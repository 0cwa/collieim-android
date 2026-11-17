"""Versioned source evidence and editorial validation, independent of presentation."""
import copy
import hashlib
import json
import os
import re
import subprocess
from pathlib import Path
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[3]
DATA = Path(__file__).with_name('pilot.json')

def validate_editorial(data):
    def require_text(record, fields):
        if not all(isinstance(record.get(key), str) and record[key].strip() for key in fields):
            raise ValueError('Missing required editorial text: ' + ', '.join(fields))

    if data.get('version') != 2 or not re.fullmatch(r'[0-9a-f]{40}', data.get('source', '')):
        raise ValueError('Unsupported receipt version or source')
    require_text(data, ('app_headline', 'release_headline'))
    if not re.fullmatch(r'v[0-9][0-9A-Za-z.-]*', data.get('release', '')):
        raise ValueError('Invalid release tag')
    history = data.get('release_history', [])
    if (not isinstance(history, list) or
            any(not isinstance(tag, str) or not re.fullmatch(r'v[0-9][0-9A-Za-z.-]*', tag) for tag in history) or
            len(set(history)) != len(history) or data['release'] in history):
        raise ValueError('Invalid or duplicate release history')
    if not re.fullmatch(r'[0-9a-f]{40}', data.get('release_base', '')) or not re.fullmatch(r'v[0-9][0-9A-Za-z.-]*', data.get('release_base_tag', '')):
        raise ValueError('Invalid release baseline')
    if data.get('release_status') not in ('Release candidate', 'Beta', 'Stable', 'In development'):
        raise ValueError('Unknown release status')
    if data.get('membership', {}).get('status') != 'unknown':
        raise ValueError('Approved membership adapter is not available')
    if sum(f.get('placement') == 'lead' for f in data['features']) > 1:
        raise ValueError('At most one lead subject is allowed')
    app_visuals = {'connection': 'Connection', 'navigation': 'Navigation', 'personal': 'Contacts', 'shield': 'Choices'}
    for f in data['features']:
        require_text(f, ('title', 'display_title', 'summary', 'origin', 'category', 'path', 'limit'))
        if f.get('placement') not in ('lead', 'standard', 'hidden') or app_visuals.get(f.get('visual')) != f.get('category'):
            raise ValueError('Unknown subject placement or visual category')
        if f['placement'] != 'hidden' and f.get('change_type') not in ('Adaptation', 'Restoration'):
            raise ValueError('Unknown patch change type')
        if 'diagram' in f:
            d = f['diagram']
            if not isinstance(d, dict) or not isinstance(d.get('caption'), str) or not isinstance(d.get('cases'), list) or not d['cases'] or any(not isinstance(c, dict) or not all(isinstance(c.get(k), str) and c[k] for k in ('condition', 'result')) for c in d['cases']):
                raise ValueError('Incomplete subject diagram')
    release_visuals = {'clearance': 'Unlocking', 'navigation': 'Navigation'}
    for c in data['release_changes']:
        require_text(c, ('title', 'display_title', 'summary', 'origin', 'category', 'change_type', 'path'))
        if release_visuals.get(c.get('visual')) != c.get('category'):
            raise ValueError('Unknown release illustration category')
        keys = ('top', 'gap', 'bottom', 'caption') if c['visual'] == 'clearance' else ('from', 'to', 'note', 'caption')
        if not isinstance(c.get('diagram'), dict) or not all(isinstance(c['diagram'].get(k), str) and c['diagram'][k] for k in keys):
            raise ValueError('Incomplete release illustration')
    return data


def validate(data, root=None):
    validate_editorial(data)
    root = ROOT if root is None else root
    env = dict(os.environ, GIT_NO_LAZY_FETCH='1')
    source = data['source']
    tag_commit = subprocess.check_output(['git', 'rev-parse', '--verify', 'refs/tags/' + data['release'] + '^{commit}'], cwd=root, env=env, text=True).strip()
    if tag_commit != source:
        raise ValueError('Release tag does not match source')
    for feature in data['features']:
        if not re.fullmatch(r'[0-9a-f]{40}', feature['change']):
            raise ValueError('Invalid change commit')
        if feature['upstream'] and urlparse(feature['upstream']).scheme != 'https':
            raise ValueError('Upstream links must use HTTPS')
        subprocess.run(['git', 'merge-base', '--is-ancestor', feature['change'], source], cwd=root, env=env, check=True)
        blob = subprocess.check_output(['git', 'rev-parse', '--verify', source + ':' + feature['path']], cwd=root, env=env, text=True).strip()
        if blob != feature['source_blob']:
            raise ValueError('Source-bound evidence does not match release tree: ' + feature['path'])
        for support in feature.get('supporting_paths', []):
            blob = subprocess.check_output(['git', 'rev-parse', '--verify', source + ':' + support['path']], cwd=root, env=env, text=True).strip()
            if blob != support['blob']:
                raise ValueError('Supporting evidence does not match release tree: ' + support['path'])
        for path in feature.get('absent_paths', []):
            if subprocess.run(['git', 'cat-file', '-e', source + ':' + path], cwd=root, env=env, stderr=subprocess.DEVNULL).returncode == 0:
                raise ValueError('Expected removed path is present in release tree')
    if subprocess.check_output(['git', 'rev-parse', '--verify', 'refs/tags/' + data['release_base_tag'] + '^{commit}'], cwd=root, env=env, text=True).strip() != data['release_base']:
        raise ValueError('Release baseline does not match tag')
    release_commits = set(subprocess.check_output(['git', 'rev-list', data['release_base'] + '..' + source], cwd=root, env=env, text=True).splitlines())
    for change in data['release_changes']:
        if not re.fullmatch(r'[0-9a-f]{40}', change.get('change', '')):
            raise ValueError('Invalid release change commit')
        if change['change'] not in release_commits:
            raise ValueError('Release change is outside the comparison range')
        parents = subprocess.check_output(['git', 'rev-list', '--parents', '-n', '1', change['change']], cwd=root, env=env, text=True).split()
        if len(parents) != 2:
            raise ValueError('Release change evidence must be a single-parent commit')
        expected = change['path'].encode('utf-8')
        touched = subprocess.check_output(['git', '--literal-pathspecs', 'diff-tree', '--no-commit-id', '--name-only', '-z', '-r', '--no-renames', change['change'], '--', change['path']], cwd=root).split(b'\0')
        if expected not in touched:
            raise ValueError('Release change did not touch its cited path')
        changed = subprocess.check_output(['git', '--literal-pathspecs', 'diff', '--name-only', '-z', '--no-renames', data['release_base'], source, '--', change['path']], cwd=root).split(b'\0')
        if expected not in changed:
            raise ValueError('Release entry has no change at its cited path')
    return data


def encode(value):
    return (json.dumps(value, sort_keys=True, ensure_ascii=False, indent=2) + '\n').encode('utf-8')


def record_hashes(data):
    result = {}
    for item in data['features'] + data['release_changes']:
        name = item['_record_path']
        if name in result:
            raise ValueError('Duplicate record path')
        raw = json.dumps({k: v for k, v in item.items() if k != '_record_path'},
                         sort_keys=True, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
        result[name] = hashlib.sha256(raw).hexdigest()
    return result


def manifest(data):
    result = {k: copy.deepcopy(v) for k, v in data.items() if k not in ('features', 'release_changes')}
    result['subject_order'] = [item['_record_path'] for item in data['features']]
    result['release_order'] = [item['_record_path'] for item in data['release_changes']]
    return result


def validate_receipt(receipt):
    version = receipt.get('receipt_version')
    if version not in (1, 2):
        raise ValueError('Unsupported frozen receipt version; supported: 1 (asset-bound), 2 (source-only)')
    if receipt.get('membership', {}).get('status') != 'unknown':
        raise ValueError('Approved membership adapter is not available')
    history = receipt.get('published_releases')
    if history is not None and (not isinstance(history, list) or any(not isinstance(tag, str) or not re.fullmatch(r'v[0-9][0-9A-Za-z.-]*', tag) for tag in history) or len(history) != len(set(history))):
        raise ValueError('Invalid published release index')
    data = validate_editorial(receipt['editorial'])
    if receipt['membership'] != data['membership']:
        raise ValueError('Receipt membership differs from editorial source')
    if receipt.get('record_hashes') != record_hashes(data) or receipt.get('manifest') != manifest(data):
        raise ValueError('Receipt content differs from its recorded hashes/manifest; re-freeze reviewed records')
    if version == 2 and 'asset_hashes' in receipt:
        raise ValueError('Source receipt v2 must keep presentation hashes in the build manifest')
    if receipt.get('classification') != 'path-rules-v1' or receipt.get('diff_method') != 'isolated-myers-source-attributes-v1':
        raise ValueError('Unsupported source metric method')
    for category in ('production', 'tests', 'translations', 'generated', 'other'):
        values = receipt.get('release_metrics', {}).get(category)
        if not isinstance(values, list) or len(values) != 2 or any(type(v) is not int or v < 0 for v in values):
            raise ValueError('Invalid source metrics')
    return receipt


def load_snapshot(path=DATA):
    """The signed, reviewed source snapshot is the offline build's trust boundary."""
    try:
        from .freeze import load_editorial
    except ImportError:
        from freeze import load_editorial
    try:
        data, _ = load_editorial(path)
    except FileNotFoundError as error:
        raise ValueError(f'Missing reviewed record {error.filename}; restore it or update the manifest and re-freeze') from error
    frozen = path.with_name('source-receipt.json')
    try:
        receipt = validate_receipt(json.loads(frozen.read_text()))
    except FileNotFoundError as error:
        raise ValueError(f'Missing {frozen}; run freeze.py --out {frozen} in a source checkout') from error
    current, stored = record_hashes(data), receipt['record_hashes']
    changed = sorted(k for k in current.keys() & stored.keys() if current[k] != stored[k])
    added, removed = sorted(current.keys() - stored.keys()), sorted(stored.keys() - current.keys())
    old, new = receipt['manifest'], manifest(data)
    fields = sorted(k for k in old.keys() | new.keys() if old.get(k) != new.get(k))
    if changed or added or removed or fields:
        raise ValueError(f'Source receipt needs review and re-freeze: added={added}, removed={removed}, changed={changed}, manifest={fields}. Run freeze.py --previous {frozen} --out {frozen} after reviewing source evidence.')
    return receipt
