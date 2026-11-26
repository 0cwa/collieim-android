#!/usr/bin/env python3
"""Opt in to Collie's Layer trailer hooks for maintainers and agents."""

import argparse
from pathlib import Path
import subprocess
import sys


FILES = ("commit-msg", "pre-push", "collie_check_layer_trailers.py")
MARKER = b"Collie Layer trailer hooks:"


def install():
    source = Path(__file__).resolve().parent
    global_hooks = subprocess.run(
        ["git", "config", "--global", "--includes", "--get", "core.hooksPath"],
        stdout=subprocess.DEVNULL,
    )
    if global_hooks.returncode == 0:
        print(
            "WARNING: core.hooksPath is set globally. If this repository uses that "
            "directory, installing Collie's hooks there can affect other repositories.",
            file=sys.stderr,
        )
    destination = Path(subprocess.check_output(
        ["git", "rev-parse", "--path-format=absolute", "--git-path", "hooks"], text=True
    ).strip())
    # Validate all destinations before writing any files. Copies in Git's common
    # hooks directory survive checkout and also cover this clone's worktrees.
    for name in FILES:
        target = destination / name
        if target.is_symlink() or (target.exists() and (
            not target.is_file() or MARKER not in b"\n".join(target.read_bytes().splitlines()[:2])
        )):
            raise ValueError(f"Refusing to overwrite an unrelated hook: {target}")
    destination.mkdir(parents=True, exist_ok=True)
    for name in FILES:
        target = destination / name
        target.write_bytes((source / name).read_bytes())
        target.chmod(0o644 if name.endswith(".py") else 0o755)
    print(f"Installed Collie commit-msg and pre-push hooks in {destination}.")


if __name__ == "__main__":
    argparse.ArgumentParser(description=__doc__).parse_args()
    print("Collie hooks are opt-in for maintainers and agents. Fork contributors can skip them.")
    try:
        install()
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(error, file=sys.stderr)
        sys.exit(1)
