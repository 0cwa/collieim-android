import os
from pathlib import Path
import signal
import subprocess
import tempfile
import time
import unittest

SCRIPT = Path(__file__).with_name('instrumentation_diagnostics.py')


class DiagnosticsTest(unittest.TestCase):
    def test_captures_logs_and_liveness_on_shutdown(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            adb = root / 'adb'
            adb.write_text('#!/bin/bash\nif [[ "$1" == logcat ]]; then echo synthetic-system-log; exec sleep 30; else echo device; fi\n')
            adb.chmod(0o755)
            env = dict(os.environ, PATH=str(root) + ':' + os.environ['PATH'])
            proc = subprocess.Popen(['python3', str(SCRIPT), str(root / 'logs')], env=env)
            try:
                deadline = time.monotonic() + 5
                while time.monotonic() < deadline:
                    path = root / 'logs/device-liveness.txt'
                    if path.exists() and 'get-state' in path.read_text():
                        break
                    time.sleep(.05)
                else:
                    self.fail('liveness sample missing')
                proc.send_signal(signal.SIGTERM)
                self.assertEqual(proc.wait(timeout=10), 0)
                self.assertIn('synthetic-system-log', (root / 'logs/system-logcat.txt').read_text())
                self.assertIn('adb devices -l', path.read_text())
            finally:
                if proc.poll() is None:
                    proc.kill()
                    proc.wait()
