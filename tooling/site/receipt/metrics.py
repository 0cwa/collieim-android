#!/usr/bin/env python3
"""Recompute Git changes with isolated configuration and source-tree attributes."""
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
try:
    from . import evidence
except ImportError:
    import evidence


def classify(path):
    lower = path.lower()
    if '/generated/' in lower or lower.endswith(('.pb.java', '.pb.kt')):
        return 'generated'
    if '/src/test/' in lower or '/src/androidtest/' in lower or '/tests/' in lower:
        return 'tests'
    if re.search(r'/res/values-[a-z]{2,3}(?:-r[a-z]{2})?/', lower) or '/translations/' in lower:
        return 'translations'
    if '/src/main/' in lower:
        return 'production'
    return 'other'


def parse_metrics(raw):
    result = {category: [0, 0] for category in ('production', 'tests', 'translations', 'generated', 'other')}
    result['binary_paths'] = []
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


def isolated_diff(source, arguments):
    """Use an empty bare repository sharing only objects, never local config/attributes."""
    env = {k: v for k, v in os.environ.items() if not k.startswith('GIT_')}
    env.update(GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL=os.devnull,
               GIT_ATTR_NOSYSTEM='1', GIT_ATTR_SOURCE=source,
               GIT_NO_LAZY_FETCH='1', LC_ALL='C')
    common = subprocess.check_output(['git', 'rev-parse', '--path-format=absolute', '--git-common-dir'],
                                     cwd=evidence.ROOT, env=env, text=True).strip()
    objects = Path(common) / 'objects'
    scratch = Path.home() / 'agent-scratch/collieim-android/receipt-tests'
    scratch.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='receipt-git-', dir=scratch) as directory:
        repo = Path(directory) / 'objects.git'
        subprocess.run(['git', '-c', 'init.templateDir=', 'init', '--quiet', '--bare', str(repo)],
                       cwd=directory, env=env, check=True)
        (repo / 'objects/info/alternates').write_text(str(objects.resolve()) + '\n', encoding='utf-8')
        command = ['git', '--git-dir', str(repo), '-c', 'core.attributesFile=' + os.devnull,
                   '-c', 'diff.algorithm=myers', *arguments]
        return subprocess.check_output(command, cwd=directory, env=env)


def change_metrics(commit):
    parents = isolated_diff(commit, ['rev-list', '--parents', '-n', '1', commit]).split()
    if len(parents) != 2:
        raise ValueError('Metrics require a single-parent change')
    raw = isolated_diff(commit, ['diff-tree', '--no-commit-id', '--no-ext-diff', '--no-textconv',
                                 '--no-renames', '--diff-algorithm=myers', '-r', '--numstat', '-z', commit, '--'])
    return parse_metrics(raw)


def release_metrics(base, source):
    raw = isolated_diff(source, ['diff', '--no-ext-diff', '--no-textconv', '--no-renames',
                                  '--diff-algorithm=myers', '--numstat', '-z', base, source, '--'])
    return parse_metrics(raw)


def main():
    import freeze
    data, _ = freeze.load_editorial()
    evidence.validate(data)
    changes = sorted({f['change'] for f in data['features']})
    print(json.dumps({commit: change_metrics(commit) for commit in changes}, indent=2, sort_keys=True))


if __name__ == '__main__':
    main()
