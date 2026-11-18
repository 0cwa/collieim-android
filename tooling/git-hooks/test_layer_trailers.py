"""Exercise the PR #168 regression through real commits, merges and pushes."""

import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPTS = Path(__file__).resolve().parent
CHECKER = SCRIPTS / "collie_check_layer_trailers.py"
INSTALLER = SCRIPTS / "install.py"
GOOD = "Change the website\n\nLayer: website\nCo-authored-by: Name <name@example.com>\n"
BROKEN = GOOD.replace("website\nCo-authored", "website\n\nCo-authored")


class LayerTrailerTests(unittest.TestCase):
    def setUp(self):
        scratch = Path(os.environ.get(
            "RUNNER_TEMP", Path.home() / "agent-scratch/collieim-android/git-hooks"
        ))
        scratch.mkdir(parents=True, exist_ok=True)
        temporary = tempfile.TemporaryDirectory(prefix="layer-trailers-", dir=scratch)
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.repo = self.root / "repository with spaces"
        self.repo.mkdir()
        self.env = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
        self.env.update(GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull)
        self.git("-c", "init.templateDir=", "init", "--initial-branch=main")
        self.git("config", "user.name", "Test Author")
        self.git("config", "user.email", "test@example.com")
        self.git("config", "commit.gpgsign", "false")
        self.commit("Initial source")  # Historical main commits are outside the PR range.
        self.base = self.git("rev-parse", "HEAD").stdout.strip()
        self.git("update-ref", "refs/remotes/origin/main", self.base)

    def run_command(self, *args, check=True, **kwargs):
        result = subprocess.run(
            args, cwd=kwargs.pop("cwd", self.repo), env=self.env, text=True,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, **kwargs
        )
        if check:
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return result

    def git(self, *args, **kwargs):
        return self.run_command("git", *args, **kwargs)

    def commit(self, message, **kwargs):
        return self.git("commit", "--allow-empty", "--file=-", input=message, **kwargs)

    def install(self, **kwargs):
        return self.run_command(sys.executable, str(INSTALLER), **kwargs)

    def check_range(self, **kwargs):
        return self.run_command(sys.executable, str(CHECKER), "range", self.base, **kwargs)

    def bare_remote(self):
        remote = self.root / "remote.git"
        self.git("init", "--bare", str(remote))
        self.git("remote", "add", "origin", str(remote))
        return remote

    def test_original_blank_line_regression_is_rejected_by_commit_and_range(self):
        self.install()
        rejected = self.commit(BROKEN, check=False)
        self.assertNotEqual(rejected.returncode, 0)
        self.assertIn("no blank line", rejected.stderr)
        self.assertEqual(self.git("rev-parse", "HEAD").stdout.strip(), self.base)
        self.git("-c", "core.hooksPath=/dev/null", "commit", "--allow-empty", "-F", "-", input=BROKEN)
        rejected = self.check_range(check=False)
        self.assertNotEqual(rejected.returncode, 0)
        self.assertIn("needs exactly one", rejected.stderr)

    def test_joined_trailers_pass_commit_and_range_without_changing_message(self):
        self.install()
        self.commit(GOOD)
        self.assertEqual(self.git("show", "-s", "--format=%B").stdout.strip(), GOOD.strip())
        self.assertIn("checked 1 non-merge commits", self.check_range().stdout)

    def test_base_checker_validates_head_without_checker(self):
        checker_path = self.repo / "tooling/git-hooks/collie_check_layer_trailers.py"
        checker_path.parent.mkdir(parents=True)
        checker_path.write_bytes(CHECKER.read_bytes())
        self.git("add", str(checker_path))
        self.commit("Add checker\n\nLayer: tooling")
        base = self.git("rev-parse", "HEAD").stdout.strip()
        self.git("rm", str(checker_path))
        self.commit(GOOD)
        extracted = self.root / "trusted-checker.py"
        extracted.write_text(self.git("show", f"{base}:tooling/git-hooks/collie_check_layer_trailers.py").stdout)
        self.assertIn("checked 1 non-merge commits", self.run_command(sys.executable, str(extracted), "range", base, "HEAD").stdout)
        self.commit(BROKEN)
        rejected = self.run_command(sys.executable, str(extracted), "range", base, "HEAD", check=False)
        self.assertNotEqual(rejected.returncode, 0)
        self.assertIn("needs exactly one", rejected.stderr)

    def test_missing_duplicate_and_invalid_layers_are_rejected(self):
        self.install()
        for trailers in ("", "Layer: website\nLayer: website", "Layer: Website", "Layer: two words", "Layer: ../site", "Layer:"):
            with self.subTest(trailers=trailers):
                result = self.commit(f"Change\n\n{trailers}\n", check=False)
                self.assertNotEqual(result.returncode, 0)

    def test_editor_comments_are_handled_by_git(self):
        self.install()
        self.git("commit", "--allow-empty", "--cleanup=strip", "-F", "-", input=GOOD + "\n# Editor instructions\n")
        self.check_range()

    def test_amend_is_checked(self):
        self.install()
        self.commit(GOOD)
        before = self.git("rev-parse", "HEAD").stdout
        result = self.git("commit", "--amend", "-F", "-", input=BROKEN, check=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.git("rev-parse", "HEAD").stdout, before)

    def test_merges_are_exempt_but_their_commits_are_checked(self):
        self.git("switch", "-c", "topic")
        self.commit(GOOD)
        self.git("switch", "main")
        self.commit("Change\n\nLayer: tooling")
        self.install()
        self.git("merge", "--no-ff", "topic", "-m", "Merge topic without a Layer")
        self.assertIn("checked 2 non-merge commits", self.check_range().stdout)
        self.assertNotEqual(self.commit(BROKEN, check=False).returncode, 0)

    def test_completing_stopped_merge_is_exempt(self):
        self.git("switch", "-c", "topic")
        self.commit(GOOD)
        self.git("switch", "main")
        self.install()
        self.git("merge", "--no-ff", "--no-commit", "topic")
        self.git("commit", "-m", "Merge topic without a Layer")
        self.assertIn("checked 1 non-merge commits", self.check_range().stdout)

    def test_squash_merge_still_requires_a_trailer(self):
        self.git("switch", "-c", "topic")
        self.commit(GOOD)
        self.git("switch", "main")
        self.install()
        self.git("merge", "--squash", "topic")
        self.assertNotEqual(self.commit("Squashed topic", check=False).returncode, 0)

    def test_pre_push_blocks_bad_ancestor_even_when_head_is_valid(self):
        remote = self.bare_remote()
        self.commit(BROKEN)
        self.git("push", "origin", "HEAD:refs/heads/topic")  # Bad commit already published.
        previous = self.git("rev-parse", "HEAD").stdout.strip()
        self.commit(GOOD)
        self.install()
        result = self.git("push", "origin", "HEAD:refs/heads/topic", check=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("needs exactly one", result.stderr)
        self.assertEqual(self.git("--git-dir", str(remote), "rev-parse", "refs/heads/topic").stdout.strip(), previous)

    def test_new_branch_push_passes_and_branch_deletion_is_allowed(self):
        remote = self.bare_remote()
        self.install()
        self.commit(GOOD)
        self.git("push", "origin", "HEAD:refs/heads/topic")
        self.assertEqual(self.git("--git-dir", str(remote), "rev-parse", "refs/heads/topic").stdout, self.git("rev-parse", "HEAD").stdout)
        self.git("push", "origin", ":refs/heads/topic")
        self.assertNotEqual(self.git("--git-dir", str(remote), "show-ref", "--verify", "refs/heads/topic", check=False).returncode, 0)

    def test_new_branch_with_bad_history_is_not_published(self):
        remote = self.bare_remote()
        self.commit(BROKEN)
        self.install()
        result = self.git("push", "origin", "HEAD:refs/heads/topic", check=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("needs exactly one", result.stderr)
        self.assertNotEqual(self.git("--git-dir", str(remote), "show-ref", "--verify", "refs/heads/topic", check=False).returncode, 0)

    def test_missing_base_fails_closed_with_fetch_instruction(self):
        remote = self.bare_remote()
        self.install()
        self.commit(GOOD)
        self.git("update-ref", "-d", "refs/remotes/origin/main")
        result = self.git("push", "origin", "HEAD:refs/heads/topic", check=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("git fetch origin main", result.stderr)
        self.assertNotEqual(self.git("--git-dir", str(remote), "show-ref", "--verify", "refs/heads/topic", check=False).returncode, 0)

    def test_installer_refuses_existing_hooks_without_partial_install(self):
        hook = self.repo / ".git/hooks/pre-push"
        hook.parent.mkdir()
        hook.write_text("#!/bin/sh\n# Existing user hook\n", encoding="utf-8")
        result = self.install(check=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Refusing to overwrite", result.stderr)
        self.assertEqual(hook.read_text(), "#!/bin/sh\n# Existing user hook\n")
        self.assertFalse((hook.parent / "commit-msg").exists())

    def test_installer_refuses_empty_or_symlinked_hooks(self):
        hook = self.repo / ".git/hooks/commit-msg"
        hook.parent.mkdir()
        hook.touch()
        self.assertNotEqual(self.install(check=False).returncode, 0)
        hook.unlink()
        hook.symlink_to(self.root / "missing-user-hook")
        self.assertNotEqual(self.install(check=False).returncode, 0)
        self.assertTrue(hook.is_symlink())

    def test_installer_from_linked_worktree_covers_both_worktrees(self):
        worktree = self.root / "linked worktree"
        self.git("worktree", "add", "-b", "topic", str(worktree))
        self.run_command(sys.executable, str(INSTALLER), cwd=worktree)
        self.assertNotEqual(self.commit(BROKEN, check=False).returncode, 0)
        result = self.run_command(
            "git", "commit", "--allow-empty", "-F", "-", input=BROKEN,
            cwd=worktree, check=False
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("needs exactly one", result.stderr)

    def test_installer_can_be_rerun_and_supports_custom_hooks_path(self):
        hooks = self.root / "custom hooks"
        self.git("config", "core.hooksPath", str(hooks))
        self.install()
        self.install()
        self.assertTrue((hooks / "commit-msg").is_file())
        self.assertNotEqual(self.commit(BROKEN, check=False).returncode, 0)

    def test_installed_hooks_survive_source_files_disappearing(self):
        copied = self.root / "installer checkout"
        copied.mkdir()
        for name in ("install.py", "commit-msg", "pre-push", "collie_check_layer_trailers.py"):
            (copied / name).write_bytes((SCRIPTS / name).read_bytes())
        self.run_command(sys.executable, str(copied / "install.py"))
        for path in copied.iterdir():
            path.unlink()
        self.assertNotEqual(self.commit(BROKEN, check=False).returncode, 0)
        self.commit(GOOD)


if __name__ == "__main__":
    unittest.main()
