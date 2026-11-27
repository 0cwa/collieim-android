"""Unit tests for the API 35 UI smoke runner and evidence validator."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


runner = load('run_ui_smoke', HERE / 'run_ui_smoke.py')
validator = load('check_ui_smoke_results', HERE.parents[1] / '.github/scripts/check-ui-smoke-results.py')


def good_log(classname):
    return good_class_log(classname, ['case'])


def good_class_log(classname, methods):
    log = ''
    for method in methods:
        log += (f'INSTRUMENTATION_STATUS: class={classname}\nINSTRUMENTATION_STATUS: test={method}\n'
                'INSTRUMENTATION_STATUS_CODE: 1\n'
                f'INSTRUMENTATION_STATUS: class={classname}\nINSTRUMENTATION_STATUS: test={method}\n'
                'INSTRUMENTATION_STATUS_CODE: 0\n')
    return log + 'INSTRUMENTATION_CODE: -1\n'


class RunnerTests(unittest.TestCase):
    def test_one_pass_is_required_and_counted(self):
        name = 'sample.Test'
        self.assertEqual(runner.parse_instrumentation(good_log(name), name), ['case'])

    def test_zero_tests_fails(self):
        with self.assertRaises(ValueError):
            runner.parse_instrumentation('INSTRUMENTATION_CODE: -1\n', 'sample.Test')

    def test_failure_skip_crash_and_wrong_class_fail(self):
        name = 'sample.Test'
        for raw in (
            good_log(name).replace('STATUS_CODE: 0', 'STATUS_CODE: -2'),
            good_log(name).replace('STATUS_CODE: 0', 'STATUS_CODE: -3'),
            good_log(name).replace('INSTRUMENTATION_CODE: -1', 'INSTRUMENTATION_FAILED: crashed'),
            good_log(name).replace('class=sample.Test', 'class=sample.Other'),
        ):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                runner.parse_instrumentation(raw, name)

    @mock.patch.object(runner.subprocess, 'run')
    def test_adb_command_uses_requested_serial(self, subprocess_run):
        runner.run(['adb', 'shell', 'getprop'], serial='emulator-5560')
        self.assertEqual(subprocess_run.call_args.args[0], ['adb', '-s', 'emulator-5560', 'shell', 'getprop'])

    def test_execute_reads_both_manifests_and_records_clean_subsets(self):
        manifest = json.loads(runner.MANIFEST.read_text())
        selections = (
            ('stable', 'org.thoughtcrime.securesms.apkupdate.CollieUpdateSettingsTest'),
            ('beta', 'org.thoughtcrime.securesms.components.settings.app.StoriesSettingsNavigationTest'),
        )
        for edition, classname in selections:
            config = manifest['editions'][edition]
            methods = config['methods'][classname]
            with tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                app_apk = root / 'app.apk'
                test_apk = root / 'test.apk'
                app_apk.write_bytes(b'app')
                test_apk.write_bytes(b'test')
                output = root / 'evidence'
                calls = []

                def fake_run(argv, **kwargs):
                    calls.append(argv)
                    if argv[:2] == ['git', 'rev-parse']:
                        value = 'a' * 40 if argv[-1] == 'HEAD' else 'b' * 40
                        return runner.subprocess.CompletedProcess(argv, 0, value + '\n')
                    if argv[:4] == ['adb', 'shell', 'getprop', 'ro.build.version.sdk']:
                        return runner.subprocess.CompletedProcess(argv, 0, '35\n')
                    if argv[:4] == ['adb', 'shell', 'getprop', 'ro.build.fingerprint']:
                        return runner.subprocess.CompletedProcess(argv, 0, 'google/sdk_gphone64_x86_64\n')
                    if 'am' in argv and 'instrument' in argv:
                        return runner.subprocess.CompletedProcess(argv, 0, good_class_log(classname, methods))
                    return runner.subprocess.CompletedProcess(argv, 0, '')

                def fake_manifest(apk, destination):
                    destination.write_text('<manifest/>')

                args = runner.argparse.Namespace(
                    edition=edition, expected_api_level=None, classes=[classname], output_dir=output,
                    app_apk=app_apk, test_apk=test_apk, serial='emulator-5560', skip_install=False)
                with mock.patch.object(runner, 'run', side_effect=fake_run), \
                        mock.patch.object(runner, 'manifest_xml', side_effect=fake_manifest):
                    self.assertEqual(runner.execute(args), 0)

                instrumentation = next(call for call in calls if 'instrument' in call)
                expected_runner = f'{config["test_application_id"]}/{config["runner"]}'
                self.assertIn(expected_runner, instrumentation)
                if edition == 'stable':
                    self.assertIn('collieTestStopLauncher', instrumentation)
                else:
                    self.assertNotIn('collieTestStopLauncher', instrumentation)
                self.assertEqual(json.loads((output / 'classes.json').read_text()), [classname])
                summary = json.loads((output / 'summary.json').read_text())
                self.assertEqual(summary[0]['passed'], len(methods))
                self.assertEqual((output / 'exit-code.txt').read_text(), '0\n')

    def test_execute_records_data_clear_failure_as_nonzero_evidence(self):
        manifest = json.loads(runner.MANIFEST.read_text())
        config = manifest['editions']['stable']
        classname = config['classes'][0]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            app_apk = root / 'app.apk'
            test_apk = root / 'test.apk'
            app_apk.write_bytes(b'app')
            test_apk.write_bytes(b'test')
            output = root / 'evidence'

            def fake_run(argv, **kwargs):
                if argv[:2] == ['git', 'rev-parse']:
                    value = 'a' * 40 if argv[-1] == 'HEAD' else 'b' * 40
                    return runner.subprocess.CompletedProcess(argv, 0, value + '\n')
                if argv[:4] == ['adb', 'shell', 'getprop', 'ro.build.version.sdk']:
                    return runner.subprocess.CompletedProcess(argv, 0, '35\n')
                if argv[:4] == ['adb', 'shell', 'getprop', 'ro.build.fingerprint']:
                    return runner.subprocess.CompletedProcess(argv, 0, 'google/sdk_gphone64_x86_64\n')
                if 'pm' in argv and 'clear' in argv:
                    return runner.subprocess.CompletedProcess(argv, 1, 'device disconnected\n')
                return runner.subprocess.CompletedProcess(argv, 0, '')

            args = runner.argparse.Namespace(
                edition='stable', expected_api_level=None, classes=[classname], output_dir=output,
                app_apk=app_apk, test_apk=test_apk, serial='emulator-5560', skip_install=False)
            with mock.patch.object(runner, 'run', side_effect=fake_run), \
                    mock.patch.object(runner, 'manifest_xml', side_effect=lambda apk, dest: dest.write_text('<manifest/>')):
                with contextlib.redirect_stderr(io.StringIO()):
                    with self.assertRaises(ValueError):
                        runner.execute(args)
            self.assertEqual((output / 'exit-code.txt').read_text(), '1\n')
            self.assertIn('pm clear exited 1', json.loads((output / 'summary.json').read_text())[0]['error'])


class ValidatorTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.config = json.loads(validator.MANIFEST.read_text())['editions']['stable']
        self.expected_sha = 'a' * 40
        (self.root / 'source.txt').write_text(self.expected_sha + '\n' + 'b' * 40 + '\n')
        (self.root / 'exit-code.txt').write_text('0\n')
        (self.root / 'device-api.txt').write_text('35\n')
        (self.root / 'device-fingerprint.txt').write_text('test/device\n')
        (self.root / 'animations.json').write_text(json.dumps({
            'original': {'window_animation_scale': '1', 'transition_animation_scale': '1', 'animator_duration_scale': '1'},
            'applied_during_tests': {'window_animation_scale': '0', 'transition_animation_scale': '0', 'animator_duration_scale': '0'},
        }))
        (self.root / 'app-manifest.xml').write_text(f'<manifest package="{self.config["application_id"]}"/>')
        (self.root / 'test-manifest.xml').write_text(
            f'<manifest package="{self.config["test_application_id"]}"><instrumentation '
            f'android:name="{self.config["runner"]}" android:targetPackage="{self.config["application_id"]}" '
            'xmlns:android="http://schemas.android.com/apk/res/android"/></manifest>')
        hashes = f'{'1' * 64}  {self.config["app_apk"]}\n{'2' * 64}  {self.config["test_apk"]}\n'
        (self.root / 'apk-before.txt').write_text(hashes)
        (self.root / 'apk-after.txt').write_text(hashes)
        (self.root / 'classes.json').write_text(json.dumps(self.config['classes']))
        summary = []
        for classname in self.config['classes']:
            methods = self.config['methods'][classname]
            (self.root / (classname.replace('.', '_') + '.log')).write_text(good_class_log(classname, methods))
            summary.append({'class': classname, 'passed': len(methods), 'tests': methods, 'seconds': 0.1})
        (self.root / 'summary.json').write_text(json.dumps(summary))

    def tearDown(self):
        self.temp.cleanup()

    def check(self):
        validator.check(self.root, 'stable', self.expected_sha)

    def test_happy_path_stable_and_beta(self):
        self.check()
        beta = json.loads(validator.MANIFEST.read_text())['editions']['beta']
        # A beta fixture proves the identity validator selects the other manifest entry.
        (self.root / 'app-manifest.xml').write_text(f'<manifest package="{beta["application_id"]}"/>')
        (self.root / 'test-manifest.xml').write_text(
            f'<manifest package="{beta["test_application_id"]}"><instrumentation '
            f'android:name="{beta["runner"]}" android:targetPackage="{beta["application_id"]}" '
            'xmlns:android="http://schemas.android.com/apk/res/android"/></manifest>')
        (self.root / 'apk-before.txt').write_text(f'{'1' * 64}  {beta["app_apk"]}\n{'2' * 64}  {beta["test_apk"]}\n')
        (self.root / 'apk-after.txt').write_text((self.root / 'apk-before.txt').read_text())
        (self.root / 'classes.json').write_text(json.dumps(beta['classes']))
        (self.root / 'summary.json').write_text(json.dumps([
            {'class': name, 'passed': len(beta['methods'][name]), 'tests': beta['methods'][name], 'seconds': 0.1}
            for name in beta['classes']]))
        for name in beta['classes']:
            (self.root / (name.replace('.', '_') + '.log')).write_text(good_class_log(name, beta['methods'][name]))
        validator.check(self.root, 'beta', self.expected_sha)

    def test_rejects_missing_class_skipped_failed_zero_tests_and_crash(self):
        original = json.loads((self.root / 'summary.json').read_text())
        cases = [original[:-1], [dict(original[0], passed=0)], [dict(original[0], passed=1, error='skip')],
                 [dict(original[0], tests=['wrong-method'])]]
        for summary in cases:
            (self.root / 'summary.json').write_text(json.dumps(summary))
            with self.assertRaises(ValueError): self.check()
        classname = self.config['classes'][0]
        log = self.root / (classname.replace('.', '_') + '.log')
        good = log.read_text()
        for bad in (good.replace('STATUS_CODE: 0', 'STATUS_CODE: -3'),
                    good.replace('STATUS_CODE: 0', 'STATUS_CODE: -2'),
                    good + 'INSTRUMENTATION_RESULT: shortMsg=crash\n'):
            log.write_text(bad)
            (self.root / 'summary.json').write_text(json.dumps(original))
            with self.assertRaises(ValueError): self.check()
        log.write_text(good)

    def test_rejects_wrong_identity_hash_and_api(self):
        (self.root / 'device-api.txt').write_text('34\n')
        with self.assertRaises(ValueError): self.check()
        (self.root / 'device-api.txt').write_text('35\n')
        (self.root / 'test-manifest.xml').write_text('<manifest package="wrong"/>')
        with self.assertRaises(ValueError): self.check()
        (self.root / 'test-manifest.xml').write_text(
            f'<manifest package="{self.config["test_application_id"]}"><instrumentation '
            f'android:name="wrong.Runner" android:targetPackage="{self.config["application_id"]}" '
            'xmlns:android="http://schemas.android.com/apk/res/android"/></manifest>')
        with self.assertRaises(ValueError): self.check()
        (self.root / 'test-manifest.xml').write_text(
            f'<manifest package="{self.config["test_application_id"]}"><instrumentation '
            f'android:name="{self.config["runner"]}" android:targetPackage="{self.config["application_id"]}" '
            'xmlns:android="http://schemas.android.com/apk/res/android"/></manifest>')
        (self.root / 'source.txt').write_text('c' * 40 + '\n' + 'b' * 40 + '\n')
        with self.assertRaises(ValueError): self.check()
        (self.root / 'source.txt').write_text(self.expected_sha + '\n' + 'b' * 40 + '\n')
        (self.root / 'apk-after.txt').write_text((self.root / 'apk-after.txt').read_text().replace('1' * 64, '3' * 64))
        with self.assertRaises(ValueError): self.check()

    def test_rejects_animation_scales_left_enabled_during_tests(self):
        (self.root / 'animations.json').write_text(json.dumps({
            'original': {'window_animation_scale': '1'},
            'applied_during_tests': {'window_animation_scale': '1'},
        }))
        with self.assertRaises(ValueError):
            self.check()


if __name__ == '__main__':
    unittest.main()
