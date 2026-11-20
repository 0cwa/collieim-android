"""Exercise the Signal port against real Git repositories."""
import json
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "upstream" / "apply-signal-upgrade.py"
REMOVED = "app/donation.kt"
SHARED = "app/shared.kt"


class SignalUpgradeTest(unittest.TestCase):
    def git(self, *args):
        return subprocess.run(
            ["git", "-C", str(self.root), *args],
            text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True
        ).stdout

    def put(self, name, content):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)

    def commit(self, label):
        self.git("add", "-A")
        self.git("commit", "--no-gpg-sign", "-qm", label)
        return self.git("rev-parse", "HEAD").strip()

    def setUp(self):
        temporary = TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.git("init", "-q")
        self.git("config", "user.name", "Test")
        self.git("config", "user.email", "test@example.invalid")
        self.put(REMOVED, "checkout\n")
        self.put(SHARED, "shared\n")
        self.old = self.commit("upstream old")

    def fork(self, *, remove=True, modify_shared=False):
        self.git("checkout", "--detach", self.old)
        if remove:
            (self.root / REMOVED).unlink()
        if modify_shared:
            self.put(SHARED, "fork edit\n")
        self.put("tooling/upstream/signal-donation-removals.json", json.dumps({
            "schema": 1, "removed": [{"path": REMOVED, "reason": "exclusive checkout"}]
        }))
        self.commit("fork")

    def port(self, target):
        return subprocess.run(
            ["python3", str(SCRIPT), self.old, target],
            cwd=self.root, text=True, capture_output=True
        )

    def test_upstream_edit_to_removed_file_does_not_hide_shared_edit(self):
        self.put(REMOVED, "updated checkout\n")
        self.put(SHARED, "updated shared code\n")
        self.put("another/app/donation.kt", "new unlisted file with similar name\n")
        target = self.commit("upstream new")
        self.fork()
        result = self.port(target)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse((self.root / REMOVED).exists())
        self.assertIn(SHARED, self.git("diff", "--cached", "--name-only"))
        self.assertTrue((self.root / "another/app/donation.kt").is_file())

    def test_reapplies_listed_deletion_when_fork_still_has_file(self):
        self.put(SHARED, "updated shared code\n")
        target = self.commit("upstream new")
        self.fork(remove=False)
        result = self.port(target)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse((self.root / REMOVED).exists())
        self.assertIn(f"D\t{REMOVED}", self.git("diff", "--cached", "--name-status"))

    def test_outside_conflict_stops_before_removal(self):
        self.put(SHARED, "upstream edit\n")
        target = self.commit("upstream new")
        self.fork(remove=False, modify_shared=True)
        self.assertNotEqual(self.port(target).returncode, 0)
        self.assertIn(SHARED, self.git("ls-files", "-u"))
        self.assertTrue((self.root / REMOVED).is_file())

    def test_outgoing_rename_requires_manual_review(self):
        self.git("mv", REMOVED, "app/renamed.kt")
        target = self.commit("upstream moves checkout")
        self.fork()
        result = self.port(target)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("overlaps reviewed removal path", result.stderr)
        self.assertEqual(self.git("status", "--porcelain"), "")


if __name__ == "__main__":
    unittest.main()
