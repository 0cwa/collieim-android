"""Offline checks that the security validation gates fail when evidence is incomplete."""

from contextlib import redirect_stdout
import importlib.util
import io
from pathlib import Path
import subprocess
import struct
import tempfile
import unittest
import xml.etree.ElementTree as ET

from PIL import Image


SCRIPTS = Path(__file__).resolve().parents[2] / ".github/scripts"


def load_script(name):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


results = load_script("check-unit-test-results")
source = load_script("verify-security-stack-source")
device = load_script("check-security-device-results")


class TestResultGateTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.reports = []
        for task, names in results.EXPECTED_CLASSES.items():
            *module, task_name = task.split(":")
            directory = self.root.joinpath(*module, "build", "test-results", task_name)
            directory.mkdir(parents=True)
            for name in names:
                path = directory / f"TEST-{name}.xml"
                suite = ET.Element("testsuite", name=name, tests="1", failures="0", errors="0", skipped="0")
                ET.SubElement(suite, "testcase", name="security regression", classname=name)
                ET.ElementTree(suite).write(path)
                self.reports.append(path)

    def check(self):
        with redirect_stdout(io.StringIO()):
            return results.check_results(self.root)

    def mutate_report(self, update):
        path = self.reports[0]
        tree = ET.parse(path)
        update(tree.getroot())
        tree.write(path)

    def test_every_expected_class_must_have_real_passing_cases(self):
        self.assertEqual(self.check(), [7, 0, 0, 0])

    def test_missing_class_is_rejected_even_when_other_classes_pass(self):
        self.reports[0].unlink()
        with self.assertRaisesRegex(ValueError, "Missing expected"):
            self.check()

    def test_an_empty_suite_is_not_success(self):
        def empty(suite):
            suite.set("tests", "0")
            suite.remove(suite.find("testcase"))
        self.mutate_report(empty)
        with self.assertRaisesRegex(ValueError, "executed no tests"):
            self.check()

    def test_declared_success_cannot_hide_a_failure_error_or_skip(self):
        for tag in ("failure", "error", "skipped"):
            with self.subTest(tag=tag):
                self.mutate_report(lambda suite: ET.SubElement(suite.find("testcase"), tag))
                with self.assertRaisesRegex(ValueError, "did not pass"):
                    self.check()
                self.mutate_report(lambda suite: suite.find("testcase").remove(suite.find(f"testcase/{tag}")))

    def test_nonzero_failure_error_or_skip_counts_are_rejected(self):
        for key in ("failures", "errors", "skipped"):
            with self.subTest(key=key):
                self.mutate_report(lambda suite: suite.set(key, "1"))
                with self.assertRaisesRegex(ValueError, "failures.*errors.*skipped"):
                    self.check()
                self.mutate_report(lambda suite: suite.set(key, "0"))

    def test_claimed_test_count_without_cases_is_rejected(self):
        self.mutate_report(lambda suite: suite.set("tests", "2"))
        with self.assertRaisesRegex(ValueError, "Invalid JUnit counts"):
            self.check()

    def test_duplicate_reports_are_rejected(self):
        path = self.reports[0]
        path.with_name("TEST-duplicate.xml").write_bytes(path.read_bytes())
        with self.assertRaisesRegex(ValueError, "Duplicate report"):
            self.check()

    def test_unexpected_test_class_is_rejected(self):
        self.mutate_report(lambda suite: suite.set("name", "unexpected.Test"))
        with self.assertRaisesRegex(ValueError, "Unexpected test class"):
            self.check()

    def test_report_in_another_task_cannot_satisfy_required_class(self):
        path = self.reports[0]
        other_task = path.parent.with_name("testUnrelatedUnitTest")
        other_task.mkdir()
        path.rename(other_task / path.name)
        with self.assertRaisesRegex(ValueError, "Missing expected"):
            self.check()


class DeviceEvidenceGateTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.evidence = self.root / "build/security-validation/device"
        screenshots = self.evidence / "screenshots"
        screenshots.mkdir(parents=True)
        for filename in device.SCREENSHOTS:
            Image.new("RGB", (1080, 1920), (80, 120, 160)).save(screenshots / filename)
        hashes = "a" * 64 + "  app/build/outputs/apk/prodWebsite/debug/app.apk\n"
        hashes += "b" * 64 + "  app/build/outputs/apk/androidTest/prodWebsite/debug/test.apk\n"
        (self.evidence / "apk-sha256.before.txt").write_text(hashes)
        (self.evidence / "apk-sha256.after.txt").write_text(hashes)
        (self.evidence / "exit-code.txt").write_text("0\n")
        (self.evidence / "device-api.txt").write_text("35\n")
        (self.evidence / "device-fingerprint.txt").write_text("API 35 emulator fixture\n")
        self.report = self.root / "app/build/outputs/androidTest-results/TEST-updater.xml"
        self.report.parent.mkdir(parents=True)
        suite = ET.Element("testsuite", tests="2", failures="0", errors="0", skipped="0")
        for name in device.METHODS:
            ET.SubElement(suite, "testcase", name=name, classname=device.TEST_CLASS)
        ET.ElementTree(suite).write(self.report)

    def check(self, build="success", emulator="success"):
        with redirect_stdout(io.StringIO()):
            return device.check_results(self.root, build, emulator)

    def test_complete_matching_evidence_passes(self):
        result = self.check()
        self.assertEqual(set(result["executed_methods"]), device.METHODS)
        self.assertEqual(len(result["screenshots_sha256"]), 2)

    def test_header_only_screenshot_is_rejected(self):
        header = bytearray(33)
        header[:8] = b"\x89PNG\r\n\x1a\n"
        header[12:16] = b"IHDR"
        header[16:24] = struct.pack(">II", 1080, 1920)
        (self.evidence / "screenshots/collie-updates-available.png").write_bytes(header)
        with self.assertRaisesRegex(ValueError, "Invalid screenshot"):
            self.check()

    def test_truncated_screenshot_is_rejected(self):
        path = self.evidence / "screenshots/collie-updates-available.png"
        data = path.read_bytes()
        path.write_bytes(data[:len(data) // 2])
        with self.assertRaisesRegex(ValueError, "Invalid screenshot"):
            self.check()

    def test_missing_method_is_rejected(self):
        tree = ET.parse(self.report)
        tree.getroot().remove(tree.getroot().find("testcase"))
        tree.write(self.report)
        with self.assertRaisesRegex(ValueError, "Exactly both"):
            self.check()

    def test_missing_screenshot_is_rejected(self):
        (self.evidence / "screenshots/collie-updates-failed.png").unlink()
        with self.assertRaisesRegex(ValueError, "Missing screenshots"):
            self.check()

    def test_changed_apk_is_rejected(self):
        hashes = self.evidence / "apk-sha256.after.txt"
        hashes.write_text(hashes.read_text().replace("a" * 64, "c" * 64))
        with self.assertRaisesRegex(ValueError, "APK changed"):
            self.check()

    def test_skipped_method_is_rejected(self):
        tree = ET.parse(self.report)
        ET.SubElement(tree.getroot().find("testcase"), "skipped")
        tree.write(self.report)
        with self.assertRaisesRegex(ValueError, "failed or skipped"):
            self.check()

    def test_wrong_platform_is_rejected(self):
        (self.evidence / "device-api.txt").write_text("34\n")
        with self.assertRaisesRegex(ValueError, "not API 35"):
            self.check()

    def test_report_cannot_hide_failed_emulator_action(self):
        with self.assertRaisesRegex(ValueError, "device outcome: failure"):
            self.check(emulator="failure")


class PinnedSourceGateTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.git("init", "--quiet")
        self.gradle = self.root / "build.gradle.kts"
        self.gradle.write_text("// Header\n\n// Body\n\n// End\n")
        self.production = self.root / "app/src/main/java/Boundary.kt"
        self.production.parent.mkdir(parents=True)
        self.production.write_text("fun requiresAuthentication() = true\n")
        self.commit("App")
        self.app_sha = self.git("rev-parse", "HEAD")
        self.gradle.write_text("// Header\n\n" + source.TEST_JNI_BLOCKS[0] + "// Body\n\n" + source.TEST_JNI_BLOCKS[1] + "// End\n")
        self.commit("Testing support")

    def git(self, *arguments):
        return subprocess.check_output(["git", *arguments], cwd=self.root, text=True).strip()

    def commit(self, message):
        self.git("add", ".")
        self.git("-c", "user.name=Validation fixture", "-c", "user.email=fixture@example.invalid",
                 "-c", "commit.gpgsign=false", "commit", "--quiet", "-m", message)

    def test_test_only_support_is_verified_and_records_exact_source_identity(self):
        receipt = source.verify_source(self.root, self.app_sha)
        self.assertTrue(receipt["production_sources_match"])
        self.assertEqual(receipt["app_sha"], self.app_sha)
        self.assertEqual(receipt["validation_sha"], self.git("rev-parse", "HEAD"))
        self.assertEqual(receipt["support_files_changed"], ["build.gradle.kts"])

    def test_unchanged_gradle_with_jni_in_baseline_is_accepted(self):
        baseline = self.git("rev-parse", "HEAD")
        self.assertTrue(source.verify_source(self.root, baseline)["production_sources_match"])

    def test_extra_gradle_edit_against_jni_baseline_is_rejected(self):
        baseline = self.git("rev-parse", "HEAD")
        with self.gradle.open("a") as output:
            output.write("// Unexpected build change\n")
        self.commit("Unexpected build change after JNI baseline")
        with self.assertRaisesRegex(ValueError, "Root Gradle differs"):
            source.verify_source(self.root, baseline)

    def test_production_change_is_rejected(self):
        self.production.write_text("fun requiresAuthentication() = false\n")
        self.commit("Unexpected production change")
        with self.assertRaisesRegex(ValueError, "Validation changed pinned app files"):
            source.verify_source(self.root, self.app_sha)

    def test_extra_gradle_change_is_rejected(self):
        with self.gradle.open("a") as output:
            output.write("// Unexpected build change\n")
        self.commit("Unexpected build change")
        with self.assertRaisesRegex(ValueError, "Root Gradle differs"):
            source.verify_source(self.root, self.app_sha)

    def test_uncommitted_source_change_is_rejected(self):
        self.production.write_text("fun requiresAuthentication() = false\n")
        with self.assertRaisesRegex(ValueError, "Tracked working tree differs"):
            source.verify_source(self.root, self.app_sha)

    def test_untracked_source_is_rejected(self):
        (self.production.parent / "Injected.kt").write_text("// Unexpected source\n")
        with self.assertRaisesRegex(ValueError, "Unexpected untracked files"):
            source.verify_source(self.root, self.app_sha)

    def test_unpinned_reference_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "full reviewed app commit"):
            source.verify_source(self.root, "main")


if __name__ == "__main__":
    unittest.main()
