#!/usr/bin/env python3
"""Capture bounded logs only from the disposable, synthetic CI emulator.

Never use this on an emulator containing real accounts or production secrets.
"""
import collections
import signal
import subprocess
import sys
import threading
import time
from pathlib import Path

LIMIT = 5 * 1024 * 1024
stop = threading.Event()


def capture_logcat(path):
    chunks = collections.deque()
    size = 0
    try:
        proc = subprocess.Popen(['adb', 'logcat', '-b', 'all', '-v', 'threadtime'], stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        def terminate():
            stop.wait()
            if proc.poll() is None:
                proc.terminate()
        threading.Thread(target=terminate, daemon=True).start()
        while data := proc.stdout.read1(4096):
            chunks.append(data)
            size += len(data)
            while size > LIMIT:
                excess = size - LIMIT
                first = chunks.popleft()
                size -= len(first)
                if len(first) > excess:
                    chunks.appendleft(first[excess:])
                    size += len(first) - excess
        proc.wait()
        with path.open('wb') as output:
            output.writelines(chunks)
    except OSError as exc:
        path.write_text(f'logcat capture failed: {exc}\n')


def main():
    directory = Path(sys.argv[1])
    directory.mkdir(parents=True, exist_ok=True)
    signal.signal(signal.SIGTERM, lambda *_: stop.set())
    signal.signal(signal.SIGINT, lambda *_: stop.set())
    thread = threading.Thread(target=capture_logcat, args=(directory / 'system-logcat.txt',))
    thread.start()
    with (directory / 'device-liveness.txt').open('w') as output:
        while not stop.is_set():
            output.write(time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()) + '\n')
            for command in (['adb', 'devices', '-l'], ['adb', 'get-state']):
                try:
                    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=5)
                    output.write(f'{" ".join(command)} exit={result.returncode}\n{result.stdout[:4096]}\n')
                except (OSError, subprocess.TimeoutExpired) as exc:
                    output.write(f'{" ".join(command)} failed: {exc}\n')
            output.flush()
            # A 360-minute job produces at most 1,441 bounded samples.
            if output.tell() >= LIMIT:
                output.truncate(LIMIT)
                stop.wait()
                break
            stop.wait(15)
    stop.set()
    thread.join(timeout=10)


if __name__ == '__main__':
    main()
