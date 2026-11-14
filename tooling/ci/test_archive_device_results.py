"""Exercise both device diagnostic gates and runner identity checks."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

path = Path(__file__).resolve().parents[2] / '.github/scripts/check-archive-device-results.py'
spec = importlib.util.spec_from_file_location('device_gate', path)
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)
SHA = 'd' * 40


class DeviceGateTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.evidence = self.root / 'build/archive-diagnostic'
        self.evidence.mkdir(parents=True)
        for name, value in {'exit-code.txt': '0', 'device-api.txt': '35', 'device-fingerprint.txt': 'fixture', 'source.txt': SHA + '\n' + 'e' * 40}.items():
            (self.evidence / name).write_text(value)
        self.digests = 'a' * 64 + '  app/build/outputs/apk/prodWebsite/debug/app.apk\n' + 'b' * 64 + '  app/build/outputs/apk/androidTest/prodWebsite/debug/test.apk\n'
        (self.evidence / 'apk-before.txt').write_text(self.digests)
        (self.evidence / 'apk-after.txt').write_text(self.digests)
        self.report = self.root / 'app/build/outputs/androidTest-results/device/TEST-test.xml'
        self.report.parent.mkdir(parents=True)

    def setup_suite(self, suite):
        expected = gate.SUITES[suite]
        (self.evidence / 'configuration.txt').write_text(suite + '\n' + expected['property'] + '\n')
        app = ET.Element('manifest', package=expected['package'])
        test = ET.Element('manifest', package=expected['package'] + '.test')
        ET.SubElement(test, 'instrumentation', {gate.ANDROID + 'name': expected['runner'], gate.ANDROID + 'targetPackage': expected['package']})
        ET.ElementTree(app).write(self.evidence / 'app-manifest.xml')
        ET.ElementTree(test).write(self.evidence / 'test-manifest.xml')
        cases = ET.Element('testsuite', tests=str(len(expected['methods'])), failures='0', errors='0', skipped='0')
        for name in sorted(expected['methods']):
            ET.SubElement(cases, 'testcase', classname=expected['class'], name=name)
        ET.ElementTree(cases).write(self.report)

    def check(self, suite):
        gate.check(self.root, 'success', 'success', suite, SHA)

    def test_both_complete_suites_pass(self):
        for suite in gate.SUITES:
            with self.subTest(suite=suite):
                self.setup_suite(suite)
                self.check(suite)

    def test_imo_methods_may_be_in_separate_reports(self):
        self.setup_suite('imo')
        tree = ET.parse(self.report)
        suite = tree.getroot()
        second = suite.findall('testcase')[-1]
        suite.remove(second)
        suite.set('tests', '1')
        tree.write(self.report)
        other = ET.Element('testsuite', tests='1', failures='0', errors='0', skipped='0')
        other.append(second)
        ET.ElementTree(other).write(self.report.with_name('TEST-second.xml'))
        self.check('imo')

    def test_imo_missing_skipped_failed_and_duplicate_methods_fail(self):
        for mutation in ('missing', 'skipped', 'failure', 'duplicate'):
            with self.subTest(mutation=mutation):
                self.setup_suite('imo')
                tree = ET.parse(self.report)
                root = tree.getroot()
                case = root.find('testcase')
                if mutation == 'missing': root.remove(case)
                elif mutation == 'duplicate': root.append(ET.fromstring(ET.tostring(case)))
                else: ET.SubElement(case, mutation)
                tree.write(self.report)
                with self.assertRaises(ValueError): self.check('imo')

    def test_archive_wrong_or_empty_case_fails(self):
        for mutation in ('wrong', 'empty'):
            with self.subTest(mutation=mutation):
                self.setup_suite('archive')
                tree = ET.parse(self.report)
                case = tree.getroot().find('testcase')
                if mutation == 'wrong': case.set('name', 'shard0')
                else: tree.getroot().remove(case)
                tree.write(self.report)
                with self.assertRaises(ValueError): self.check('archive')

    def test_zero_declared_imo_cases_fail(self):
        self.setup_suite('imo')
        tree = ET.parse(self.report)
        tree.getroot().set('tests', '0')
        tree.write(self.report)
        with self.assertRaises(ValueError): self.check('imo')

    def test_imo_wrong_runner_target_or_package_fails(self):
        for change in ('runner', 'target', 'package', 'property'):
            with self.subTest(change=change):
                self.setup_suite('imo')
                if change == 'property':
                    (self.evidence / 'configuration.txt').write_text('imo\n(none)\n')
                else:
                    file = self.evidence / ('app-manifest.xml' if change == 'package' else 'test-manifest.xml')
                    tree = ET.parse(file)
                    if change == 'package': tree.getroot().set('package', 'im.collie.app')
                    else: tree.getroot().find('instrumentation').set(gate.ANDROID + ('name' if change == 'runner' else 'targetPackage'), 'wrong')
                    tree.write(file)
                with self.assertRaises(ValueError): self.check('imo')

    def test_changed_apk_bad_source_and_failed_step_fail(self):
        self.setup_suite('imo')
        (self.evidence / 'apk-after.txt').write_text(self.digests.replace('a' * 64, 'c' * 64))
        with self.assertRaisesRegex(ValueError, 'APK pair changed'): self.check('imo')
        (self.evidence / 'apk-after.txt').write_text(self.digests)
        with self.assertRaisesRegex(ValueError, 'source identity'): gate.check(self.root, 'success', 'success', 'imo', 'f' * 40)
        with self.assertRaisesRegex(ValueError, 'step failed'): gate.check(self.root, 'success', 'failure', 'imo', SHA)
        (self.evidence / 'exit-code.txt').write_text('1')
        with self.assertRaisesRegex(ValueError, 'command failed'): self.check('imo')


if __name__ == '__main__': unittest.main()
