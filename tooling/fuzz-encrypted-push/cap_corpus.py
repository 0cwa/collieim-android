#!/usr/bin/env python3
"""Keep the scheduled Jazzer cache small and deterministic."""

import argparse
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path)
    parser.add_argument("--max-files", type=int, default=512)
    parser.add_argument("--max-bytes", type=int, default=16 * 1024 * 1024)
    args = parser.parse_args()
    if args.max_files < 0 or args.max_bytes < 0:
        parser.error("corpus limits must be non-negative")

    # Preserve recent discoveries first; use a stable path order to break restored-cache timestamp ties.
    files = (
        sorted(
            (path for path in args.root.rglob("*") if path.is_file()),
            key=lambda path: (-path.stat().st_mtime_ns, path.as_posix()),
        )
        if args.root.exists()
        else []
    )
    retained = 0
    total_bytes = 0
    for path in files:
        size = path.stat().st_size
        if retained < args.max_files and total_bytes + size <= args.max_bytes:
            retained += 1
            total_bytes += size
        else:
            path.unlink()

    if args.root.exists():
        for directory in sorted((path for path in args.root.rglob("*") if path.is_dir()), reverse=True):
            try:
                directory.rmdir()
            except OSError:
                pass
    print(f"retained_files={retained} retained_bytes={total_bytes}")


if __name__ == "__main__":
    main()
