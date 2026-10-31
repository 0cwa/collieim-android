#!/usr/bin/env python3
"""Create a translation stage from a checked prototype and a clean before-tree worktree."""
import argparse
import json
import os
from pathlib import Path

import forest
import release
import translation_stage
import verify_complete


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, required=True)
    parser.add_argument('--worktree', type=Path, required=True)
    parser.add_argument('--prototype', type=Path, required=True)
    parser.add_argument('--source', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    data = json.loads(args.prototype.read_text())
    data.update(schema=1, type='generate-translations', source_commit=args.source,
                before_tree=forest.git(args.worktree, 'rev-parse', 'HEAD^{tree}'),
                after_tree='0' * 40)
    entries = verify_complete.tree_entries(args.repo, data['before_tree'])
    preimages = {}
    for path in data['output_sha256']:
        entry = entries.get(os.fsencode(path))
        if entry is None:
            preimages[path] = None
            continue
        if entry[0] not in (b'100644', b'100755') or entry[1] != b'blob':
            raise forest.ForestError(f'output is not a regular file in before-tree: {path}')
        preimages[path] = {'mode': entry[0].decode(), 'blob': entry[2].decode()}
    data['preimages'] = preimages
    translation_stage.validate(data, str(args.output))
    data['after_tree'] = translation_stage.generate(args.repo, args.worktree, data, forest.clean_git_env(), dry_run=True)
    # Validate completely before writing an output file.
    translation_stage.validate(data, str(args.output))
    args.output.write_text(json.dumps(data, indent=2, ensure_ascii=False) + '\n')


if __name__ == '__main__':
    main()
