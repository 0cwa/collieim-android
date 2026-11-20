"""Edition rules of check_metadata.py, run against throwaway metadata trees."""
import contextlib
import importlib.util
import io
import os
import struct
import tempfile
import unittest
import zlib
from pathlib import Path

SCRIPT = Path(__file__).with_name("check_metadata.py").resolve()


def png(size):
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    header = struct.pack(">IIBBBBB", size, size, 8, 0, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IEND", b"")


class CheckMetadataTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        stable = self.root / "fastlane/metadata/android/en-US"
        (stable / "images").mkdir(parents=True)
        (stable / "title.txt").write_text("Collie")
        (stable / "short_description.txt").write_text("Private messaging")
        (stable / "full_description.txt").write_text("Collie is a private messenger.")
        (stable / "images/icon.png").write_bytes(png(512))
        beta = self.root / "fastlane/metadata/trailblazer-beta/en-US"
        (beta / "images").mkdir(parents=True)
        (beta / "title.txt").write_text("Collie TrailBlazer")
        (beta / "images/icon.png").write_bytes(png(512))
        (self.root / "gradle.properties").write_text("basePackageId=im.collie.app\n")

    def run_check(self, *args):
        spec = importlib.util.spec_from_file_location("check_metadata", SCRIPT)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        cwd = os.getcwd()
        os.chdir(self.root)
        try:
            with contextlib.redirect_stdout(io.StringIO()) as output:
                status = module.main(list(args))
        finally:
            os.chdir(cwd)
        return status, output.getvalue()

    def release(self, code="178201"):
        (self.root / "gradle.properties").write_text(f"basePackageId=im.collie.app\nreleaseVersionCode={code}\n")

    def changelog(self, edition, text="Release notes.", code="178201"):
        folder = "android" if edition == "stable" else "trailblazer-beta"
        path = self.root / f"fastlane/metadata/{folder}/en-US/changelogs/{code}.txt"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(text.encode())

    def test_no_release_needs_no_changelog_in_either_edition(self):
        self.assertEqual(self.run_check()[0], 0)
        self.assertEqual(self.run_check("--edition", "beta")[0], 0)

    def test_stable_release_requires_the_stable_changelog(self):
        self.release()
        status, output = self.run_check()
        self.assertEqual(status, 1)
        self.assertIn("android/en-US/changelogs/178201.txt: missing for the stable edition", output)
        self.changelog("stable")
        self.assertEqual(self.run_check()[0], 0)

    def test_beta_changelog_does_not_satisfy_stable(self):
        self.release()
        self.changelog("beta")
        self.assertEqual(self.run_check("--edition", "stable")[0], 1)

    def test_beta_release_requires_only_the_beta_changelog(self):
        self.release()
        status, output = self.run_check("--edition", "beta")
        self.assertEqual(status, 1)
        self.assertIn("trailblazer-beta/en-US/changelogs/178201.txt: missing for the beta edition", output)
        self.changelog("beta")
        self.assertEqual(self.run_check("--edition", "beta")[0], 0)

    def test_stable_changelog_does_not_satisfy_beta(self):
        self.release()
        self.changelog("stable")
        self.assertEqual(self.run_check("--edition", "beta")[0], 1)

    def test_beta_changelogs_keep_the_ascii_size_limit_in_every_edition(self):
        self.changelog("beta", "x" * 501, code="178200")
        for edition in ("stable", "beta"):
            status, output = self.run_check("--edition", edition)
            self.assertEqual(status, 1)
            self.assertIn("must be plain ASCII, at most 500 bytes", output)

    def test_stable_listing_stays_required_for_beta(self):
        (self.root / "fastlane/metadata/android/en-US/full_description.txt").unlink()
        self.assertEqual(self.run_check("--edition", "beta")[0], 1)

    def test_unknown_edition_is_rejected(self):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            self.run_check("--edition", "nightly")


if __name__ == "__main__":
    unittest.main()
