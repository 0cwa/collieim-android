#!/usr/bin/env python3
"""Conservative physical diff around verified mechanical conflict choices."""
import argparse
import json
import os
import re
import subprocess
import tempfile
from collections import Counter
from pathlib import Path


def git(*args, check=True):
    return subprocess.run(['git', *args], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          check=check).stdout


def tree_entries(tree):
    entries = {}
    for item in git('ls-tree', '-rz', '--full-tree', tree).split(b'\0'):
        if item:
            meta, path = item.split(b'\t', 1)
            mode, kind, oid = meta.split(b' ')
            entries[os.fsdecode(path)] = (mode.decode(), kind.decode(), oid.decode())
    return entries


def occurrences(lines, needle):
    if not needle:
        return []
    return [i for i in range(len(lines) - len(needle) + 1) if lines[i:i + len(needle)] == needle]


def baseline(mechanical, final):
    """Return marker-free bytes only when all regions have unique contextual matches."""
    lines, end = mechanical.splitlines(keepends=True), final.splitlines(keepends=True)
    regions = []
    i = 0
    while i < len(lines):
        if lines[i].startswith((b'=======', b'>>>>>>>', b'|||||||')):
            return 'unmatched', None, []
        if lines[i].startswith(b'<<<<<<<') and not lines[i].startswith(b'<<<<<<< '):
            return 'unmatched', None, []
        if not lines[i].startswith(b'<<<<<<< '):
            i += 1
            continue
        start = i
        first = lines[i][8:].strip()
        i += 1
        a = i
        while i < len(lines) and not lines[i].startswith((b'=======', b'<<<<<<<', b'|||||||', b'>>>>>>>')):
            i += 1
        if i == len(lines) or lines[i].strip() != b'=======':
            return 'unmatched', None, []
        left = lines[a:i]
        i += 1
        b = i
        while i < len(lines) and not lines[i].startswith((b'>>>>>>> ', b'<<<<<<<', b'|||||||', b'=======')):
            i += 1
        if i == len(lines) or not lines[i].startswith(b'>>>>>>> '):
            return 'unmatched', None, []
        right = lines[b:i]
        second = lines[i][8:].strip()
        stop = i + 1
        if {first, second} != {b'MOLLY', b'SIGNAL'} or start == 0 or stop == len(lines):
            return 'unmatched', None, []
        before, after = lines[start - 1], lines[stop]
        if before.startswith((b'>>>>>>> ', b'=======')) or after.startswith(b'<<<<<<<'):
            return 'unmatched', None, []
        matches = []
        for label, side in ((first, left), (second, right)):
            if side:
                for pos in occurrences(end, [before] + side + [after]):
                    matches.append((label, side, pos, pos + len(side) + 2))
        if len(matches) != 1:
            return ('ambiguous' if matches else 'unmatched'), None, []
        label, side, lo, hi = matches[0]
        regions.append((start, stop, label, side, lo, hi))
        i = stop
    if not regions:
        return 'ordinary', mechanical, []
    spans = sorted((r[4], r[5]) for r in regions)
    if any(a[1] > b[0] for a, b in zip(spans, spans[1:])):
        return 'ambiguous', None, []
    output, choices, cursor = [], [], 0
    for start, stop, label, side, lo, hi in regions:
        output.extend(lines[cursor:start]); output.extend(side); cursor = stop
        choices.append({'region_start_line': start + 1, 'side': label.decode(),
                        'final_start_line': lo + 2, 'final_end_line': hi - 1})
    output.extend(lines[cursor:])
    return 'verified_conflict', b''.join(output), choices


def numstat(old, new, scratch):
    with tempfile.TemporaryDirectory(dir=scratch) as directory:
        directory = str(Path(directory).resolve())
        a, b = Path(directory) / 'old', Path(directory) / 'new'
        a.write_bytes(old); b.write_bytes(new)
        # Run outside the source repository, with no user/system config or attributes.
        env = {'PATH': os.environ.get('PATH', ''), 'HOME': directory, 'LC_ALL': 'C',
               'GIT_CONFIG_NOSYSTEM': '1', 'GIT_CONFIG_GLOBAL': os.devnull,
               'GIT_ATTR_NOSYSTEM': '1', 'GIT_CEILING_DIRECTORIES': str(Path(directory).parent)}
        result = subprocess.run(['git', 'diff', '--no-index', '--no-ext-diff', '--no-textconv',
                                 '--text', '--diff-algorithm=myers', '--numstat', '--', str(a), str(b)],
                                cwd=directory, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if result.returncode not in (0, 1):
            raise ValueError(result.stderr.decode(errors='replace'))
        if not result.stdout:
            return [0, 0]
        add, delete, _ = result.stdout.split(b'\t', 2)
        if add == b'-' or delete == b'-':
            raise ValueError('unexpected binary diff')
        return [int(add), int(delete)]


def assess(mechanical_tree, final_tree, scratch, declared_generated=()):
    for oid in (mechanical_tree, final_tree):
        if not re.fullmatch(r'[0-9a-f]{40}', oid) or git('cat-file', '-t', oid).strip() != b'tree':
            raise ValueError('trees must be full, existing tree object IDs')
    old, new = tree_entries(mechanical_tree), tree_entries(final_tree)
    changed = sorted(p for p in old.keys() | new.keys() if old.get(p) != new.get(p))
    declared = set(declared_generated)
    if len(declared) != len(declared_generated) or not declared <= set(changed):
        raise ValueError('generated declarations must be unique changed paths')
    result = []
    ga = gd = binaries = 0
    for path in changed:
        a, b = old.get(path), new.get(path)
        row = {'path': path, 'mechanical': a, 'final': b}
        before = git('cat-file', 'blob', a[2]) if a and a[1] == 'blob' else b''
        after = git('cat-file', 'blob', b[2]) if b and b[1] == 'blob' else b''
        binary = b'\0' in before or b'\0' in after
        if binary:
            binaries += 1
        elif (not a or a[1] == 'blob') and (not b or b[1] == 'blob'):
            add, delete = numstat(before, after, scratch)
            ga += add; gd += delete
        if path in declared:
            row['category'] = 'declared_generated_unverified'
        elif not b:
            row['category'] = 'deleted'
        elif (a and (a[0] != b[0] or a[1] != 'blob')) or b[1] != 'blob':
            row['category'] = 'mode_or_nonblob'
        else:
            if binary:
                row['category'] = 'binary'
            else:
                category, base, choices = baseline(before, after)
                row['category'] = category
                if base is not None:
                    row['residual_add_delete'] = numstat(base, after, scratch)
                    if choices:
                        row['choices'] = choices
        result.append(row)
    counts = dict(sorted(Counter(r['category'] for r in result).items()))
    return {'mechanical_tree': mechanical_tree, 'final_tree': final_tree,
            'gross_physical_add_delete': [ga, gd], 'gross_binary_paths': binaries,
            'diff_semantics': 'Git no-index Myers text diff; NUL anywhere marks a binary path; system, global and repository config and attributes ignored',
            'inventory_count': len(result), 'coverage_counts': counts,
            'generated_declarations': 'unverified; undeclared generated paths are not detected',
            'interpretation': 'Physical diffs only; no authorship, complexity or necessity inference. Gross and residual counts are not a partition.',
            'paths': result}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('mechanical_tree'); p.add_argument('final_tree')
    p.add_argument('--scratch', required=True, help='existing task scratch directory for temporary diff files')
    p.add_argument('--generated', action='append', default=[], help='declare a changed generated path (not verified)')
    args = p.parse_args()
    print(json.dumps(assess(args.mechanical_tree, args.final_tree, args.scratch, args.generated), indent=2, ensure_ascii=True))


if __name__ == '__main__':
    main()
