#!/usr/bin/env python3
"""Recompute per-change Git churn; counts are not additive across features."""
import json
import subprocess
import render


def classify(path):
    lower = path.lower()
    if '/src/test/' in lower or '/src/androidtest/' in lower or '/tests/' in lower:
        return 'tests'
    if '/res/values-' in lower or '/translations/' in lower:
        return 'translations'
    # Unclassified paths are intentionally not asserted to be production code.
    return 'other'


def change_metrics(commit):
    parents = subprocess.check_output(['git', 'rev-list', '--parents', '-n', '1', commit], cwd=render.ROOT, text=True).split()
    if len(parents) != 2:
        raise ValueError('Metrics require a single-parent change')
    raw = subprocess.check_output(['git', 'diff-tree', '--root', '--no-commit-id', '--no-renames', '-r', '--numstat', '-z', commit], cwd=render.ROOT)
    result = {'tests': [0, 0], 'translations': [0, 0], 'other': [0, 0], 'binary_paths': []}
    for entry in raw.split(b'\0'):
        if not entry:
            continue
        added, removed, path = entry.decode('utf-8', errors='surrogateescape').split('\t', 2)
        if added == '-' or removed == '-':
            result['binary_paths'].append(path)
        else:
            bucket = result[classify(path)]
            bucket[0] += int(added)
            bucket[1] += int(removed)
    return result


def main():
    data = render.validate(json.loads(render.DATA.read_text()))
    changes = sorted({f['change'] for f in data['features']})
    print(json.dumps({commit: change_metrics(commit) for commit in changes}, indent=2, sort_keys=True))


if __name__ == '__main__':
    main()
