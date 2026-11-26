"""Exercise the feature diff CLI against small, isolated Git histories."""

import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().with_name("feature_diff.py")
CATALOG = """
[shared]
paths = ["**/strings2.xml"]

[features.relay]
about = "Relay enrollment"
paths = ["relay/**", "integration.txt"]
shared_paths = ["res/strings2.xml", "integration.txt"]
depends_on = ["alerts"]

[features.alerts]
about = "Linked-device alerts"
paths = ["alerts/*"]
shared_paths = ["res/strings2.xml"]
"""


class GitFixture(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory(prefix="feature-diff-")
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.git("init", "-q", "-b", "collie")
        for key, value in {
            "user.name": "Fixture",
            "user.email": "fixture@example.invalid",
            "commit.gpgsign": "false",
            "core.hooksPath": str(self.root / "no-hooks"),
            "core.autocrlf": "false",
        }.items():
            self.git("config", key, value)
        self.write("tooling/features.toml", CATALOG)
        self.write("integration.txt", "first\n")
        self.write("res/strings2.xml", "first\n")
        self.write("alerts/job.kt", "first\n")
        self.write("alerts/remove.kt", "remove\n")
        self.write("old.txt", "rename\n")
        self.initial = self.commit("Initial tree")

    def git(self, *args):
        return subprocess.run(["git", *args], cwd=self.root, check=True,
                              stdout=subprocess.PIPE).stdout

    def write(self, path, content):
        file = self.root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(content, bytes):
            file.write_bytes(content)
        else:
            file.write_text(content, encoding="utf-8")

    def commit(self, message):
        self.git("add", "-A")
        self.git("commit", "-qm", message)
        return self.git("rev-parse", "HEAD").decode().strip()

    def cli(self, *args, cwd=None, expected=0):
        result = subprocess.run([sys.executable, str(SCRIPT), *args], cwd=cwd or self.root,
                                capture_output=True)
        self.assertEqual(result.returncode, expected, result.stderr.decode())
        return result

    def changes(self):
        self.write("integration.txt", "first\nsecond\n")
        self.write("res/strings2.xml", "second\n")
        self.write("alerts/job.kt", "first\nsecond\n")
        (self.root / "alerts/remove.kt").unlink()
        self.write("alerts/nested/unowned.kt", "single * cannot span directories\n")
        self.write("relay/nested/:(exclude)weird\tname\n.txt", "new relay\n")
        self.write("relay/art.bin", b"\0binary")
        (self.root / "old.txt").rename(self.root / "relay/new.txt")
        return self.commit("Collie changes")

    def configure_signing(self):
        key = self.root / ".git/fixture-signer"
        subprocess.run(["ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", str(key)], check=True)
        allowed = self.root / ".git/allowed-signers"
        allowed.write_text("fixture@example.invalid " + key.with_suffix(".pub").read_text())
        self.git("config", "gpg.format", "ssh")
        self.git("config", "gpg.ssh.allowedSignersFile", str(allowed))
        self.git("config", "user.signingKey", str(key))

    def upgrade(self, branch, *, signed=True, pinned=True, layer="upgrade"):
        self.git("switch", "-qc", branch, self.initial)
        self.write(f"{branch}.txt", "Molly\n")
        upstream = self.commit(f"Upstream {branch}")
        self.git("switch", "-q", "integration")
        trailer = upstream if pinned else self.initial
        flag = "-S" if signed else "--no-gpg-sign"
        self.git("merge", "-q", "--no-ff", flag, "-m", "Merge upstream", "-m",
                 f"Layer: {layer}\nUpstream: mollyim/mollyim-android {branch}@{trailer}", branch)
        merge = self.git("rev-parse", "HEAD").decode().strip()
        return merge, upstream

    def test_auto_base_finds_signed_pinned_upgrade_off_first_parent(self):
        self.configure_signing()
        self.git("switch", "-qc", "integration")
        self.upgrade("older")
        merge, upstream = self.upgrade("molly")
        self.upgrade("wrong-parent", pinned=False)
        self.upgrade("unsigned", signed=False)
        self.upgrade("wrong-layer", layer="tooling")
        self.git("switch", "-q", "collie")
        self.write("collie.txt", "Collie\n")
        self.commit("Collie work while upgrades are integrated")
        self.git("merge", "-q", "--no-ff", "-m", "Merge integration PR", "integration")
        report = json.loads(self.cli("--json").stdout)
        self.assertEqual(report["base"], upstream)
        self.assertEqual(report["molly_merge"], merge)
        historical = json.loads(self.cli("--head", merge, "--json").stdout)
        self.assertEqual(historical["base"], upstream)
        self.assertEqual(historical["head"], merge)

    def test_no_upgrade_explains_base_override(self):
        result = self.cli(expected=1)
        self.assertIn(b"supply --base <rev>", result.stderr)
        self.assertIn(b"OK", self.cli("--check").stdout)

    def test_summary_json_accounts_for_every_path_once(self):
        head = self.changes()
        report = json.loads(self.cli("--base", self.initial, "--head", head, "--json",
                                     cwd=self.root / "tooling").stdout)
        paths = {entry["path"]: entry for entry in report["files"]}
        self.assertEqual(report["total"], {
            "files": 9, "collie_only": 4, "modified_upstream": 5, "added": 6, "deleted": 3, "binary": 1,
        })
        self.assertEqual(sum(row["files"] for row in report["owners"].values()), 9)
        self.assertEqual(report["owners"]["shared"]["files"], 2)
        self.assertEqual(paths["res/strings2.xml"]["shared_with"], ["alerts", "relay"])
        self.assertEqual(paths["integration.txt"]["owner"], "shared")
        self.assertEqual(paths["relay/new.txt"]["origin"], "collie-only")
        self.assertEqual(paths["old.txt"]["status"], "D")
        self.assertEqual(paths["alerts/job.kt"]["origin"], "modified-upstream")
        self.assertEqual(paths["alerts/remove.kt"]["status"], "D")
        self.assertEqual(paths["alerts/nested/unowned.kt"]["owner"], "unmapped")
        self.assertIsNone(paths["relay/art.bin"]["added"])
        self.assertEqual(report["features"]["relay"]["depends_on"], ["alerts"])
        summary = self.cli("--base", self.initial).stdout
        self.assertRegex(summary.decode(), r"(?m)^unmapped\s+2\s")

    def test_diff_matches_native_git_and_limits_shared_files_to_feature(self):
        head = self.changes()
        owned = ["relay/art.bin", "relay/nested/:(exclude)weird\tname\n.txt", "relay/new.txt"]
        for extra in ([], ["--include-shared"]):
            with self.subTest(extra=extra):
                selected = owned + (["integration.txt", "res/strings2.xml"] if extra else [])
                expected = self.git("--literal-pathspecs", "diff", f"{self.initial}..{head}", "--", *selected)
                self.assertEqual(self.cli("diff", "--base", self.initial, "--feature", "relay",
                                          *extra, cwd=self.root / "tooling").stdout, expected)
        self.assertEqual(self.cli("diff", "--base", head, "--feature", "alerts").stdout, b"")
        self.assertIn(b"unknown feature", self.cli("diff", "--feature", "absent", expected=1).stderr)

    def test_check_rejects_invalid_toml_and_invalid_schema(self):
        for catalog, message in [
            ("[features.relay\n", b"line"),
            (CATALOG.replace('about = "Relay enrollment"', "about = 7"), b"about"),
            (CATALOG.replace('"relay/**"', '"../relay/**"'), b"repository-relative"),
            (CATALOG.replace('"relay/**"', '"relay/**.kt"'), b"whole path component"),
        ]:
            with self.subTest(catalog=catalog):
                self.write("tooling/features.toml", catalog)
                self.assertIn(message, self.cli("--check", expected=1).stderr)

    def test_check_rejects_duplicate_exclusive_patterns_and_tracked_overlaps(self):
        for patterns, conflict in [
            ('"alerts/*"', b"alerts/*"),
            ('"alerts/job.kt"', b"alerts/job.kt"),
            ('"alerts/**/*.kt"', b"alerts/job.kt"),
        ]:
            with self.subTest(patterns=patterns):
                self.write("tooling/features.toml", CATALOG.replace('paths = ["relay/**", "integration.txt"]',
                                                                   f'paths = ["relay/**", {patterns}]'))
                expected = self.cli("--check", expected=1).stderr
                self.assertIn(conflict, expected)

    def test_check_finds_overlap_in_deleted_paths_when_base_is_given(self):
        self.write("tooling/features.toml", CATALOG.replace('"relay/**"', '"alerts/**/remove.*"'))
        (self.root / "alerts/remove.kt").unlink()
        self.commit("Remove a path with overlapping patterns")
        self.assertIn(b"OK", self.cli("--check").stdout)
        self.assertIn(b"alerts/remove.kt", self.cli("--base", self.initial, "--check", expected=1).stderr)

    def test_check_rejects_unknown_dependencies_and_cycles(self):
        self.write("tooling/features.toml", CATALOG.replace('depends_on = ["alerts"]', 'depends_on = ["missing"]'))
        self.assertIn(b"depends on unknown features: missing", self.cli("--check", expected=1).stderr)
        self.write("tooling/features.toml", CATALOG + 'depends_on = ["relay"]\n')
        self.assertIn(b"dependency cycle", self.cli("--check", expected=1).stderr)


class RealCatalog(unittest.TestCase):
    def test_real_map_passes_check(self):
        result = subprocess.run([sys.executable, str(SCRIPT), "--check"], cwd=SCRIPT.parents[2],
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("tooling/features.toml: OK", result.stdout)


if __name__ == "__main__":
    unittest.main()
