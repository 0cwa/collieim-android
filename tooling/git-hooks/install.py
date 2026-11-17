#!/usr/bin/env python3
"""Install Collie's Layer trailer checks without replacing unrelated Git hooks."""

from pathlib import Path
import subprocess
import sys


FILES = ("commit-msg", "pre-push", "collie_check_layer_trailers.py")
MARKER = b"Collie Layer trailer hooks:"


def install():
    source = Path(__file__).resolve().parent
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
    try:
        install()
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(error, file=sys.stderr)
        sys.exit(1)
