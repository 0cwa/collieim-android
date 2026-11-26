"""Exercise the local pre-push preflight summary and failure paths."""

from contextlib import redirect_stdout
import importlib.util
import io
from pathlib import Path
import shutil
import subprocess
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).with_name("preflight.py")
SPEC = importlib.util.spec_from_file_location("preflight", SCRIPT)
preflight = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(preflight)


class PreflightTests(unittest.TestCase):
    def invoke(self, outcomes, argv=None):
        def run(command, **kwargs):
            if command[0] == "git":
                return subprocess.CompletedProcess(command, 0, outcomes.get("signatures", "abc123 G\n"), "")
            check = "trailers" if "collie_check_layer_trailers.py" in command[1] else "metadata"
            return subprocess.CompletedProcess(command, outcomes.get(check, 0), outcomes.get(f"{check}_output", ""), "")

        output = io.StringIO()
        with patch.object(preflight.subprocess, "run", side_effect=run), redirect_stdout(output):
            result = preflight.main(argv or [])
        return result, output.getvalue()

    def test_all_checks_pass(self):
        result, output = self.invoke({})
        self.assertEqual(result, 0)
        registered = sum(line.startswith(("PASS: ", "FAIL: ")) for line in output.splitlines())
        self.assertGreater(registered, 0)
        self.assertIn(f"Preflight PASSED ({registered}/{registered} checks)", output)

    def test_pinned_pairing_manifest_passes(self):
        output = io.StringIO()
        with redirect_stdout(output):
            self.assertTrue(preflight.check_pairing_manifest())
        self.assertIn("PASS: CollieSocket pairing v1 manifest", output.getvalue())

    def test_tampered_pairing_artifact_fails_preflight(self):
        with TemporaryDirectory() as temporary:
            root = Path(temporary) / preflight.PAIRING_CONTRACT_ROOT.name
            shutil.copytree(preflight.PAIRING_CONTRACT_ROOT, root)
            offer = root / "offer.schema.json"
            offer.write_bytes(offer.read_bytes() + b"\n")
            with patch.object(preflight, "PAIRING_CONTRACT_ROOT", root):
                result, output = self.invoke({})
        self.assertEqual(result, 1)
        self.assertIn("hash mismatch: offer.schema.json", output)
        self.assertIn("FAIL: CollieSocket pairing v1 manifest", output)

    def test_layer_trailer_failure_is_reported(self):
        result, output = self.invoke({"trailers": 1, "trailers_output": "bad layer\n"})
        self.assertEqual(result, 1)
        self.assertIn("FAIL: Layer trailers", output)
        self.assertIn("Preflight FAILED", output)

    def test_metadata_failure_is_reported(self):
        result, output = self.invoke({"metadata": 1, "metadata_output": "invalid metadata\n"})
        self.assertEqual(result, 1)
        self.assertIn("FAIL: Fastlane metadata", output)

    def test_unsigned_and_bad_signature_commits_fail(self):
        for status in ("N", "B"):
            with self.subTest(status=status):
                result, output = self.invoke({"signatures": f"deadbeef {status}\n"})
                self.assertEqual(result, 1)
                self.assertIn("deadbeef", output)
                self.assertIn("FAIL: commit signatures", output)

    def test_allow_unsigned_keeps_other_checks_and_passes(self):
        result, output = self.invoke({"signatures": "deadbeef N\n"}, ["--allow-unsigned"])
        self.assertEqual(result, 0)
        self.assertIn("PASS: commit signatures (unsigned commits allowed)", output)

    def test_signature_log_failure_fails(self):
        def run(command, **kwargs):
            if command[0] == "git":
                return subprocess.CompletedProcess(command, 128, "", "unknown ref")
            return subprocess.CompletedProcess(command, 0, "", "")

        output = io.StringIO()
        with patch.object(preflight.subprocess, "run", side_effect=run), redirect_stdout(output):
            result = preflight.main([])
        self.assertEqual(result, 1)
        self.assertIn("git log failed: unknown ref", output.getvalue())


if __name__ == "__main__":
    unittest.main()
