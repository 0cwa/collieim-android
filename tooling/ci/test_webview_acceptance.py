"""Check that incomplete WebView device evidence fails closed."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET
from PIL import Image, ImageDraw

script = Path(__file__).resolve().parents[2] / '.github/scripts/check-webview-acceptance.py'
spec = importlib.util.spec_from_file_location('webview_gate', script)
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)
SHA = 'd' * 40


class WebViewGateTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.evidence = self.root / 'build/webview-acceptance'
        self.evidence.mkdir(parents=True)
        for name, value in {
            'source.txt': SHA + '\n' + 'e' * 40, 'exit-code.txt': '0', 'device-api.txt': '35',
            'device-fingerprint.txt': 'fixture', 'webview-provider.txt': 'Current WebView package (name, version): (com.example.webview, 1.0)',
            'connectivity-before.txt': 'Active default network: none', 'connectivity-after.txt': 'Active default network: none'
        }.items(): (self.evidence / name).write_text(value)
        self.digests = 'a' * 64 + '  app/build/outputs/apk/prodWebsite/debug/app.apk\n' + 'b' * 64 + '  app/build/outputs/apk/androidTest/prodWebsite/debug/test.apk\n'
        (self.evidence / 'apk-before.txt').write_text(self.digests)
        (self.evidence / 'apk-after.txt').write_text(self.digests)
        app = ET.Element('manifest', package='im.collie.app')
        test = ET.Element('manifest', package='im.collie.app.test')
        ET.SubElement(test, 'instrumentation', {gate.ANDROID + 'name': 'org.thoughtcrime.securesms.testing.SignalTestRunner', gate.ANDROID + 'targetPackage': 'im.collie.app'})
        ET.ElementTree(app).write(self.evidence / 'app-manifest.xml')
        ET.ElementTree(test).write(self.evidence / 'test-manifest.xml')
        self.report = self.root / 'app/build/outputs/androidTest-results/device/TEST-webview.xml'
        self.report.parent.mkdir(parents=True)
        suite = ET.Element('testsuite', tests=str(len(gate.METHODS)), failures='0', errors='0', skipped='0')
        for name in sorted(gate.METHODS): ET.SubElement(suite, 'testcase', classname=gate.CLASS, name=name)
        ET.ElementTree(suite).write(self.report)
        screenshots = self.evidence / 'screenshots'
        screenshots.mkdir()
        image = Image.new('RGB', (100, 100), 'white')
        ImageDraw.Draw(image).rectangle((10, 10, 60, 60), fill='black')
        for name in gate.SCREENSHOTS: image.save(screenshots / name)

    def check(self): gate.check(self.root, 'success', 'success', SHA)

    def test_complete_evidence_passes(self): self.check()

    def test_missing_skipped_duplicate_and_failed_cases_rejected(self):
        original = self.report.read_bytes()
        for mutation in ('missing', 'skipped', 'duplicate', 'failure'):
            with self.subTest(mutation=mutation):
                self.report.write_bytes(original)
                tree = ET.parse(self.report)
                suite = tree.getroot()
                case = suite.find('testcase')
                if mutation == 'missing': suite.remove(case)
                elif mutation == 'duplicate': suite.append(ET.fromstring(ET.tostring(case)))
                else: ET.SubElement(case, mutation)
                tree.write(self.report)
                with self.assertRaises(ValueError): self.check()

    def test_wrong_runner_and_online_device_rejected(self):
        file = self.evidence / 'test-manifest.xml'
        original = file.read_bytes()
        tree = ET.parse(file)
        tree.getroot().find('instrumentation').set(gate.ANDROID + 'name', 'wrong.Runner')
        tree.write(file)
        with self.assertRaisesRegex(ValueError, 'runner'): self.check()
        file.write_bytes(original)
        (self.evidence / 'connectivity-before.txt').write_text('Active default network: 100')
        with self.assertRaisesRegex(ValueError, 'offline'): self.check()

    def test_changed_apk_or_source_rejected(self):
        (self.evidence / 'apk-after.txt').write_text(self.digests.replace('a' * 64, 'c' * 64))
        with self.assertRaisesRegex(ValueError, 'APK pair changed'): self.check()
        (self.evidence / 'apk-after.txt').write_text(self.digests)
        with self.assertRaisesRegex(ValueError, 'source identity'): gate.check(self.root, 'success', 'success', 'f' * 40)

    def test_missing_corrupt_and_blank_screenshot_rejected(self):
        path = self.evidence / 'screenshots/paypal.png'
        original = path.read_bytes()
        for mutation in ('missing', 'corrupt', 'blank'):
            with self.subTest(mutation=mutation):
                path.write_bytes(original)
                if mutation == 'missing': path.unlink()
                elif mutation == 'corrupt': path.write_bytes(original[:32])
                else: Image.new('RGB', (100, 100), 'white').save(path)
                with self.assertRaises((ValueError, OSError)): self.check()

    def test_no_provider_and_failed_device_rejected(self):
        (self.evidence / 'webview-provider.txt').write_text('Current WebView package: null')
        with self.assertRaisesRegex(ValueError, 'provider identity'): self.check()
        with self.assertRaisesRegex(ValueError, 'step failed'): gate.check(self.root, 'success', 'failure', SHA)


if __name__ == '__main__': unittest.main()
