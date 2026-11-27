"""Exercise host and device result gates with incomplete evidence."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET
from PIL import Image, ImageDraw

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
        self.digests = 'a' * 64 + '  app/build/outputs/apk/prodWebsite/debug/app-prod-website-debug.apk\n' + 'b' * 64 + '  app/build/outputs/apk/androidTest/prodWebsite/debug/app-prod-website-debug-androidTest.apk\n'
        (self.evidence / 'apk-before.txt').write_text(self.digests)
        (self.evidence / 'apk-after.txt').write_text(self.digests)
        ET.ElementTree(ET.Element('manifest', package='im.collie.app')).write(self.evidence / 'app-manifest.xml')
        test_manifest = ET.Element('manifest', package='im.collie.app.test')
        ET.SubElement(test_manifest, 'instrumentation', {
            gate.ANDROID + 'name': 'org.thoughtcrime.securesms.testing.SignalTestRunner',
            gate.ANDROID + 'targetPackage': 'im.collie.app',
        })
        ET.ElementTree(test_manifest).write(self.evidence / 'test-manifest.xml')
        self.host = self.make_report(
            'feature/app-settings/build/test-results/testDebugUnitTest/TEST-host.xml',
            {gate.HOST_CLASS: gate.HOST_METHODS},
        )
        self.device = self.make_report('build/navigation-validation/results/navigation/device/TEST-device.xml', {
            gate.DEVICE_CLASS: gate.DEVICE_METHODS,
        })
        self.release_notes = self.make_report('build/navigation-validation/results/release-notes/device/TEST-device.xml', {
            gate.RELEASE_NOTES_CLASS: gate.RELEASE_NOTES_METHODS,
        })
        screenshots = self.evidence / 'screenshots'
        screenshots.mkdir()
        image = Image.new('RGB', (100, 100), 'white')
        ImageDraw.Draw(image).rectangle((10, 10, 60, 60), fill='black')
        for name in gate.SCREENSHOTS:
            image.save(screenshots / name)

    def make_report(self, path, cases_by_class):
        file = self.root / path
        file.parent.mkdir(parents=True)
        count = sum(len(methods) for methods in cases_by_class.values())
        suite = ET.Element('testsuite', tests=str(count), failures='0', errors='0', skipped='0')
        for classname, methods in cases_by_class.items():
            for method in sorted(methods):
                ET.SubElement(suite, 'testcase', classname=classname, name=method)
        ET.ElementTree(suite).write(file)
        return file

    def check(self):
        gate.check(self.root, 'success', 'success', 'success', 'd' * 40)

    def test_complete_host_and_device_pass(self):
        self.check()

    def test_missing_release_notes_report_fails(self):
        self.release_notes.unlink()
        with self.assertRaises(ValueError):
            self.check()

    def test_missing_skipped_and_duplicate_device_fail(self):
        original = self.device.read_bytes()
        for mutation in ('missing', 'skipped', 'duplicate', 'wrong class'):
            with self.subTest(mutation=mutation):
                tree = ET.parse(self.device)
                suite = tree.getroot()
                case = suite.find('testcase')
                if mutation == 'missing':
                    suite.remove(case)
                elif mutation == 'skipped':
                    ET.SubElement(case, 'skipped')
                elif mutation == 'duplicate':
                    suite.append(ET.fromstring(ET.tostring(case)))
                else:
                    case.set('classname', 'unexpected.Class')
                suite.set('tests', str(len(suite.findall('testcase'))))
                tree.write(self.device)
                with self.assertRaises(ValueError):
                    self.check()
                self.device.write_bytes(original)

    def test_missing_host_method_and_changed_apk_fail(self):
        original = self.host.read_bytes()
        tree = ET.parse(self.host)
        tree.getroot().remove(tree.getroot().find('testcase'))
        tree.write(self.host)
        with self.assertRaises(ValueError): self.check()
        self.host.write_bytes(original)
        (self.evidence / 'apk-after.txt').write_text(self.digests.replace('a' * 64, 'c' * 64))
        with self.assertRaisesRegex(ValueError, 'APK pair changed'): self.check()

    def test_wrong_source_identity_fails(self):
        (self.evidence / 'source.txt').write_text('f' * 40 + '\n' + 'e' * 40)
        with self.assertRaisesRegex(ValueError, 'source identity'): self.check()

    def test_failure_or_wrong_api_fails(self):
        with self.assertRaisesRegex(ValueError, 'step failed'): gate.check(self.root, 'failure', 'success', 'success', 'd' * 40)
        (self.evidence / 'device-api.txt').write_text('34')
        with self.assertRaisesRegex(ValueError, 'API 35'): self.check()

    def test_wrong_runner_or_missing_and_blank_screenshots_fail(self):
        test_manifest = self.evidence / 'test-manifest.xml'
        tree = ET.parse(test_manifest)
        tree.getroot().find('instrumentation').set(gate.ANDROID + 'name', 'wrong.Runner')
        tree.write(test_manifest)
        with self.assertRaisesRegex(ValueError, 'runner'): self.check()
        tree.getroot().find('instrumentation').set(gate.ANDROID + 'name', 'org.thoughtcrime.securesms.testing.SignalTestRunner')
        tree.write(test_manifest)

        screenshot = self.evidence / 'screenshots/settings-home.png'
        original = screenshot.read_bytes()
        screenshot.unlink()
        with self.assertRaisesRegex(ValueError, 'screenshots'): self.check()
        Image.new('RGB', (100, 100), 'white').save(screenshot)
        with self.assertRaisesRegex(ValueError, 'blank'): self.check()
        screenshot.write_bytes(original)


if __name__ == '__main__': unittest.main()
