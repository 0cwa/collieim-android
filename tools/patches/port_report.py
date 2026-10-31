#!/usr/bin/env python3
"""Render physical Git diff measurements and explicitly supplied hunk evidence."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

TYPES = {'conflict_resolution', 'signal_api_compile_fix', 'behavior_preservation'}
HUNK = re.compile(rb'^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@')
OLD = 'c47a74ba0961903eeaeda6d22347a990056c3e83'
MOLLY = '0e202dda90785771ff8c87526eaaf30c655f54f3'


def git(*args):
    return subprocess.run(['git', *args], check=True, stdout=subprocess.PIPE).stdout


def numstat(old, new):
    records = {}
    for row in filter(None, git('diff', '--no-renames', '--numstat', '-z', old, new).split(b'\0')):
        added, deleted, path = row.split(b'\t', 2)
        records[path] = None if added == b'-' else (int(added), int(deleted))
    return records


def modes(old, new):
    fields = git('diff', '--no-renames', '--raw', '-z', old, new).split(b'\0')
    result = {}
    for i in range(0, len(fields) - 1, 2):
        if not fields[i]:
            continue
        header = fields[i].split()
        result[fields[i+1]] = (header[0][1:].decode(), header[1].decode())
    return result


def require_tree(value):
    if not isinstance(value, str) or not re.fullmatch('[0-9a-f]{40}', value):
        raise ValueError('expected an immutable full tree ID')
    if git('cat-file', '-t', value).strip() != b'tree':
        raise ValueError('expected a tree object: ' + value)


def parse_hunks(patch):
    """Read actual hunk body counts, including lines beginning +++ and ---."""
    lines = patch.splitlines(keepends=True)
    result = []
    i = 0
    while i < len(lines):
        match = HUNK.match(lines[i])
        if not match:
            i += 1
            continue
        old_left = int(match.group(2) or 1)
        new_left = int(match.group(4) or 1)
        body = [lines[i]]
        added = deleted = 0
        i += 1
        while old_left or new_left:
            if i >= len(lines):
                raise ValueError('truncated hunk')
            line = lines[i]
            body.append(line)
            if line.startswith(b' '):
                old_left -= 1; new_left -= 1
            elif line.startswith(b'+'):
                new_left -= 1; added += 1
            elif line.startswith(b'-'):
                old_left -= 1; deleted += 1
            elif not line.startswith(b'\\'):
                raise ValueError('invalid hunk line')
            if old_left < 0 or new_left < 0:
                raise ValueError('hunk exceeds declared length')
            i += 1
        result.append((added, deleted, b''.join(body)))
    return result


def totals(records):
    values = [v for v in records.values() if v is not None]
    return sum(v[0] for v in values), sum(v[1] for v in values), sum(v is None for v in records.values())


def render(config, evidence, ready=False):
    port = config['port']
    before, target = port['mechanical_tree'], config['target_tree']
    source = config['signal']['commit']
    require_tree(before)
    require_tree(target)
    if not isinstance(source, str) or not re.fullmatch('[0-9a-f]{40}', source) or git('cat-file', '-t', source).strip() != b'commit':
        raise ValueError('expected an immutable full Signal commit ID')
    rows = evidence.get('hunks', {})
    if not isinstance(rows, dict):
        raise ValueError('hunks metadata must be an object')
    seen = set()
    stats = numstat(before, target)
    file_modes = modes(before, target)
    if set(file_modes) != set(stats):
        raise ValueError('raw diff paths do not match numstat')
    output = ['# Port report', '', 'Physical Git diff lines; counts do not establish authorship or necessity.', '']
    for label, a, b in [('Molly patchset', OLD, MOLLY), ('Clean merge contribution', source, before),
                        ('AI-assisted compatibility changes before minimization', before, target),
                        ('Compared with Molly before minimization', MOLLY, target)]:
        adds, dels, binary = totals(numstat(a, b))
        output.append(f'- {label}: {adds} added, {dels} deleted, {adds+dels} total text lines; {binary} binary paths (`{a}` → `{b}`).')
    a, d, _ = totals(stats); ma, md, _ = totals(numstat(OLD, MOLLY))
    output += ['', f'Compatibility share: {a+d} / {ma+md} physical added plus deleted text lines = {(a+d)/(ma+md)*100:.3f}%.' if ma+md else 'Compatibility share: undefined (zero denominator).',
               'After minimization: unmeasured.', '', 'Molly exclusions, including partial-file exclusions: unmeasured.', '',
               'Clean merge counts above are whole-file Git diff totals; no clean-merge hunk classification is asserted.', '',
               '| Hunk ID | Path | Kind | Old mode | New mode | Added | Deleted | Type | Reason | Molly equivalent | Revert gate and result |',
               '|---|---|---|---|---|---:|---:|---|---|---|---|']
    for path, expected in sorted(stats.items()):
        display = os.fsdecode(path).replace('|', '\\|').replace('\n', '\\n')
        if expected is None:
            key = hashlib.sha256(before.encode()+b'\0'+target.encode()+b'\0'+path+b'\0binary').hexdigest()[:20]
            hunks = [(0, 0, b'binary', key, 'binary')]
        else:
            patch = git('diff', '--no-ext-diff', '--no-renames', '--unified=3', before, target, '--', os.fsdecode(path))
            hunks = [(x, y, body, hashlib.sha256(before.encode()+b'\0'+target.encode()+b'\0'+path+b'\0'+body).hexdigest()[:20], 'text') for x, y, body in parse_hunks(patch)]
            if (sum(h[0] for h in hunks), sum(h[1] for h in hunks)) != expected:
                raise ValueError('hunk counts do not match numstat for ' + display)
        old_mode, new_mode = file_modes[path]
        if expected is not None and old_mode != new_mode:
            key = hashlib.sha256(before.encode()+b'\0'+target.encode()+b'\0'+path+b'\0mode').hexdigest()[:20]
            hunks.append((0, 0, b'mode', key, 'mode'))
        for added, deleted, _, key, kind in hunks:
            if key in seen: raise ValueError('duplicate hunk ID ' + key)
            seen.add(key); item = rows.get(key, {})
            category = item.get('type', '')
            if category and category not in TYPES: raise ValueError('invalid hunk type ' + key)
            if ready and (category not in TYPES or any(not item.get(k) or item[k] == 'unmeasured' for k in ('reason', 'molly_equivalent', 'searched_branches', 'revert_gate', 'outcome'))):
                raise ValueError('missing evidence for ' + key)
            fields = [key, display, kind, old_mode, new_mode, str(added), str(deleted), category or 'unmeasured', item.get('reason', 'unmeasured'), item.get('molly_equivalent', 'unmeasured') + ' (searched: ' + item.get('searched_branches', 'unmeasured') + ')', (item.get('revert_gate', 'unmeasured') + ': ' + item.get('outcome', 'unmeasured'))]
            output.append('| ' + ' | '.join(str(f).replace('\n', ' ').replace('|', '\\|') for f in fields) + ' |')
    stale = set(rows) - seen
    if stale: raise ValueError('stale or overlapping metadata: ' + ', '.join(sorted(stale)))
    if ready and not evidence.get('exclusions_complete'):
        raise ValueError('Molly exclusion evidence is incomplete')
    return '\n'.join(output) + '\n'


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--config', default='molly-build.json'); p.add_argument('--metadata', default='patches/ports/signal-8.33.2/report-metadata.json')
    p.add_argument('--output', default='docs/PORT-REPORT.md'); p.add_argument('--check', action='store_true'); p.add_argument('--ready', action='store_true')
    args = p.parse_args()
    try:
        result = render(json.loads(Path(args.config).read_text()), json.loads(Path(args.metadata).read_text()), args.ready)
        if args.check:
            if Path(args.output).read_text() != result: raise ValueError('port report is stale')
        else: Path(args.output).write_text(result)
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as exc:
        print('port report failed: ' + str(exc), file=sys.stderr); return 1
    return 0


if __name__ == '__main__': sys.exit(main())
