import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

import test_prepare_sources as source_fixtures

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
spec = importlib.util.spec_from_file_location('build_sources', HERE / 'build_sources.py')
subject = importlib.util.module_from_spec(spec)
spec.loader.exec_module(subject)

class PartialSourceBuildTest(unittest.TestCase):
    def setUp(self):
        self.fixture = source_fixtures.SourcePreparationTest('runTest')
        self.fixture.setUp()
        self.addCleanup(self.fixture.tearDown)
        self.root = self.fixture.root
        self.sources = self.root / 'prepared'
        subject.prepare_sources.prepare(self.fixture.lock_file, self.fixture.sources, self.sources)
        (self.sources / 'libsignal/.git/info').mkdir(exist_ok=True)
        (self.sources / 'libsignal/.git/info/exclude').write_text('java/\n')
        self.sdk = self.root / 'sdk'
        ndk = self.sdk / 'ndk/28.0.13004108'
        ndk.mkdir(parents=True)
        (ndk / 'source.properties').write_text('Pkg.Revision = 28.0.13004108\n')
        self.java = self.root / 'jdk'
        (self.java / 'bin').mkdir(parents=True)
        (self.java / 'bin/java').write_text('fixture')
        self.rust_bin = self.root / 'rust-bin'
        self.rust_bin.mkdir()
        for name in ('cargo', 'rustc', 'rustup'):
            tool = self.rust_bin / name
            tool.write_text('fixture')
            tool.chmod(0o755)
        (self.java / 'bin/java').chmod(0o755)
        self.report = self.root / 'partial.json'
        self.calls = []
        self.lock = json.loads(self.fixture.lock_file.read_text())
        self.binaries = {'libsignal-android': b'fixture-aar', 'libsignal-client': b'fixture-jar'}
        for module in self.lock['modules']:
            if module['name'] in self.binaries:
                module['binary_sha256'] = hashlib.sha256(self.binaries[module['name']]).hexdigest()
        self.fixture.lock_file.write_text(json.dumps(self.lock))

    def runner(self, args, cwd, env):
        self.calls.append(args)
        self.assertEqual('1.98.1', env['RUSTUP_TOOLCHAIN'])
        if args[-1] == '-version':
            return ['openjdk version \"21.0.13\" 2026-01-01']
        if args[-1] == '--version':
            return ['rustc 1.98.1 (fixture)']
        if args[-3:] == ['target', 'list', '--installed']:
            return ['aarch64-linux-android', 'armv7-linux-androideabi', 'i686-linux-android', 'x86_64-linux-android']
        if 'test' in args:
            return ['test result: ok. 4 passed; 0 failed; 0 ignored']
        if args[0] == str(self.root / 'verified-gradle/bin/gradle'):
            self.assertEqual(str(self.sdk / 'ndk/28.0.13004108'), env['ANDROID_NDK_HOME'])
            self.assertEqual(str(self.sdk / 'ndk/28.0.13004108'), env['ANDROID_NDK_ROOT'])
            aar = self.sources / 'libsignal/java/android/build/outputs/aar'
            jar = self.sources / 'libsignal/java/client/build/libs'
            aar.mkdir(parents=True)
            jar.mkdir(parents=True)
            (aar / 'libsignal.aar').write_bytes(self.binaries['libsignal-android'])
            (jar / 'libsignal.jar').write_bytes(self.binaries['libsignal-client'])
            return []
        raise AssertionError('unexpected diagnostic command')

    def run_build(self):
        with patch.object(subject.verify_gradle, 'verify', return_value=self.root / 'verified-gradle/bin/gradle'):
            return subject.diagnostic_build(self.fixture.lock_file, self.sources, self.sdk, self.java, self.rust_bin, self.report, self.root / 'gradle.zip', self.root / 'verified-gradle', self.runner)

    def test_success_is_explicitly_partial_and_never_release_proof(self):
        receipt = self.run_build()
        self.assertEqual('PARTIAL_LIBSIGNAL_ONLY', receipt['status'])
        self.assertFalse(receipt['release_allowed'])
        self.assertTrue(any('configured_proxy_never_falls_back_to_direct' in command for command in self.calls))
        self.assertTrue(self.report.is_file())

    def test_mismatched_output_fails_without_receipt(self):
        self.binaries['libsignal-client'] = b'unexpected'
        with self.assertRaisesRegex(subject.Failure, 'reproducibility is unproved'):
            self.run_build()
        self.assertFalse(self.report.exists())

    def test_ringrtc_preflight_requires_requested_java_version(self):
        def runner(args, cwd, env):
            if args[-1] == '-version': return ['openjdk version \"25.0.4\"']
            return self.runner(args, cwd, env)
        subject.preflight(self.fixture.lock_file, self.sources, self.sdk, self.java, self.rust_bin, runner, java_major=25)
        with self.assertRaisesRegex(subject.Failure, 'Java 21'):
            subject.preflight(self.fixture.lock_file, self.sources, self.sdk, self.java, self.rust_bin, runner)

    def test_incorrect_ndk_fails_before_commands(self):
        (self.sdk / 'ndk/28.0.13004108/source.properties').write_text('Pkg.Revision = 29.0.0\n')
        with self.assertRaisesRegex(subject.Failure, 'NDK version'):
            self.run_build()
        self.assertFalse(self.report.exists())
        self.assertEqual([], self.calls)

if __name__ == '__main__':
    unittest.main()
