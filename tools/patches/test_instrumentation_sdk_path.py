"""Exercise the actual CI emulator step when adb is not on the host PATH."""
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ('molly-patch-release.yml', 'molly-unsigned-release.yml')


def workflow_step(text, name):
    section = text.split('      - name: ' + name + '\n', 1)[1]
    script = section.split('        run: |\n', 1)[1].split('\n      - name:', 1)[0]
    return textwrap.dedent(script).replace('${{ matrix.profile }}', 'collie')


class EmulatorPathTest(unittest.TestCase):
    def test_ci_uses_sdk_platform_tools_when_host_has_no_adb(self):
        for filename in WORKFLOWS:
            with self.subTest(workflow=filename), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                sdk = root / 'sdk'
                bin_dir = root / 'bin'
                bin_dir.mkdir()
                for name in ('bash', 'timeout', 'tr', 'sleep', 'yes'):
                    (bin_dir / name).symlink_to('/usr/bin/' + name)
                for name in ('sudo', 'python3'):
                    path = bin_dir / name
                    path.write_text('#!/bin/bash\nexit 0\n')
                    path.chmod(0o755)
                for relative, body in (
                    ('cmdline-tools/latest/bin/avdmanager', 'exit 0'),
                    ('cmdline-tools/latest/bin/sdkmanager', 'printf \"%s\\n\" \"$*\" >> \"$RUNNER_TEMP/sdkmanager.log\"'),
                    ('emulator/emulator', 'exit 0'),
                    ('platform-tools/adb', 'if [[ "$*" == *getprop* ]]; then echo 1; fi; exit 0'),
                ):
                    path = sdk / relative
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text('#!/bin/bash\n' + body + '\n')
                    path.chmod(0o755)
                gate = root / 'tools/patches/instrumentation_gate.sh'
                gate.parent.mkdir(parents=True)
                gate.write_text('#!/bin/bash\ncommand -v adb >/dev/null\n')
                gate.chmod(0o755)
                workflow = (ROOT / '.github/workflows' / filename).read_text()
                install = workflow_step(workflow, 'Install Android SDK')
                env = {'PATH': str(bin_dir), 'ANDROID_HOME': str(sdk), 'HOME': temp, 'RUNNER_TEMP': temp}
                installed = subprocess.run(['/usr/bin/bash', '-e', '-o', 'pipefail', '-c', install], cwd=root,
                                           env=env, text=True, capture_output=True, timeout=10)
                self.assertEqual(installed.returncode, 0, installed.stderr)
                self.assertIn('platform-tools', (root / 'sdkmanager.log').read_text())
                step = workflow_step(workflow, 'Run default and IMO instrumentation on a disposable emulator')
                result = subprocess.run(['/usr/bin/bash', '-e', '-o', 'pipefail', '-c', step], cwd=root,
                                        env=env, text=True, capture_output=True, timeout=10)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == '__main__':
    unittest.main()
