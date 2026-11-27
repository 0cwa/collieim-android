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
    def invoke(self, outcomes, argv=None, expected_base="origin/main"):
        def run(command, **kwargs):
            if command[:3] == ["git", "remote", "-v"]:
                return subprocess.CompletedProcess(command, 0, outcomes.get("remotes", ""), "")
            if command[:2] == ["git", "for-each-ref"]:
                return subprocess.CompletedProcess(command, 0, outcomes.get("refs", ""), "")
            if command[:2] == ["git", "log"]:
                self.assertEqual(command[-1], f"{expected_base}..HEAD")
                return subprocess.CompletedProcess(command, 0, outcomes.get("signatures", "abc123 G\n"), "")
            check = "trailers" if "collie_check_layer_trailers.py" in command[1] else "metadata"
            if check == "trailers":
                self.assertEqual(command[2:], ["range", expected_base, "HEAD"])
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

    def test_canonical_remote_wins_over_upstream_and_fork_origin(self):
        for url in (
            "https://github.com/0cwa/collieim-android.git",
            "git@github.com:0cwa/collieim-android.git",
            "ssh://git@github.com/0cwa/collieim-android",
        ):
            with self.subTest(url=url):
                result, _ = self.invoke({
                    "remotes": (
                        "origin\thttps://github.com/contributor/collieim-android.git (fetch)\n"
                        f"origin\t{url} (push)\n"
                        f"collie\t{url} (fetch)\n"
                    ),
                    "refs": "origin/main\nupstream/main\ncollie/main\n",
                }, expected_base="collie/main")
                self.assertEqual(result, 0)

    def test_unfetched_canonical_main_falls_back_to_upstream(self):
        result, _ = self.invoke({
            "remotes": "collie\thttps://github.com/0cwa/collieim-android (fetch)\n",
            "refs": "origin/main\nupstream/main\n",
        }, expected_base="upstream/main")
        self.assertEqual(result, 0)

    def test_similar_url_falls_back_to_origin(self):
        result, _ = self.invoke({
            "remotes": "collie\thttps://github.com/0cwa/collieim-android-fork (fetch)\n",
            "refs": "collie/main\norigin/main\n",
        })
        self.assertEqual(result, 0)

    def test_explicit_base_skips_remote_detection(self):
        with patch.object(preflight, "default_base") as detect:
            result, _ = self.invoke({}, ["--base", "release/stable/8.35"], expected_base="release/stable/8.35")
        detect.assert_not_called()
        self.assertEqual(result, 0)

    def test_pinned_pairing_manifest_passes(self):
        output = io.StringIO()
        with redirect_stdout(output):
            self.assertTrue(preflight.check_pairing_manifest())
        self.assertIn("PASS: CollieSocket pairing v1 manifest", output.getvalue())

    def test_pinned_v4_contract_manifest_passes(self):
        output = io.StringIO()
        with redirect_stdout(output):
            self.assertTrue(preflight.check_v4_contract_manifest())
        self.assertIn("PASS: CollieSocket encrypted push v4 manifest", output.getvalue())

    def test_tampered_v4_contract_artifact_fails_preflight(self):
        with TemporaryDirectory() as temporary:
            root = Path(temporary) / preflight.V4_CONTRACT_ROOT.name
            shutil.copytree(preflight.V4_CONTRACT_ROOT, root)
            schema = root / "event.schema.json"
            schema.write_bytes(schema.read_bytes() + b"\n")
            with patch.object(preflight, "V4_CONTRACT_ROOT", root):
                result, output = self.invoke({})
        self.assertEqual(result, 1)
        self.assertIn("hash mismatch: event.schema.json", output)
        self.assertIn("FAIL: CollieSocket encrypted push v4 manifest", output)

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
            result = preflight.main(["--base", "origin/main"])
        self.assertEqual(result, 1)
        self.assertIn("git log failed: unknown ref", output.getvalue())


if __name__ == "__main__":
    unittest.main()
