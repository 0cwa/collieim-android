"""Exercise host and device result gates with incomplete evidence."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

script = Path(__file__).resolve().parents[2] / '.github/scripts/check-navigation-results.py'
spec = importlib.util.spec_from_file_location('navigation_gate', script)
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class NavigationGateTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.evidence = self.root / 'build/navigation-validation'
        self.evidence.mkdir(parents=True)
        for name, value in {'exit-code.txt': '0', 'device-api.txt': '35', 'device-fingerprint.txt': 'fixture', 'source.txt': 'd' * 40 + '\n' + 'e' * 40}.items():
            (self.evidence / name).write_text(value)
        self.digests = 'a' * 64 + '  app/build/outputs/apk/prodWebsite/debug/app.apk\n' + 'b' * 64 + '  app/build/outputs/apk/androidTest/prodWebsite/debug/test.apk\n'
        (self.evidence / 'apk-before.txt').write_text(self.digests)
        (self.evidence / 'apk-after.txt').write_text(self.digests)
        self.host = self.make_report('feature/app-settings/build/test-results/testDebugUnitTest/TEST-host.xml', gate.HOST_CLASS, gate.HOST_METHODS)
        self.device = self.make_report('app/build/outputs/androidTest-results/device/TEST-device.xml', gate.DEVICE_CLASS, gate.DEVICE_METHODS)

    def make_report(self, path, classname, methods):
        file = self.root / path
        file.parent.mkdir(parents=True)
        suite = ET.Element('testsuite', tests=str(len(methods)), failures='0', errors='0', skipped='0')
        for method in sorted(methods):
            ET.SubElement(suite, 'testcase', classname=classname, name=method)
        ET.ElementTree(suite).write(file)
        return file

    def check(self):
        gate.check(self.root, 'success', 'success', 'success', 'd' * 40)

    def test_complete_host_and_device_pass(self):
        self.check()

    def test_missing_skipped_and_duplicate_device_fail(self):
        for mutation in ('missing', 'skipped', 'duplicate'):
            with self.subTest(mutation=mutation):
                tree = ET.parse(self.device)
                suite = tree.getroot()
                case = suite.find('testcase')
                if mutation == 'missing': suite.remove(case)
                elif mutation == 'skipped': ET.SubElement(case, 'skipped')
                else: suite.append(ET.fromstring(ET.tostring(case)))
                tree.write(self.device)
                with self.assertRaises(ValueError): self.check()
                self.make_replacement(self.device, gate.DEVICE_CLASS, gate.DEVICE_METHODS)

    def make_replacement(self, file, classname, methods):
        suite = ET.Element('testsuite', tests=str(len(methods)), failures='0', errors='0', skipped='0')
        for method in sorted(methods): ET.SubElement(suite, 'testcase', classname=classname, name=method)
        ET.ElementTree(suite).write(file)

    def test_missing_host_method_and_changed_apk_fail(self):
        tree = ET.parse(self.host)
        tree.getroot().remove(tree.getroot().find('testcase'))
        tree.write(self.host)
        with self.assertRaises(ValueError): self.check()
        self.make_replacement(self.host, gate.HOST_CLASS, gate.HOST_METHODS)
        (self.evidence / 'apk-after.txt').write_text(self.digests.replace('a' * 64, 'c' * 64))
        with self.assertRaisesRegex(ValueError, 'APK pair changed'): self.check()

    def test_wrong_source_identity_fails(self):
        (self.evidence / 'source.txt').write_text('f' * 40 + '\n' + 'e' * 40)
        with self.assertRaisesRegex(ValueError, 'source identity'): self.check()

    def test_failure_or_wrong_api_fails(self):
        with self.assertRaisesRegex(ValueError, 'step failed'): gate.check(self.root, 'failure', 'success', 'success', 'd' * 40)
        (self.evidence / 'device-api.txt').write_text('34')
        with self.assertRaisesRegex(ValueError, 'API 35'): self.check()


if __name__ == '__main__': unittest.main()
