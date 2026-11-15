import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest
import zlib


SCRIPT = Path(__file__).with_name("check-project-log-proof.py")
SPEC = importlib.util.spec_from_file_location("project_log_proof_checker", SCRIPT)
CHECKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER)


def png(width=80, height=80, blank=False):
    signature = b"\x89PNG\r\n\x1a\n"

    def chunk(kind, payload):
        crc = zlib.crc32(kind + payload) & 0xFFFFFFFF
        return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", crc)

    pixels = bytearray()
    for y in range(height):
        pixels.append(0)
        for x in range(width):
            color = (0, 0, 0) if blank else (x * 3 % 256, y * 3 % 256, (x + y) * 2 % 256)
            pixels.extend((*color, 255))
    header = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    return signature + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(pixels)) + chunk(b"IEND", b"")


def evidence(root):
    proof = root / "build/project-log-proof"
    screenshots = proof / "screenshots"
    screenshots.mkdir(parents=True)
    sha = "a" * 40
    (proof / "source.txt").write_text(f"{sha}\n{'b' * 40}\n")
    (proof / "exit-code.txt").write_text("0\n")
    (proof / "device-api.txt").write_text("35\n")
    (proof / "device-fingerprint.txt").write_text("google/sdk_gphone64_x86_64/emu\n")
    (proof / "app-manifest.xml").write_text('<manifest package="im.collie.app" />')
    (proof / "test-manifest.xml").write_text(
        '<manifest package="im.collie.app.test"><instrumentation android:name="org.thoughtcrime.securesms.testing.SignalTestRunner" android:targetPackage="im.collie.app" xmlns:android="http://schemas.android.com/apk/res/android" /></manifest>'
    )
    app = "app/build/outputs/apk/prodWebsite/debug/im.collie.app-prodWebsite-debug.apk"
    test = "app/build/outputs/apk/androidTest/prodWebsite/debug/im.collie.app-prodWebsite-debug-androidTest.apk"
    digests = f"{'1' * 64}  {app}\n{'2' * 64}  {test}\n"
    (proof / "apk-before.txt").write_text(digests)
    (proof / "apk-after.txt").write_text(digests)
    names = sorted(CHECKER.METHODS)
    cases = "".join(f'<testcase classname="{CHECKER.CLASS}" name="{name}" />' for name in names)
    results = root / "app/build/outputs/androidTest-results/connected/prodWebsiteDebugAndroidTest"
    results.mkdir(parents=True)
    (results / "TEST-project-log.xml").write_text(f'<testsuite tests="3" failures="0" errors="0" skipped="0">{cases}</testsuite>')
    for name in CHECKER.SCREENSHOTS:
        (screenshots / name).write_bytes(png())
    (screenshots / "browser-handoff.txt").write_text(
        f"url={CHECKER.TARGET_URL}\nbrowser=com.android.chrome\nrendered_title={CHECKER.TARGET_TITLE}\nrendered_pr=#156\naddress=github.com/0cwa/collieim-android/pull/156\n"
    )
    return sha, proof, screenshots, results


class ProjectLogProofCheckerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.sha, self.proof, self.screenshots, self.results = evidence(self.root)

    def tearDown(self):
        self.temp.cleanup()

    def check(self):
        CHECKER.check(self.root, "success", "success", self.sha)

    def test_accepts_complete_api35_proof(self):
        self.check()

    def test_accepts_https_browser_address(self):
        handoff = self.screenshots / "browser-handoff.txt"
        handoff.write_text(handoff.read_text().replace("address=github.com/", "address=https://github.com/"))
        self.check()

    def test_rejects_device_or_build_failure(self):
        with self.assertRaisesRegex(ValueError, "device step failed"):
            CHECKER.check(self.root, "success", "failure", self.sha)

    def test_rejects_wrong_source_sha(self):
        with self.assertRaisesRegex(ValueError, "source SHA"):
            CHECKER.check(self.root, "success", "success", "c" * 40)

    def test_rejects_wrong_api_level(self):
        (self.proof / "device-api.txt").write_text("34\n")
        with self.assertRaisesRegex(ValueError, "API 35"):
            self.check()

    def test_rejects_apk_drift(self):
        (self.proof / "apk-after.txt").write_text((self.proof / "apk-after.txt").read_text().replace("2" * 64, "3" * 64))
        with self.assertRaisesRegex(ValueError, "changed during"):
            self.check()

    def test_rejects_missing_or_unexpected_test_method(self):
        report = next(self.results.glob("TEST-*.xml"))
        report.write_text(report.read_text().replace("projectLogDisclosureOpensPr156InTheExternalBrowser", "unexpectedMethod"))
        with self.assertRaisesRegex(ValueError, "exactly three"):
            self.check()

    def test_rejects_skipped_test(self):
        report = next(self.results.glob("TEST-*.xml"))
        report.write_text(report.read_text().replace('name="whatsNewShowsThisUpdateAndTheProjectLog" />', 'name="whatsNewShowsThisUpdateAndTheProjectLog"><skipped /></testcase>'))
        with self.assertRaisesRegex(ValueError, "skip"):
            self.check()

    def test_rejects_blank_screenshot(self):
        (self.screenshots / "07-light-pr156-browser.png").write_bytes(png(blank=True))
        with self.assertRaisesRegex(ValueError, "blank screenshot"):
            self.check()

    def test_rejects_corrupt_screenshot(self):
        (self.screenshots / "07-light-pr156-browser.png").write_bytes(b"not a png")
        with self.assertRaisesRegex(ValueError, "not a PNG"):
            self.check()

    def test_rejects_browser_page_without_expected_url(self):
        handoff = self.screenshots / "browser-handoff.txt"
        handoff.write_text(handoff.read_text().replace("/pull/156", "/pull/150"))
        with self.assertRaisesRegex(ValueError, "did not show the PR156 URL"):
            self.check()

    def test_rejects_browser_page_from_another_repository(self):
        handoff = self.screenshots / "browser-handoff.txt"
        original = handoff.read_text()
        for repository in ("another-owner/collieim-android", "0cwa/another-repository"):
            with self.subTest(repository=repository):
                handoff.write_text(original.replace("address=github.com/0cwa/collieim-android/", f"address=github.com/{repository}/"))
                with self.assertRaisesRegex(ValueError, "did not show the PR156 URL"):
                    self.check()


if __name__ == "__main__":
    unittest.main()
