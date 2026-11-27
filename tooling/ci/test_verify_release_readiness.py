"""Exercise the release boundary with missing, stale and failed CI evidence."""
from contextlib import redirect_stderr, redirect_stdout
import io
import json
import os
from pathlib import Path
import re
import subprocess
from tempfile import TemporaryDirectory
from textwrap import dedent
import unittest
from unittest.mock import patch

import verify_release_readiness as gate

SHA = "a" * 40
MAIN_TIP = "b" * 40
REPO = "0cwa/collieim-android"
WORKFLOW_ID = 202
MAIN_ENDPOINT = f"repos/{REPO}/git/ref/heads/main"
COMPARE_ENDPOINT = f"repos/{REPO}/compare/{SHA}...{MAIN_TIP}?per_page=1"
WORKFLOW_ENDPOINT = f"repos/{REPO}/actions/workflows?per_page=100"
RUNS_ENDPOINT = f"repos/{REPO}/actions/runs?head_sha={SHA}&per_page=100"
JOBS_ENDPOINT = f"repos/{REPO}/actions/runs/100/attempts/2/jobs?per_page=100"


def run_record(**changes):
    return {"id": 100, "run_attempt": 2, "path": gate.WORKFLOW_PATH,
            "head_sha": SHA, "head_branch": "main", "event": "push", "workflow_id": WORKFLOW_ID,
            "repository": {"full_name": REPO}, "head_repository": {"full_name": REPO},
            "status": "completed", "conclusion": "success", **changes}


def jobs():
    return [{"id": index, "name": name, "run_id": 100, "head_sha": SHA,
              "run_attempt": 2, "status": "completed", "conclusion": "success"}
            for index, name in enumerate(gate.REQUIRED_JOBS, 1)]


def page(key, entries):
    return [{"total_count": len(entries), key: entries}]


def workflow_record(**changes):
    return {"id": WORKFLOW_ID, "path": gate.WORKFLOW_PATH, **changes}


def main_ref(sha=SHA):
    return {"ref": "refs/heads/main", "object": {"type": "commit", "sha": sha}}


class ReleaseReadinessTest(unittest.TestCase):
    def test_success_uses_registered_workflow_and_rechecks_current_attempt(self):
        for event in ("push", "workflow_dispatch"):
            with self.subTest(event=event):
                reader = self.reader({
                    MAIN_ENDPOINT: main_ref(),
                    WORKFLOW_ENDPOINT: [
                        {"total_count": 2, "workflows": [workflow_record(id=9, path=".github/workflows/other.yml")]},
                        {"total_count": 2, "workflows": [workflow_record()]},
                    ],
                    RUNS_ENDPOINT: page("workflow_runs", [run_record(event=event)]),
                    JOBS_ENDPOINT: page("jobs", jobs()),
                })
                evidence = gate.verify(REPO, SHA, reader=reader)
                self.assertEqual(evidence["source_commit"], SHA)
                self.assertEqual(evidence["source_branch"], "main")
                self.assertEqual(evidence["workflow_path"], gate.WORKFLOW_PATH)
                self.assertEqual(evidence["workflow_id"], WORKFLOW_ID)
                self.assertEqual(evidence["workflow_event"], event)
                self.assertIsNone(evidence["trusted_main"])
                self.assertEqual(evidence["run_attempt"], 2)
                self.assertEqual([job["name"] for job in evidence["jobs"]], list(gate.REQUIRED_JOBS))
                self.assertEqual(reader.calls, [MAIN_ENDPOINT, WORKFLOW_ENDPOINT, RUNS_ENDPOINT, JOBS_ENDPOINT,
                                                WORKFLOW_ENDPOINT, RUNS_ENDPOINT])

    def test_ancestor_of_current_main_tip_can_authorize_release(self):
        reader = self.reader({
            MAIN_ENDPOINT: main_ref(MAIN_TIP),
            COMPARE_ENDPOINT: {"status": "ahead", "merge_base_commit": {"sha": SHA}},
            WORKFLOW_ENDPOINT: page("workflows", [workflow_record()]),
            RUNS_ENDPOINT: page("workflow_runs", [run_record()]),
            JOBS_ENDPOINT: page("jobs", jobs()),
        })
        self.assertEqual(gate.verify(REPO, SHA, reader=reader)["source_commit"], SHA)
        self.assertEqual(reader.calls[:2], [MAIN_ENDPOINT, COMPARE_ENDPOINT])

    def test_successful_dispatch_named_main_outside_main_history_fails(self):
        for status, merge_base in (("diverged", "c" * 40), ("behind", MAIN_TIP), ("ahead", "c" * 40)):
            with self.subTest(status=status, merge_base=merge_base):
                reader = self.reader({
                    MAIN_ENDPOINT: main_ref(MAIN_TIP),
                    COMPARE_ENDPOINT: {"status": status, "merge_base_commit": {"sha": merge_base}},
                    WORKFLOW_ENDPOINT: page("workflows", [workflow_record()]),
                    RUNS_ENDPOINT: page("workflow_runs", [run_record(event="workflow_dispatch", head_branch="main")]),
                    JOBS_ENDPOINT: page("jobs", jobs()),
                })
                with self.assertRaisesRegex(gate.Failure, "outside refs/heads/main history"):
                    gate.verify(REPO, SHA, reader=reader)
                self.assertEqual(reader.calls, [MAIN_ENDPOINT, COMPARE_ENDPOINT])

    def test_tag_ref_cannot_substitute_for_main_ref(self):
        reader = self.reader({MAIN_ENDPOINT: {**main_ref(), "ref": "refs/tags/main"}})
        with self.assertRaisesRegex(gate.Failure, "Invalid main ref identity"):
            gate.verify(REPO, SHA, reader=reader)

    def reader(self, responses):
        def reader(endpoint, *, paginate):
            self.assertEqual(paginate, "/actions/" in endpoint)
            reader.calls.append(endpoint)
            return responses[endpoint]

        reader.calls = []
        return reader

    def test_missing_ambiguous_or_incomplete_workflow_listing_fails(self):
        for listing in (
            page("workflows", []),
            page("workflows", [workflow_record(path=".github/workflows/renamed.yml")]),
            page("workflows", [workflow_record(), workflow_record(id=303)]),
            [{"total_count": 2, "workflows": [workflow_record()]}],
        ):
            with self.subTest(listing=listing), self.assertRaises(gate.Failure):
                gate.verify(REPO, SHA, reader=self.reader({MAIN_ENDPOINT: main_ref(), WORKFLOW_ENDPOINT: listing}))

    def test_foreign_stale_or_untrusted_runs_cannot_authorize_release(self):
        for changes in ({"head_sha": "b" * 40}, {"event": "pull_request"},
                        {"head_branch": "topic"}, {"head_branch": "refs/heads/main"},
                        {"path": ".github/workflows/other.yml"}, {"workflow_id": 7},
                        {"repository": {"full_name": "other/repo"}},
                        {"head_repository": {"full_name": "fork/collieim-android"}}):
            with self.subTest(changes=changes), self.assertRaises(gate.Failure):
                gate.select_run([run_record(**changes)], REPO, SHA, "main", WORKFLOW_ID)

    def test_newer_failure_or_pending_run_cannot_fall_back_to_old_success(self):
        for changes in ({"conclusion": "failure"}, {"status": "in_progress", "conclusion": None},
                        {"conclusion": "cancelled"}, {"conclusion": "skipped"}):
            with self.subTest(changes=changes), self.assertRaises(gate.Failure):
                gate.select_run([run_record(id=101, **changes), run_record()], REPO, SHA, "main", WORKFLOW_ID)

    def test_missing_or_duplicate_job_fails(self):
        for records in (jobs()[:-1], jobs() + [jobs()[0]]):
            with self.assertRaises(gate.Failure):
                gate.verify_jobs(run_record(), records, SHA)

    def test_skipped_neutral_failed_and_unfinished_required_jobs_fail(self):
        for name in gate.REQUIRED_JOBS:
            for conclusion in ("skipped", "neutral", "failure", "cancelled", None):
                records = jobs()
                next(record for record in records if record["name"] == name)["conclusion"] = conclusion
                with self.subTest(name=name, conclusion=conclusion), self.assertRaises(gate.Failure):
                    gate.verify_jobs(run_record(), records, SHA)

    def test_success_from_another_commit_run_or_attempt_fails(self):
        for field, value in (("head_sha", "b" * 40), ("run_id", 99), ("run_attempt", 1)):
            records = jobs()
            records[0][field] = value
            with self.subTest(field=field), self.assertRaises(gate.Failure):
                gate.verify_jobs(run_record(), records, SHA)

    def test_all_pages_are_required_and_duplicate_ids_rejected(self):
        pages = [{"total_count": 3, "jobs": jobs()[:1]}, {"total_count": 3, "jobs": jobs()[1:]}]
        self.assertEqual(len(gate.flatten_pages(pages, "jobs")), 3)
        for invalid in (pages[:1], [{"total_count": 2, "jobs": [jobs()[0], jobs()[0]]}],
                        [], [{"jobs": jobs()}], [{"total_count": 1, "jobs": [None]}]):
            with self.subTest(invalid=invalid), self.assertRaises(gate.Failure):
                gate.flatten_pages(invalid, "jobs")

    def test_rerun_during_verification_fails(self):
        for latest in (
            run_record(run_attempt=3),
            run_record(id=101),
            run_record(event="workflow_dispatch"),
            run_record(status="in_progress", conclusion=None),
        ):
            responses = iter([main_ref(), page("workflows", [workflow_record()]),
                              page("workflow_runs", [run_record()]),
                              page("jobs", jobs()),
                              page("workflows", [workflow_record()]),
                              page("workflow_runs", [latest])])
            with self.subTest(latest=latest), self.assertRaises(gate.Failure):
                gate.verify(REPO, SHA, reader=lambda *args, **kwargs: next(responses))

    def test_workflow_recreation_during_verification_fails(self):
        responses = iter([main_ref(), page("workflows", [workflow_record()]),
                          page("workflow_runs", [run_record()]),
                          page("jobs", jobs()),
                          page("workflows", [workflow_record(id=303)])])
        with self.assertRaisesRegex(gate.Failure, "CI workflow changed during verification"):
            gate.verify(REPO, SHA, reader=lambda *args, **kwargs: next(responses))

    def test_job_without_attempt_cannot_authorize_release(self):
        records = jobs()
        records[0].pop("run_attempt")
        with self.assertRaisesRegex(gate.Failure, "different run attempt"):
            gate.verify_jobs(run_record(), records, SHA)

    def test_main_cli_accepts_legacy_trusted_main_argument(self):
        with TemporaryDirectory() as directory:
            report = Path(directory) / "ci.json"
            arguments = ["verify_release_readiness.py", "--repository", REPO, "--sha", SHA,
                         "--source-branch", "main", "--trusted-main", "ignored", "--report", str(report)]
            evidence = {"run_url": "https://github.com/0cwa/collieim-android/actions/runs/100"}
            with patch("sys.argv", arguments), patch.object(gate, "verify", return_value=evidence) as verify:
                with redirect_stdout(io.StringIO()):
                    self.assertEqual(gate.main(), 0)
                verify.assert_called_once_with(REPO, SHA, "main")
                self.assertEqual(json.loads(report.read_text()), evidence)
                arguments[arguments.index("--source-branch") + 1] = "release/stable/8.35"
                with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
                    gate.main()
                self.assertEqual(error.exception.code, 2)

    def test_input_validation_precedes_api_calls(self):
        def forbidden(*args, **kwargs):
            self.fail("invalid input reached GitHub")
        for repo, sha, branch in (("../../repos", SHA, "main"), (REPO, "main", "main"),
                                  (REPO, SHA.upper(), "main"), (REPO, SHA, "release/stable/8.35"),
                                  (REPO, SHA, "refs/heads/main")):
            with self.subTest(repo=repo, sha=sha, branch=branch), self.assertRaises(gate.Failure):
                gate.verify(repo, sha, branch, reader=forbidden)


class BuildInputGateTest(unittest.TestCase):
    def test_detects_build_paths_and_always_builds_non_pr_events(self):
        workflow = (Path(__file__).resolve().parents[2] / gate.WORKFLOW_PATH).read_text()
        step = re.search(r"(?ms)^        id: detect\n.*?^        run: \|\n((?:^          [^\n]*\n)+)", workflow)
        self.assertIsNotNone(step, "Missing build input detection script")
        script = dedent(step[1])

        with TemporaryDirectory() as directory:
            root = Path(directory)

            def git(*args):
                return subprocess.check_output(
                    ["git", "-c", "user.name=CI fixture", "-c", "user.email=ci@example.invalid",
                     "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null", *args],
                    cwd=root, text=True,
                ).strip()

            def detect(event, base):
                output = root / "output"
                output.write_text("")
                subprocess.run(["bash", "-eo", "pipefail", "-c", script], cwd=root, check=True,
                               env={**os.environ, "BASE": base, "GITHUB_EVENT_NAME": event,
                                    "GITHUB_OUTPUT": str(output)})
                return output.read_text()

            for event in ("push", "workflow_dispatch"):
                with self.subTest(event=event):
                    self.assertEqual(detect(event, ""), "build_changed=true\n")

            git("init", "-q", "-b", "main")
            git("commit", "--allow-empty", "-qm", "base")
            base = git("rev-parse", "HEAD")
            for path, build_changed in (
                ("gradlew", "true"), ("gradlew.bat", "true"), ("lib/contacts/build.gradle", "true"),
                ("app/src/main/AndroidManifest.xml", "true"), ("lib/image-editor/consumer-rules.pro", "true"),
                ("app/proguard/extra.cfg", "true"), ("app/shrinker-config-r8.txt", "true"),
                (".gitattributes", "true"), (".github/actions/disk-cleanup/action.yml", "true"),
                ("gradle/libs.versions.toml", "true"), ("settings.gradle.kts", "true"),
                ("app/src/beta/branding/trailblazer.svg", "true"),
                ("app/src/prodWebsiteBeta/kotlin/Example.kt", "true"),
                ("docs/contributing.md", "false"), ("app/src/main/Example.kt", "false"),
            ):
                with self.subTest(path=path):
                    git("reset", "--hard", "-q", base)
                    file = root / path
                    file.parent.mkdir(parents=True, exist_ok=True)
                    file.touch()
                    git("add", "--", path)
                    git("commit", "-qm", "change build input")
                    self.assertEqual(detect("pull_request", base), f"build_changed={build_changed}\n")

            git("reset", "--hard", "-q", base)
            git("switch", "-qc", "pr")
            (root / "README.md").touch()
            git("add", "README.md")
            git("commit", "-qm", "change documentation on PR")
            git("switch", "-q", "main")
            (root / "gradle.properties").touch()
            git("add", "gradle.properties")
            git("commit", "-qm", "change build input on main")
            git("merge", "-q", "--no-ff", "-m", "synthetic PR merge", "pr")
            self.assertEqual(detect("pull_request", base), "build_changed=false\n")


if __name__ == "__main__":
    unittest.main()
