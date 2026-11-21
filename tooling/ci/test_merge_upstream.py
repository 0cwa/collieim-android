"""Exercise the upstream merge against real Git repositories."""
import json
import os
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "upstream" / "merge-upstream.py"
REMOVED = "app/donation.kt"
SHARED = "app/shared.kt"


class MergeUpstreamTest(unittest.TestCase):
    def git(self, *args, check=True):
        return subprocess.run(
            ["git", "-C", str(self.root), *args], env=self.env,
            text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=check
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
        scratch = Path(os.environ.get(
            "RUNNER_TEMP", Path.home() / "agent-scratch/collieim-android/merge-upstream"
        ))
        scratch.mkdir(parents=True, exist_ok=True)
        temporary = TemporaryDirectory(prefix="merge-upstream-", dir=scratch)
        self.addCleanup(temporary.cleanup)
        self.env = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
        self.env.update(GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull)
        self.root = Path(temporary.name) / "collie"
        self.root.mkdir()
        self.upstream = Path(temporary.name) / "upstream.git"
        self.git("init", "-q", "--initial-branch=main")
        self.git("config", "user.name", "Test")
        self.git("config", "user.email", "test@example.invalid")
        self.put(REMOVED, "checkout\n")
        self.put(SHARED, "shared\n")
        self.old = self.commit("upstream old")
        subprocess.run(["git", "init", "-q", "--bare", str(self.upstream)], env=self.env, check=True)

    def publish(self, *, tag=None, branch=None):
        """Push the current upstream commit, then return to a Collie branch."""
        if tag:
            self.git("tag", "-a", "-m", tag, tag)
            self.git("push", "-q", str(self.upstream), f"refs/tags/{tag}")
            self.git("tag", "-d", tag)
        if branch:
            self.git("push", "-q", str(self.upstream), f"HEAD:refs/heads/{branch}")
        return self.git("rev-parse", "HEAD").strip()

    def fork(self, *, modify_shared=False):
        self.git("checkout", "-q", "-b", "collie", self.old)
        (self.root / REMOVED).unlink(missing_ok=True)
        if modify_shared:
            self.put(SHARED, "fork edit\n")
        self.put("tooling/upstream/signal-donation-removals.json", json.dumps({
            "schema": 1, "removed": [{"path": REMOVED, "reason": "exclusive checkout"}]
        }))
        self.commit("fork")

    def merge(self, *args):
        return subprocess.run(
            ["python3", str(SCRIPT), *args, "--url", str(self.upstream)],
            cwd=self.root, env=self.env, text=True, capture_output=True
        )

    def message(self):
        return (self.root / ".git/MERGE_MSG").read_text()

    def test_signal_merge_keeps_removed_file_deleted_and_prepares_trailers(self):
        self.git("checkout", "-q", "-b", "signal")
        self.put(REMOVED, "updated checkout\n")
        self.put(SHARED, "updated shared code\n")
        self.put("another/app/donation.kt", "new unlisted file with similar name\n")
        target = self.commit("upstream new")
        self.publish(tag="v2.0")
        self.fork()
        result = self.merge("signal", "v2.0")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("No conflicts remain", result.stdout)
        self.assertFalse((self.root / REMOVED).exists())
        self.assertEqual(self.git("ls-files", "-u"), "")
        self.assertEqual((self.root / SHARED).read_text(), "updated shared code\n")
        self.assertTrue((self.root / "another/app/donation.kt").is_file())
        self.assertEqual(self.git("rev-parse", "MERGE_HEAD").strip(), target)
        self.assertEqual(self.git("rev-parse", "refs/tags/signal-v2.0^{commit}").strip(), target)
        self.assertTrue(self.message().endswith(
            "Layer: upgrade\nUpstream: signalapp/Signal-Android v2.0\n"))
        self.assertIn("Kept 1 files deleted", self.message())
        self.git("commit", "--no-gpg-sign", "--no-edit")
        self.assertEqual(self.git("show", "-s", "--format=%P").split()[1], target)
        self.assertIn("Upstream: signalapp/Signal-Android v2.0", self.git("show", "-s", "--format=%B"))

    def test_other_conflicts_are_listed_and_left_for_review(self):
        self.git("checkout", "-q", "-b", "molly")
        self.put(REMOVED, "upstream edit\n")
        self.put(SHARED, "upstream edit\n")
        target = self.commit("upstream new")
        self.publish(branch="main")
        self.fork(modify_shared=True)
        result = self.merge("molly", "main")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Resolve 1 conflicts", result.stdout)
        self.assertIn(SHARED, self.git("ls-files", "-u"))
        self.assertFalse((self.root / REMOVED).exists())
        self.assertIn(f"- {SHARED} (both modified)", self.message())
        self.assertIn(f"Upstream: mollyim/mollyim-android main@{target}", self.message())

    def test_join_records_an_already_ported_release_without_changes(self):
        self.git("checkout", "-q", "-b", "signal")
        self.put(SHARED, "updated shared code\n")
        target = self.commit("upstream new")
        self.publish(tag="v2.0")
        self.fork(modify_shared=True)
        result = self.merge("signal", "v2.0", "--join")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.git("rev-parse", "MERGE_HEAD").strip(), target)
        self.assertEqual(self.git("diff", "--cached", "--name-only", "HEAD"), "")

    def test_upstream_adding_a_removed_path_is_flagged(self):
        self.git("rm", "-q", REMOVED)
        self.old = self.commit("upstream removes checkout")  # Collie forks after this.
        self.git("checkout", "-q", "-b", "signal")
        self.put(REMOVED, "new checkout\n")
        self.commit("upstream adds checkout again")
        self.publish(tag="v2.0")
        self.fork()
        result = self.merge("signal", "v2.0")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(f"Review: upstream adds {REMOVED}", result.stdout)
        self.assertIn(f"- {REMOVED}", self.message())

    def test_refuses_dirty_tree_and_unknown_names(self):
        self.publish(tag="v2.0")
        self.fork()
        self.put("untracked.txt", "local work\n")
        result = self.merge("signal", "v2.0")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("clean topic branch", result.stderr)
        (self.root / "untracked.txt").unlink()
        for name in ("../v2.0", "-v2.0", "v2.0 extra"):
            with self.subTest(name=name):
                result = self.merge("signal", name)
                self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.git("rev-parse", "-q", "--verify", "MERGE_HEAD", check=False), "")


if __name__ == "__main__":
    unittest.main()
