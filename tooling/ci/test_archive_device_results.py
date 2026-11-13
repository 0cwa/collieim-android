"""Exercise archive diagnostic evidence acceptance and failure handling."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

path = Path(__file__).resolve().parents[2] / '.github/scripts/check-archive-device-results.py'
spec = importlib.util.spec_from_file_location('archive_gate', path)
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class ArchiveGateTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.evidence = self.root / 'build/archive-diagnostic'
        self.evidence.mkdir(parents=True)
        for name, value in {'exit-code.txt': '0', 'device-api.txt': '35', 'device-fingerprint.txt': 'fixture'}.items():
            (self.evidence / name).write_text(value)
        self.digests = 'a' * 64 + '  app/build/outputs/apk/prodWebsite/debug/app.apk\n' + 'b' * 64 + '  app/build/outputs/apk/androidTest/prodWebsite/debug/test.apk\n'
        (self.evidence / 'apk-before.txt').write_text(self.digests)
        (self.evidence / 'apk-after.txt').write_text(self.digests)
        self.report = self.root / 'app/build/outputs/androidTest-results/device/TEST-archive.xml'
        self.report.parent.mkdir(parents=True)
        self.suite = ET.Element('testsuite', tests='1', failures='0', errors='0', skipped='0')
        ET.SubElement(self.suite, 'testcase', classname=gate.CLASS, name=gate.METHOD)
        self.save()

    def save(self):
        ET.ElementTree(self.suite).write(self.report)

    def test_passing_selected_shard(self):
        gate.check(self.root, 'success', 'success')

    def test_missing_wrong_duplicate_and_skipped_case_fail(self):
        case = self.suite.find('testcase')
        for kind in ('missing', 'wrong', 'duplicate', 'skipped'):
            with self.subTest(kind=kind):
                if kind == 'missing': self.suite.remove(case)
                elif kind == 'wrong': case.set('name', 'shard0')
                elif kind == 'duplicate': self.suite.append(ET.fromstring(ET.tostring(case)))
                else: ET.SubElement(case, 'skipped')
                self.save()
                with self.assertRaises(ValueError): gate.check(self.root, 'success', 'success')
                if kind == 'missing': self.suite.append(case)
                elif kind == 'wrong': case.set('name', gate.METHOD)
                elif kind == 'duplicate': self.suite.remove(self.suite.findall('testcase')[-1])
                else: case.remove(case.find('skipped'))

    def test_zero_declared_tests_fail(self):
        self.suite.set('tests', '0')
        self.save()
        with self.assertRaisesRegex(ValueError, 'one declared test'): gate.check(self.root, 'success', 'success')

    def test_changed_apk_failed_step_and_exit_fail(self):
        (self.evidence / 'apk-after.txt').write_text(self.digests.replace('a' * 64, 'c' * 64))
        with self.assertRaisesRegex(ValueError, 'APK pair changed'): gate.check(self.root, 'success', 'success')
        (self.evidence / 'apk-after.txt').write_text(self.digests)
        with self.assertRaisesRegex(ValueError, 'step failed'): gate.check(self.root, 'success', 'failure')
        (self.evidence / 'exit-code.txt').write_text('1')
        with self.assertRaisesRegex(ValueError, 'command failed'): gate.check(self.root, 'success', 'success')


if __name__ == '__main__': unittest.main()
