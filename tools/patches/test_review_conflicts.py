import gzip
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("review_conflicts.py")
spec = importlib.util.spec_from_file_location("conflict_review", SCRIPT)
review = importlib.util.module_from_spec(spec)
spec.loader.exec_module(review)


class ChoiceValidationTest(unittest.TestCase):
    def test_rename_labeled_markers_have_ordered_exact_sides(self):
        original = "".join(
            f"context {i}\n<<<<<<< MOLLY:old/{i}\nmolly {i}\n=======\nsignal {i}\n>>>>>>> SIGNAL:new/{i}\n"
            for i in range(13)
        )
        choices = [
            {"ordinal": i + 1, "side": "old_molly" if i % 2 == 0 else "new_signal"}
            for i in range(13)
        ]
        expected = "".join(
            f"context {i}\n" + (f"molly {i}\n" if i % 2 == 0 else f"signal {i}\n")
            for i in range(13)
        )
        self.assertEqual(review.apply_choices(original, choices), expected)
        for bad in (
            [{"ordinal": 1, "side": "unknown"}] + choices[1:],
            [{"ordinal": 2, "side": "old_molly"}] + choices[1:],
            choices[:-1],
        ):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                review.apply_choices(original, bad)

    def test_preimage_mismatch_rejected(self):
        expected = {"mode": "100644", "oid": "a" * 40}
        review.validate_preimage("100644 " + ("a" * 40) + " 0\tfile", expected, "file")
        for current in (
            "100755 " + ("a" * 40) + " 0\tfile",
            "100644 " + ("b" * 40) + " 0\tfile",
            "",
        ):
            with self.subTest(current=current), self.assertRaises(ValueError):
                review.validate_preimage(current, expected, "file")


class ReplayIntegrationTest(unittest.TestCase):
    def test_real_merge_and_cached_residual_reconstruct_target(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            repo = root / "repo"
            repo.mkdir()
            out = root / "out"
            env = os.environ.copy()
            env.update(
                GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL="/dev/null", LC_ALL="C"
            )

            def git(*args, check=True):
                return (
                    subprocess.run(
                        ["git", "-C", str(repo), *args],
                        env=env,
                        stdout=subprocess.PIPE,
                        stderr=subprocess.PIPE,
                        check=check,
                    )
                    .stdout.decode()
                    .strip()
                )

            git("init", "-q")
            git("config", "user.name", "Review Test")
            git("config", "user.email", "review@example.invalid")
            (repo / "file.txt").write_text("start\nbase\nend\n")
            (repo / "deleted.txt").write_text("base\n")
            git("add", ".")
            git("commit", "-qm", "base")
            base = git("rev-parse", "HEAD")
            git("checkout", "-qb", "MOLLY")
            (repo / "file.txt").write_text("start\nmolly\nend\n")
            (repo / "deleted.txt").write_text("molly\n")
            git("commit", "-qam", "molly")
            molly = git("rev-parse", "HEAD")
            git("checkout", "-qb", "SIGNAL", base)
            (repo / "file.txt").write_text("start\nsignal\nend\n")
            (repo / "deleted.txt").unlink()
            git("add", "-A")
            git("commit", "-qm", "signal")
            signal = git("rev-parse", "HEAD")
            merge = git(
                "-c",
                "merge.conflictStyle=merge",
                "-c",
                "merge.renames=true",
                "-c",
                "merge.renameLimit=7000",
                "-c",
                "merge.directoryRenames=conflict",
                "-c",
                "merge.renormalize=false",
                "-c",
                "core.autocrlf=false",
                "-c",
                "core.attributesFile=/dev/null",
                "-c",
                "core.hooksPath=/dev/null",
                "merge-tree",
                "--write-tree",
                "--messages",
                "-Xfind-renames=50%",
                "--merge-base=" + base,
                "MOLLY",
                "SIGNAL",
                check=False,
            )
            mechanical = merge.splitlines()[0]
            (repo / "file.txt").write_text("start\nmolly\nfinal residual\nend\n")
            git("commit", "-qam", "final")
            target = git("rev-parse", "HEAD^{tree}")
            subprocess.run(
                [
                    sys.executable,
                    str(SCRIPT),
                    "--repo",
                    str(repo),
                    "--mechanical-tree",
                    mechanical,
                    "--old-signal",
                    base,
                    "--molly-source",
                    molly,
                    "--new-signal",
                    signal,
                    "--target-tree",
                    target,
                    "--out",
                    str(out),
                ],
                env=env,
                check=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )
            summary = json.loads((out / "summary.json").read_text())
            manifest = json.loads((out / "manifest.json").read_text())
            data = json.loads(gzip.decompress((out / "review.json.gz").read_bytes()))
            self.assertEqual(summary["applied_tree"], target)
            self.assertTrue(summary["full_tree_path_mode_oid_equal"])
            self.assertEqual(summary["selected_regions"], 1)
            self.assertEqual(data["files"][0]["regions"][0]["side"], "old_molly")
            deleted = next(
                item for item in manifest["files"] if item["path"] == "deleted.txt"
            )
            self.assertEqual(deleted["category"], "target-absent")
            self.assertIsNone(deleted["target"])
            self.assertTrue((out / "residual.patch.gz").is_file())
            self.assertTrue((out / "view.html").is_file())
            # Independently assemble this fixture's known Molly choice in a new index,
            # then apply the produced patch rather than trusting the report's claim.
            private_env = dict(env, GIT_INDEX_FILE=str(root / "independent-index"))

            def private_git(*args, input=None):
                return (
                    subprocess.run(
                        ["git", "-C", str(repo), *args],
                        env=private_env,
                        input=input,
                        stdout=subprocess.PIPE,
                        stderr=subprocess.PIPE,
                        check=True,
                    )
                    .stdout.decode()
                    .strip()
                )

            private_git("read-tree", mechanical)
            picked = private_git(
                "hash-object", "-w", "--stdin", input=b"start\nmolly\nend\n"
            )
            private_git("update-index", "--cacheinfo", "100644", picked, "file.txt")
            private_git(
                "apply",
                "--cached",
                "--binary",
                "--whitespace=nowarn",
                "-",
                input=gzip.decompress((out / "residual.patch.gz").read_bytes()),
            )
            self.assertEqual(private_git("write-tree"), target)


if __name__ == "__main__":
    unittest.main()
