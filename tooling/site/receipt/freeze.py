#!/usr/bin/env python3
"""Resolve a development receipt from pinned Git objects; not release approval."""
import argparse
import copy
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
try:
    from . import metrics
except ImportError:
    import metrics
try:
    from . import evidence
except ImportError:
    import evidence


def load_editorial(manifest_path=evidence.DATA, revision=None):
    """Read reviewed records from a working tree or one explicit Git commit."""
    root = evidence.ROOT.resolve()
    # In revision mode, use lexical repository-relative names. Resolving a
    # working-tree symlink would read a different object than the named path.
    manifest_path = Path(os.path.abspath(manifest_path))
    base = manifest_path.parent
    revision_oid = None
    if revision:
        revision_oid = subprocess.check_output(
            ['git', 'rev-parse', '--verify', '--end-of-options', revision + '^{commit}'],
            cwd=root, env=dict(os.environ, GIT_NO_LAZY_FETCH='1'), text=True).strip()
        base.relative_to(root)

    def read(path):
        path = Path(os.path.abspath(path))
        path.relative_to(base)
        if revision_oid:
            relative = path.relative_to(root).as_posix()
            return subprocess.check_output(['git', 'show', revision_oid + ':' + relative], cwd=root, env=dict(os.environ, GIT_NO_LAZY_FETCH='1'))
        resolved = path.resolve()
        resolved.relative_to(base.resolve())
        return resolved.read_bytes()

    data = json.loads(read(manifest_path))
    seen = set()
    for input_key, output_key, prefix in [('subjects', 'features', 'subjects'), ('release_records', 'release_changes', 'changes')]:
        records = []
        for name in data.pop(input_key):
            if not isinstance(name, str) or not re.fullmatch(prefix + r'/[a-z0-9][a-z0-9-]*\.json', name) or name in seen:
                raise ValueError('Invalid or duplicate record path')
            seen.add(name)
            record = json.loads(read(base / PurePosixPath(name)))
            if '_record_path' in record:
                raise ValueError('Reserved record field')
            record['_record_path'] = name
            records.append(record)
        data[output_key] = records
    return data, revision_oid


record_hashes = evidence.record_hashes


def freeze(data, previous=None, editorial_revision=None):
    try:
        evidence.validate(data)
    except subprocess.CalledProcessError as error:
        raise ValueError('Source objects or refs unavailable. Re-freeze in a source checkout with the pinned release/baseline tags and change ancestry; site builds use the committed source-receipt.json.') from error
    hashes = record_hashes(data)
    manifest = evidence.manifest(data)
    reconciliation = {'status': 'not-compared', 'added': [], 'removed': [], 'changed': [], 'manifest_changed': []}
    if previous is not None:
        if previous.get('receipt_version') not in (1, 2) or 'record_hashes' not in previous:
            raise ValueError('Unsupported previous receipt')
        old = previous['record_hashes']
        prior_manifest = previous.get('manifest')
        manifest_changed = sorted(k for k in manifest.keys() | (prior_manifest or {}).keys() if prior_manifest is None or manifest.get(k) != prior_manifest.get(k))
        reconciliation = {
            'status': 'review-needed' if old != hashes or manifest_changed else 'unchanged',
            'added': sorted(hashes.keys() - old.keys()),
            'removed': sorted(old.keys() - hashes.keys()),
            'changed': sorted(k for k in hashes.keys() & old.keys() if hashes[k] != old[k]),
            'manifest_changed': manifest_changed,
        }

    return {
        'receipt_version': 2,
        'membership': copy.deepcopy(data['membership']),
        'editorial': copy.deepcopy(data),
        'editorial_revision': editorial_revision,
        'record_hashes': hashes,
        'manifest': manifest,
        'reconciliation': reconciliation,
        'release_metrics': metrics.release_metrics(data['release_base'], data['source']),
        'classification': 'path-rules-v1',
        'diff_method': 'isolated-myers-source-attributes-v1',
        'git_version': subprocess.check_output(['git', '--version'], cwd=evidence.ROOT, text=True).strip(),
    }


def encode(receipt):
    return (json.dumps(receipt, sort_keys=True, ensure_ascii=False, indent=2) + '\n').encode('utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, default=evidence.DATA)
    parser.add_argument('--editorial-revision', help='Read the manifest and records from this Git commit')
    parser.add_argument('--previous', type=Path, help='Compare editorial records with a previous frozen receipt')
    parser.add_argument('--out', type=Path)
    parser.add_argument('--check', action='store_true', help='Check committed source receipt against records without Git')
    args = parser.parse_args()
    if args.check:
        evidence.load_snapshot(args.input)
        return
    if not args.out:
        parser.error('--out is required unless --check is used')
    data, revision = load_editorial(args.input, args.editorial_revision)
    previous = json.loads(args.previous.read_text(encoding='utf-8')) if args.previous else None
    args.out.write_bytes(encode(freeze(data, previous, revision)))


if __name__ == '__main__':
    main()
