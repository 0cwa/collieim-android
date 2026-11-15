"""Exercise missing, skipped, failing, and misleading JUnit evidence."""

from contextlib import redirect_stdout
import importlib.util
import io
import json
import shutil
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET


SCRIPT = Path(__file__).resolve().parents[2] / ".github/scripts/check-unit-test-results.py"
SPEC = importlib.util.spec_from_file_location("check_unit_test_results", SCRIPT)
gate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gate)


class ResultGateTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.app = "app:testProdWebsiteDebugUnitTest"
        self.library = "lib:storage:testDebugUnitTest"
        self.policy = {"schema": 1, "required_tasks": [self.app, self.library],
                       "security_suites": [{"task": self.app, "class": "example.SecurityTest",
                                            "min_executed": 2}]}
        self.security = self.report(self.app, "example.SecurityTest", [None, None])
        self.other = self.report(self.library, "example.StorageTest", [None])

    def report(self, task, name, outcomes):
        parts = task.split(":")
        path = self.root.joinpath(*parts[:-1], "build", "test-results", parts[-1], f"TEST-{name}.xml")
        path.parent.mkdir(parents=True, exist_ok=True)
        suite = ET.Element("testsuite", name=name, tests=str(len(outcomes)),
                           failures=str(outcomes.count("failure")), errors=str(outcomes.count("error")),
                           skipped=str(outcomes.count("skipped")))
        for index, outcome in enumerate(outcomes):
            case = ET.SubElement(suite, "testcase", name=f"case-{index}", classname=name)
            if outcome:
                ET.SubElement(case, outcome)
        ET.ElementTree(suite).write(path, encoding="unicode")
        return path

    def check(self):
        with redirect_stdout(io.StringIO()):
            return gate.check_results(self.root, self.policy)

    def test_complete_successful_task_and_security_evidence_passes(self):
        result = self.check()
        self.assertTrue(result["passed"])
        self.assertEqual(result["total"], {"tests": 3, "failures": 0, "errors": 0, "skipped": 0})

    def test_missing_module_is_rejected_even_when_app_tests_pass(self):
        self.other.unlink()
        result = self.check()
        self.assertFalse(result["passed"])
        self.assertIn(self.library, "\n".join(result["problems"]))

    def test_plain_jvm_task_reports_are_required_and_collected(self):
        paths = []
        for task, name in (("core:util-jvm:test", "example.ResettableStateTest"),
                           ("lib:libsignal-service:test", "example.ControlMessageTest"),
                           ("core:network:test", "example.CoreNetworkTest"),
                           ("lib:network:test", "example.NetworkTest"),
                           ("tooling:fast-lint:test", "example.FastLintTest"),
                           ("tooling:lintchecks:test", "example.LintChecksTest")):
            self.policy["required_tasks"].append(task)
            self.policy["security_suites"].append({"task": task, "class": name, "min_executed": 1})
            paths.append(self.report(task, name, [None]))
        result = self.check()
        self.assertTrue(result["passed"])
        self.assertEqual(result["tasks"]["core:util-jvm:test"]["tests"], 1)
        self.assertEqual(result["tasks"]["lib:libsignal-service:test"]["tests"], 1)
        for path in paths:
            data = path.read_bytes()
            path.unlink()
            self.assertFalse(self.check()["passed"])
            path.write_bytes(data)

    def test_missing_security_suite_is_rejected_even_when_app_is_nonempty(self):
        self.security.unlink()
        self.report(self.app, "example.UnrelatedTest", [None, None, None])
        self.assertFalse(self.check()["passed"])

    def test_security_suite_cannot_shrink_below_its_reviewed_minimum(self):
        self.report(self.app, "example.SecurityTest", [None])
        self.assertFalse(self.check()["passed"])

    def test_security_suite_cannot_pass_with_a_skipped_case(self):
        self.report(self.app, "example.SecurityTest", [None, None, "skipped"])
        self.assertFalse(self.check()["passed"])

    def test_failure_or_error_in_any_suite_is_rejected(self):
        for outcome in ("failure", "error"):
            with self.subTest(outcome=outcome):
                self.report(self.library, "example.StorageTest", [outcome])
                self.assertFalse(self.check()["passed"])

    def test_a_required_task_with_only_skips_is_rejected(self):
        self.report(self.library, "example.StorageTest", ["skipped"])
        self.assertFalse(self.check()["passed"])

    def test_other_skips_are_preserved_as_named_nonpassing_evidence(self):
        self.report(self.library, "example.StorageTest", [None, "skipped"])
        result = self.check()
        self.assertTrue(result["passed"])
        self.assertEqual(result["skipped_cases"], [{"task": self.library, "class": "example.StorageTest",
                                                  "name": "case-1"}])
        self.assertEqual(result["total"]["skipped"], 1)

    def test_empty_reports_cannot_pass(self):
        self.security.unlink()
        self.other.unlink()
        self.assertFalse(self.check()["passed"])

    def test_shards_must_each_execute_tests_and_cannot_duplicate_suites(self):
        self.report(self.library, "example.StorageTest", [None, "skipped"])
        first, second = self.root / "first", self.root / "second"
        first.mkdir()
        second.mkdir()
        shutil.move(str(self.root / "app"), first)
        shutil.move(str(self.root / "lib"), second)
        with redirect_stdout(io.StringIO()):
            result = gate.check_results(self.root, self.policy, [first, second])
        self.assertTrue(result["passed"])
        self.assertEqual(result["shards"], {"first": 2, "second": 1})
        self.assertEqual(result["shard_totals"], {
            "first": {"tests": 2, "failures": 0, "errors": 0, "skipped": 0},
            "second": {"tests": 2, "failures": 0, "errors": 0, "skipped": 1}})
        shutil.rmtree(second / "lib")
        with redirect_stdout(io.StringIO()):
            result = gate.check_results(self.root, self.policy, [first, second])
        self.assertIn("Shard has no executed tests: second", result["problems"])
        shutil.copytree(first / "app", second / "app")
        with self.assertRaisesRegex(ValueError, "Duplicate JUnit suite"):
            gate.check_results(self.root, self.policy, [first, second])

    def test_forged_summary_cannot_hide_a_failed_testcase(self):
        self.report(self.app, "example.SecurityTest", [None, "failure"])
        self.security.write_text(self.security.read_text().replace('failures="1"', 'failures="0"'))
        with self.assertRaisesRegex(ValueError, "totals do not match"):
            self.check()

    def test_duplicate_reports_cannot_inflate_security_coverage(self):
        duplicate = self.security.with_name("TEST-duplicate.xml")
        duplicate.write_bytes(self.security.read_bytes())
        with self.assertRaisesRegex(ValueError, "Duplicate JUnit suite"):
            self.check()

    def test_malformed_report_is_rejected(self):
        self.security.write_text("<testsuite")
        with self.assertRaises(ET.ParseError):
            self.check()

    def test_duplicate_shard_names_are_rejected(self):
        first = self.root / "one" / "shard"
        second = self.root / "two" / "shard"
        first.mkdir(parents=True)
        second.mkdir(parents=True)
        with self.assertRaisesRegex(ValueError, "Duplicate shard root name"):
            gate.check_results(self.root, self.policy, [first, second])

    def test_policy_rejects_duplicate_tasks_or_unreviewed_security_tasks(self):
        path = self.root / "policy.json"
        self.policy["required_tasks"].append(self.app)
        path.write_text(json.dumps(self.policy))
        with self.assertRaisesRegex(ValueError, "distinct required tasks"):
            gate.load_policy(path)
        self.policy["required_tasks"].pop()
        self.policy["security_suites"][0]["task"] = "other:task"
        path.write_text(json.dumps(self.policy))
        with self.assertRaisesRegex(ValueError, "Invalid or duplicate security suite"):
            gate.load_policy(path)


if __name__ == "__main__":
    unittest.main()
