#!/usr/bin/env python3
"""Render experimental development receipts; this is not release approval evidence."""
import argparse
import base64
import html
import json
from pathlib import Path
import re
import subprocess
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[3]
DATA = Path(__file__).with_name('pilot.json')
REPO = 'https://github.com/0cwa/collieim-android'


def validate(data):
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
    source = data['source']
    tag_commit = subprocess.check_output(['git', 'rev-parse', '--verify', 'refs/tags/' + data['release'] + '^{commit}'], cwd=ROOT, text=True).strip()
    if tag_commit != source:
        raise ValueError('Release tag does not match source')
    for feature in data['features']:
        if not re.fullmatch(r'[0-9a-f]{40}', feature['change']):
            raise ValueError('Invalid change commit')
        if feature['upstream'] and urlparse(feature['upstream']).scheme != 'https':
            raise ValueError('Upstream links must use HTTPS')
        subprocess.run(['git', 'merge-base', '--is-ancestor', feature['change'], source], cwd=ROOT, check=True)
        blob = subprocess.check_output(['git', 'rev-parse', '--verify', source + ':' + feature['path']], cwd=ROOT, text=True).strip()
        if blob != feature['source_blob']:
            raise ValueError('Source-bound evidence does not match release tree')
        for support in feature.get('supporting_paths', []):
            blob = subprocess.check_output(['git', 'rev-parse', '--verify', source + ':' + support['path']], cwd=ROOT, text=True).strip()
            if blob != support['blob']:
                raise ValueError('Supporting evidence does not match release tree')
        for path in feature.get('absent_paths', []):
            if subprocess.run(['git', 'cat-file', '-e', source + ':' + path], cwd=ROOT, stderr=subprocess.DEVNULL).returncode == 0:
                raise ValueError('Expected removed path is present in release tree')
    if subprocess.check_output(['git', 'rev-parse', '--verify', 'refs/tags/' + data['release_base_tag'] + '^{commit}'], cwd=ROOT, text=True).strip() != data['release_base']:
        raise ValueError('Release baseline does not match tag')
    release_commits = set(subprocess.check_output(['git', 'rev-list', data['release_base'] + '..' + source], cwd=ROOT, text=True).splitlines())
    for change in data['release_changes']:
        if not re.fullmatch(r'[0-9a-f]{40}', change.get('change', '')):
            raise ValueError('Invalid release change commit')
        if change['change'] not in release_commits:
            raise ValueError('Release change is outside the comparison range')
        parents = subprocess.check_output(['git', 'rev-list', '--parents', '-n', '1', change['change']], cwd=ROOT, text=True).split()
        if len(parents) != 2:
            raise ValueError('Release change evidence must be a single-parent commit')
        expected = change['path'].encode('utf-8')
        touched = subprocess.check_output(['git', '--literal-pathspecs', 'diff-tree', '--no-commit-id', '--name-only', '-z', '-r', '--no-renames', change['change'], '--', change['path']], cwd=ROOT).split(b'\0')
        if expected not in touched:
            raise ValueError('Release change did not touch its cited path')
        changed = subprocess.check_output(['git', '--literal-pathspecs', 'diff', '--name-only', '-z', '--no-renames', data['release_base'], source, '--', change['path']], cwd=ROOT).split(b'\0')
        if expected not in changed:
            raise ValueError('Release entry has no change at its cited path')
    return data


def asset_hashes():
    import hashlib
    paths = ('docs/site/assets/fonts/manrope.ttf', 'docs/site/assets/fonts/sora.ttf', 'docs/site/assets/art/landscape-hero.svg', 'docs/site/assets/collie-icon.svg', 'docs/site/assets/site.css', 'docs/site/assets/art/landscape-transition.svg', 'tooling/site/receipt/stories.css')
    return {path: hashlib.sha256((ROOT / path).read_bytes()).hexdigest() for path in paths}


def render(receipt, layout):
    """Render frozen, selected stories; never infer membership or query Git."""
    from urllib.parse import quote
    if receipt.get('receipt_version') != 1:
        raise ValueError('Unsupported frozen receipt')
    if receipt.get('asset_hashes') != asset_hashes():
        raise ValueError('Receipt assets differ from this renderer checkout')
    if receipt.get('membership', {}).get('status') != 'unknown':
        raise ValueError('Approved membership adapter is not available')
    data = receipt['editorial']
    e = html.escape
    assets = ROOT / 'docs/site/assets'
    def uri(path):
        kind = 'font/ttf' if path.suffix == '.ttf' else 'image/svg+xml'
        return 'data:' + kind + ';base64,' + base64.b64encode(path.read_bytes()).decode('ascii')
    # Share the site's actual palette, theme roles and typography, not a second foundation.
    foundation = (assets / 'site.css').read_text().split('*,*::before')[0]
    foundation = re.sub(r'url\((fonts/[^)]+)\)', lambda m: 'url(' + uri(assets / m[1]) + ')', foundation)
    css = Path(__file__).with_name('stories.css').read_text()
    mark = uri(assets / 'collie-icon.svg')
    source = data['source']
    identity = f'<a href="{REPO}/tree/{source}">Source {source[:7]}</a>'
    foundation_credit = '<p class="foundation">Built on <a href="https://signal.org/">Signal</a> and <a href="https://molly.im/">Molly</a>.</p>'
    release_credit = '<p class="foundation">Built on <a href="https://signal.org/">Signal</a> and <a href="https://molly.im/">Molly</a>.</p>'
    status = f'<p class="snapshot">Source preview · {e(data["release"])}</p><p class="membership">Release membership is unknown.</p>'
    def evidence(item):
        refs = ''.join(f'<li><a href="{REPO}/blob/{source}/{quote(ref["path"], safe="/")}">{e(ref["label"])}</a></li>' for ref in item.get('supporting_paths', []))
        upstream = f'<p><a href="{e(item["upstream"], quote=True)}">Original source</a></p>' if item.get('upstream') else ''
        limit = f'<p>{e(item["limit"])}</p>' if item.get('limit') and not item.get('visible_limit') else ''
        conditions = item.get('diagram', {}).get('cases', [])
        setting = '<dl class="conditions">' + ''.join(f'<div><dt>{e(c["condition"])}</dt><dd>{e(c["result"])}</dd></div>' for c in conditions) + '</dl>' if conditions else ''
        return f'''<details><summary>Source<span class="sr-only">: {e(item['display_title'])}</span></summary><div class="evidence">{upstream}{setting}<h4>File</h4><p><a href="{REPO}/blob/{source}/{quote(item['path'], safe='/')}">{e(item['path'])}</a></p><p><a href="{REPO}/commit/{item['change']}">Change commit</a></p><ul>{refs}</ul>{limit}</div></details>'''
    def anchor(item):
        return Path(item['_record_path']).stem

    def story(item, release=False):
        limit = f'<p class="limit">{e(item["visible_limit"])}</p>' if item.get('visible_limit') else ''
        kind = item['change_type']
        tone = 'adaptation' if kind == 'Adaptation' else 'layout' if kind == 'Layout fix' else 'restoration'
        return f'''<article class="{'edition-story' if release else 'subject'}" id="{e(anchor(item))}"><div class="patch-kind {tone}">{e(kind)}<span>{e(item['category'])}</span></div><div class="story-copy"><h2>{e(item['display_title'])}</h2><p>{e(item['summary'])}</p>{limit}<p class="origin">{e(item['origin'])}</p>{evidence(item)}</div></article>'''
    visible = [f for f in data['features'] if f['placement'] != 'hidden']
    selected = ''.join(story(c, True) for c in data['release_changes']) or '<p>No selected release story is available for this version.</p>'
    context = f'<div class="source-context">{status}<p>{identity}</p></div>'
    if layout == 'release':
        title = "What’s new in Collie"
        history = ''.join(f'<li><a class="history-card" href="{REPO}/releases/tag/{quote(tag, safe="")}"><strong>{e(tag)}</strong><span>Release notes ↗</span></a></li>' for tag in data.get('release_history', []))
        archive = f'<ol class="history-list">{history}</ol>' if history else '<p>Earlier releases will appear here.</p>'
        added, removed = receipt['release_metrics']['production']
        comparison = f'''<details class="comparison"><summary>Changes</summary><div class="evidence"><p>Difference from <a href="{REPO}/tree/{data['release_base']}">{e(data['release_base_tag'])}</a></p><p class="metric">Production lines: <span class="added">+{added}<span class="sr-only"> added</span></span> / <span class="removed">−{removed}<span class="sr-only"> removed</span></span></p><p>Tests excluded. Path-based estimate.</p><p><a href="{REPO}/compare/{data['release_base']}...{source}">Source diff</a></p></div></details>'''
        content = f'''<div class="shell"><header class="page-heading"><h1>{title}</h1><a href="app.html">Patch explorer →</a></header><div class="release-layout"><aside class="release-index"><h2>Selected edition</h2><a href="#current-edition">{e(data['release'])}</a><a href="#earlier-releases">Earlier releases</a></aside><div class="release-feed"><section id="current-edition" class="current-edition"><header class="edition-title"><h2>{e(data['release'])}</h2><p>{e(data['release_headline'])}</p>{context}<p>{len(data['release_changes'])} patches in this preview.</p><a href="{REPO}/releases/tag/{quote(data['release'], safe='')}">GitHub release notes ↗</a></header>{comparison}<div class="edition-patches">{selected}</div>{release_credit}</section><section class="history" id="earlier-releases"><h2>Earlier releases</h2><p>Release notes on GitHub.</p>{archive}</section><p class="related">Inside Collie: <a href="app.html">Browse app-wide patches →</a></p></div></div></div>'''
    elif layout == 'app':
        title = 'Patch explorer'
        index = ''.join(f'<li><a href="#{e(anchor(f))}">{e(f["display_title"])}</a></li>' for f in visible)
        subjects = ''.join(story(f) for f in visible)
        content = f'''<div class="shell"><header class="page-heading"><h1>{title}</h1><a href="release.html">In {e(data['release'])} →</a></header><div class="patch-layout"><aside class="patch-index"><nav aria-label="Patch index"><h2>In this selection</h2><ul>{index}</ul></nav>{foundation_credit}</aside><div class="patch-reading"><div class="selection-note"><p>A selection of Collie’s app-wide patches, including work from earlier releases.</p>{context}</div>{subjects}<details class="selection-source"><summary>Source<span class="sr-only">: selection boundaries</span></summary><div class="evidence"><p>Source label: {e(data['release_status'])}.</p><p>Exact Signal and Molly baselines are unavailable for app-wide line counts.</p></div></details></div></div></div>'''
    else:
        raise ValueError('Unknown view')
    return f'''<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>{title} · Source preview</title><style>{foundation}{css}</style></head><body><a class="skip" href="#main">Skip to content</a><header class="masthead"><nav class="shell" aria-label="Main"><a class="brand" href="https://collie.im/"><img src="{mark}" width="40" height="40" alt="">Collie</a><a href="release.html" {'aria-current="page"' if layout == 'release' else ''}>What’s new</a><details class="menu"><summary>Explore</summary><div><a href="app.html">Patch explorer</a><a href="https://collie.im/#community">Community</a><a href="https://collie.im/#get">Get Collie</a></div></details></nav></header><main id="main">{content}</main><footer class="shell"><p>If Collie is useful to you, <a href="https://collie.im/#donate-title">support its development</a>.</p><p>Experimental pilot. Behavior checks have not been run for this preview.</p><a href="{REPO}">Explore the source ↗</a></footer></body></html>'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--layout', choices=['app', 'release'], required=True)
    parser.add_argument('--receipt', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    receipt = json.loads(args.receipt.read_text(encoding='utf-8'))
    args.out.write_text(render(receipt, args.layout), encoding='utf-8')


if __name__ == '__main__':
    main()
