"""The preparation command works on a complete Git source without consuming invalid notes."""
from pathlib import Path
import runpy
import shutil
import subprocess
import sys
import tempfile
import unittest

STAMP = Path(__file__).with_name("stamp.py").resolve()
VERIFIER = STAMP.parents[1] / "reproducible-builds/verify-release-prep.py"
METADATA = STAMP.parents[1] / "fastlane/check_metadata.py"
VERSION = "8.36.3-1-beta2"
CODE = "178202"


class StampTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        for source, destination in (
            (STAMP, "tooling/collie-log/stamp.py"),
            (VERIFIER, "tooling/reproducible-builds/verify-release-prep.py"),
        ):
            target = self.root / destination
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
        (self.root / "tooling/gradle.properties.docker").write_text("ordinary=builder\n")
        (self.root / "gradle.properties").write_text("# Retain this comment.\nordinary=retained\ncollieBuildRevision=old\ncollieBuildTimestamp=1\n")
        self.git("init", "-q")
        self.git("config", "user.name", "Preparation fixture")
        self.git("config", "user.email", "fixture@example.invalid")
        self.commit()

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.root, text=True, stderr=subprocess.PIPE).strip()

    def commit(self):
        self.git("add", "-A")
        self.git("-c", "commit.gpgsign=false", "commit", "-qm", "fixture source")
        self.source = self.git("rev-parse", "HEAD")

    def pending(self, name, text):
        path = self.root / "tooling/collie-log/unreleased" / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return path

    def stamp(self, *extra, edition="beta"):
        return subprocess.run([
            sys.executable, str(self.root / "tooling/collie-log/stamp.py"), VERSION,
            "--edition", edition, "--version-code", CODE, "--date", "2026-11-24", *extra,
        ], cwd=self.root, text=True, capture_output=True)

    def snapshot(self):
        return {str(path.relative_to(self.root)): path.read_bytes()
                for path in self.root.rglob("*") if path.is_file() and ".git" not in path.relative_to(self.root).parts}

    def test_named_and_numbered_changes_feed_one_beta_record_and_changelog(self):
        self.pending("theme-everywhere.txt", "topic: look\nTheme changes appear immediately.\n")
        self.pending("7.txt", "topic: push\npr: #7\nPush setup uses Android’s picker.\n")
        self.commit()
        result = self.stamp("--tag", "trailblazer-v" + VERSION, "--highlights", "setup-and-push")
        self.assertEqual(result.returncode, 0, result.stderr)
        record = self.root / f"app/src/main/assets/collie-log/releases/beta/{VERSION}.txt"
        self.assertEqual(record.read_text(),
                         f"version: {VERSION}\ndate: 2026-11-24\ntag: trailblazer-v{VERSION}\nhighlights: setup-and-push\n\n"
                         "topic: push\npr: 7\nPush setup uses Android’s picker.\n\n"
                         "topic: look\nTheme changes appear immediately.\n")
        draft = self.root / f"fastlane/metadata/trailblazer-beta/en-US/changelogs/{CODE}.txt"
        self.assertEqual(draft.read_text(), "- Push setup uses Android's picker.\n- Theme changes appear immediately.\n")
        self.assertFalse((self.root / "fastlane/metadata/android").exists())
        self.assertEqual(list((self.root / "tooling/collie-log/unreleased").iterdir()), [])
        properties = (self.root / "gradle.properties").read_text()
        self.assertTrue(properties.startswith("# Retain this comment.\nordinary=retained\n"))
        self.assertIn("collieBuildRevision=" + self.source + "\n", properties)
        self.assertIn("collieBuildTimestamp=" + self.git("show", "-s", "--format=%ct", self.source) + "\n", properties)
        self.commit()
        checked = subprocess.run([sys.executable, str(self.root / "tooling/reproducible-builds/verify-release-prep.py"),
                                  "--edition", "beta", "--build-state"], cwd=self.root, text=True, capture_output=True)
        self.assertEqual(checked.returncode, 0, checked.stderr)
        self.assertEqual(checked.stdout, "true\n")

    def test_empty_maintenance_release_and_draft_are_valid(self):
        result = self.stamp(edition="stable")
        self.assertEqual(result.returncode, 0, result.stderr)
        record = self.root / f"app/src/main/assets/collie-log/releases/stable/{VERSION}.txt"
        self.assertEqual(record.read_text(), f"version: {VERSION}\ndate: 2026-11-24\n")
        changelogs = self.root / "fastlane/metadata/android/en-US/changelogs"
        self.assertEqual((changelogs / f"{CODE}.txt").read_bytes(), b"")
        metadata = runpy.run_path(str(METADATA))
        metadata["check_locale"](changelogs.parent)
        self.assertEqual(metadata["errors"], [])
        self.commit()
        checked = subprocess.run([sys.executable, str(self.root / "tooling/reproducible-builds/verify-release-prep.py"),
                                  "--build-state"], cwd=self.root, text=True, capture_output=True)
        self.assertEqual(checked.returncode, 0, checked.stderr)
        self.assertEqual(checked.stdout, "true\n")

    def test_same_source_and_arguments_produce_identical_preparation(self):
        self.pending("z.txt", "topic: fixes\npr: 9\nNine.\n")
        self.pending("a.txt", "topic: fixes\npr: 12\nTwelve.\n")
        self.pending("m.txt", "topic: setup\nSetup first.\n")
        self.commit()
        self.assertEqual(self.stamp().returncode, 0)
        expected = self.snapshot()
        self.git("reset", "--hard", self.source)
        self.git("clean", "-fd")
        self.assertEqual(self.stamp().returncode, 0)
        self.assertEqual(self.snapshot(), expected)

    def test_invalid_fragment_does_not_write_or_consume_anything(self):
        invalid = ("", "topic: unknown\nA change.\n", "topic: fixes\ntopic: fixes\nA change.\n",
                   "pr: zero\nA change.\n", "x" * 121, "😀" * 61,
                   "version: " + VERSION + "\nA change.\n")
        for text in invalid:
            with self.subTest(text=text):
                path = self.pending("named-fragment.txt", text)
                self.commit()
                before = self.snapshot()
                result = self.stamp()
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(self.snapshot(), before)
                self.assertEqual(path.read_text(), text)

    def test_existing_record_or_changelog_causes_no_partial_preparation(self):
        self.pending("7.txt", "A change.\n")
        for name in (f"app/src/main/assets/collie-log/releases/beta/{VERSION}.txt",
                     f"fastlane/metadata/trailblazer-beta/en-US/changelogs/{CODE}.txt"):
            with self.subTest(name=name):
                target = self.root / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text("keep this\n")
                self.commit()
                before = self.snapshot()
                result = self.stamp()
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("already exists", result.stderr)
                self.assertEqual(self.snapshot(), before)
                target.unlink()

    def test_tag_must_match_the_selected_product_and_version(self):
        before = self.snapshot()
        for tag in ("v" + VERSION, "trailblazer-v8.36.3-1-beta1", "https://example.com", "../other"):
            with self.subTest(tag=tag):
                self.assertNotEqual(self.stamp("--tag", tag).returncode, 0)
                self.assertEqual(self.snapshot(), before)

    def test_existing_current_version_under_another_filename_stops_before_mutation(self):
        alias = self.root / "app/src/main/assets/collie-log/releases/beta/old-alias.txt"
        alias.parent.mkdir(parents=True)
        alias.write_text(f"version: {VERSION}\ndate: 2026-11-23\n")
        self.pending("7.txt", "A change.\n")
        self.commit()
        before = self.snapshot()
        result = self.stamp()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("already has a record", result.stderr)
        self.assertEqual(self.snapshot(), before)

    def test_same_version_in_the_other_product_does_not_block_preparation(self):
        stable = self.root / f"app/src/main/assets/collie-log/releases/stable/{VERSION}.txt"
        stable.parent.mkdir(parents=True)
        original = f"version: {VERSION}\ndate: 2026-11-23\ntag: v{VERSION}\n"
        stable.write_text(original)
        self.commit()
        result = self.stamp("--tag", "trailblazer-v" + VERSION)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(stable.read_text(), original)
        self.commit()
        checked = subprocess.run([sys.executable, str(self.root / "tooling/reproducible-builds/verify-release-prep.py"),
                                  "--edition", "beta", "--build-state"], cwd=self.root, text=True, capture_output=True)
        self.assertEqual(checked.returncode, 0, checked.stderr)
        self.assertEqual(checked.stdout, "true\n")

    def test_invalid_date_is_rejected_before_changes(self):
        before = self.snapshot()
        self.assertNotEqual(self.stamp("--date", "2026-02-30").returncode, 0)
        self.assertEqual(self.snapshot(), before)

    def test_legacy_pending_assets_cannot_be_hidden_by_migration(self):
        old = self.root / "app/src/main/assets/collie-log/unreleased/7.txt"
        old.parent.mkdir(parents=True)
        old.write_text("A change from an older PR.\n")
        self.commit()
        before = self.snapshot()
        result = self.stamp()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("legacy pending assets", result.stderr)
        self.assertEqual(self.snapshot(), before)

    def test_dirty_source_is_not_pinned(self):
        (self.root / "gradle.properties").write_text("ordinary=edited\n")
        before = self.snapshot()
        result = self.stamp()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("not clean", result.stderr)
        self.assertEqual(self.snapshot(), before)

    def test_symlinked_output_parent_is_rejected_before_writing(self):
        outside = tempfile.TemporaryDirectory()
        self.addCleanup(outside.cleanup)
        parent = self.root / "app/src/main/assets/collie-log/releases"
        parent.mkdir(parents=True)
        (parent / "beta").symlink_to(outside.name, target_is_directory=True)
        self.commit()
        before = self.snapshot()
        result = self.stamp()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("symlinked", result.stderr)
        self.assertEqual(self.snapshot(), before)
        self.assertEqual(list(Path(outside.name).iterdir()), [])


if __name__ == "__main__":
    unittest.main()
