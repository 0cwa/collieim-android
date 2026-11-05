#!/usr/bin/env python3
"""Identify the validated pre-profile base for a trusted main snapshot."""

import json
import subprocess
import sys

from scope import INITIAL_MAIN


def git(*args):
    return subprocess.check_output(['git', *args], text=True).strip()


def main():
    # Only first-parent history can represent a change that actually landed on
    # main; imported branch histories must not choose the validation base.
    commits = git('rev-list', '--first-parent', '--reverse', 'HEAD', '--', 'molly-build.json').splitlines()
    for commit in commits:
        blob = subprocess.run(['git', 'show', commit + ':molly-build.json'], text=True, capture_output=True)
        if blob.returncode:
            continue
        config = json.loads(blob.stdout)
        if 'profiles' in config:
            parents = git('rev-list', '--parents', '-n', '1', commit).split()
            if len(parents) < 2:
                raise ValueError('profile introduction has no first parent')
            print('main-snapshot ' + parents[1])
            return
    print('main-snapshot-transition ' + INITIAL_MAIN)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, json.JSONDecodeError, subprocess.CalledProcessError) as exc:
        print('main snapshot base failed: ' + str(exc), file=sys.stderr)
        sys.exit(1)
