#!/usr/bin/env python3
"""Export a deletion-only tree delta as a pinned resolution manifest."""
import argparse
import json
import os
from pathlib import Path

import forest
import release
import verify_complete


def export(repo, before, after, source):
    for value in (before, after, source):
        release.require_sha(value, 'tree or source commit')
    old = verify_complete.tree_entries(repo, before)
    new = verify_complete.tree_entries(repo, after)
    changed = {p for p in old.keys() | new.keys() if old.get(p) != new.get(p)}
    if not changed or any(p not in old or p in new for p in changed):
        raise forest.ForestError('tree delta must consist only of whole-file deletions')
    files = []
    for path in sorted(changed):
        mode, kind, blob = old[path]
        if kind != b'blob' or mode not in (b'100644', b'100755'):
            raise forest.ForestError(f'not a regular file: {os.fsdecode(path)!r}')
        files.append({'path': path.decode('utf-8'), 'mode': mode.decode(), 'blob': blob.decode()})
    return {'schema': 1, 'type': 'delete-files', 'source_commit': source,
            'before_tree': before, 'after_tree': after, 'files': files}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, default=Path('.'))
    parser.add_argument('--before', required=True)
    parser.add_argument('--after', required=True)
    parser.add_argument('--source', required=True)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    data = export(args.repo, args.before, args.after, args.source)
    args.output.write_text(json.dumps(data, indent=2, ensure_ascii=False) + '\n')
    # Apply the same path validation used by the assembler.
    release.deletion_manifest(args.output, str(args.output))


if __name__ == '__main__':
    main()
