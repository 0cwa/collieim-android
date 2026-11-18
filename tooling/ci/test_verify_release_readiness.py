"""Exercise the release boundary with missing, stale and failed CI evidence."""
import unittest
import json
from pathlib import Path

import verify_release_readiness as gate

SHA = "a" * 40
REPO = "0cwa/collieim-android"


def run_record(**changes):
    return {"id": 100, "run_attempt": 2, "path": gate.WORKFLOW_PATH,
            "head_sha": SHA, "head_branch": "main", "event": "push", "workflow_id": gate.WORKFLOW_ID,
            "repository": {"full_name": REPO}, "head_repository": {"full_name": REPO},
            "status": "completed", "conclusion": "success", **changes}


def jobs():
    return [{"id": index, "name": name, "run_id": 100, "head_sha": SHA,
             "run_attempt": 2, "status": "completed", "conclusion": "success"}
            for index, name in enumerate(gate.REQUIRED_JOBS, 1)]


def page(key, entries):
    return [{"total_count": len(entries), key: entries}]


class ReleaseReadinessTest(unittest.TestCase):
    def test_success_uses_exact_attempt_and_rechecks_current_state(self):
        calls = []

        def reader(endpoint, *, paginate):
            calls.append(endpoint)
            self.assertTrue(paginate)
            return page("jobs", jobs()) if "/attempts/2/jobs?" in endpoint else page("workflow_runs", [run_record()])

        evidence = gate.verify(REPO, SHA, reader=reader)
        self.assertEqual(evidence["source_commit"], SHA)
        self.assertEqual(evidence["run_attempt"], 2)
        self.assertEqual(len(evidence["jobs"]), 3)
        self.assertEqual(calls[0], calls[2])
        self.assertIn("/attempts/2/jobs?", calls[1])

    def test_foreign_stale_or_untrusted_runs_cannot_authorize_release(self):
        for changes in ({"head_sha": "b" * 40}, {"event": "pull_request"},
                        {"head_branch": "topic"}, {"path": ".github/workflows/other.yml"}, {"workflow_id": 7},
                        {"repository": {"full_name": "other/repo"}},
                        {"head_repository": {"full_name": "fork/collieim-android"}}):
            with self.subTest(changes=changes), self.assertRaises(gate.Failure):
                gate.select_run([run_record(**changes)], REPO, SHA, "main")

    def test_newer_failure_or_pending_run_cannot_fall_back_to_old_success(self):
        for changes in ({"conclusion": "failure"}, {"status": "in_progress", "conclusion": None},
                        {"conclusion": "cancelled"}, {"conclusion": "skipped"}):
            with self.subTest(changes=changes), self.assertRaises(gate.Failure):
                gate.select_run([run_record(), run_record(id=101, **changes)], REPO, SHA, "main")

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
        for latest in (run_record(run_attempt=3), run_record(id=101), run_record(event="workflow_dispatch")):
            responses = iter([page("workflow_runs", [run_record()]), page("jobs", jobs()),
                              page("workflow_runs", [latest])])
            with self.subTest(latest=latest), self.assertRaises(gate.Failure):
                gate.verify(REPO, SHA, reader=lambda *args, **kwargs: next(responses))

    def test_stable_ref_has_no_wildcard_or_branch_fallback(self):
        stable = gate.MAINTENANCE_BRANCH
        stable_run = run_record(head_branch=stable, event="workflow_dispatch")
        self.assertEqual(gate.select_run([run_record(), stable_run], REPO, SHA, stable), stable_run)
        for branch in ("release/stable/8.36", "release/stable/*", "refs/heads/" + stable):
            with self.subTest(branch=branch), self.assertRaises(gate.Failure):
                gate.select_run([run_record(head_branch=branch)], REPO, SHA, branch)
        with self.assertRaises(gate.Failure):
            gate.select_run([run_record()], REPO, SHA, stable)

    def test_job_without_attempt_cannot_authorize_release(self):
        records = jobs()
        records[0].pop("run_attempt")
        with self.assertRaisesRegex(gate.Failure, "different run attempt"):
            gate.verify_jobs(run_record(), records, SHA)

    def test_maintenance_binding_compares_objects_and_protected_main_history(self):
        policy = "b" * 40
        live_main = "c" * 40
        prefix = f"repos/{REPO}"
        api_responses = {
            f"{prefix}/git/ref/heads/main": {"object": {"sha": live_main}},
            f"{prefix}/compare/{policy}...{live_main}": {"status": "ahead", "merge_base_commit": {"sha": policy}},
        }
        candidate_inputs = {}
        for number, commit in enumerate((SHA, policy), 1):
            directories = {"": f"{number:040x}"}
            api_responses[f"{prefix}/git/commits/{commit}"] = {"sha": commit, "tree": {"sha": directories[""]}}
            by_parent = {"": []}
            for index, path in enumerate(gate.TRUSTED_CI_PATHS, 20):
                parts = path.split("/")
                folder = ""
                for part in parts[:-1]:
                    child = f"{folder}/{part}" if folder else part
                    if child not in directories:
                        oid = f"{number * 100 + len(directories):040x}"
                        directories[child] = oid
                        by_parent[folder].append({"path": part, "mode": "040000", "type": "tree", "sha": oid})
                        by_parent[child] = []
                    folder = child
                entry = {"path": parts[-1], "mode": "100644", "type": "blob", "sha": f"{index:040x}"}
                if path in (".github/actions/disk-cleanup", "tooling/ci", "tooling/git-hooks", "tooling/host-tests",
                            "tooling/reproducible-builds", "buildSrc", "gradle/wrapper"):
                    entry.update(mode="040000", type="tree")
                by_parent[folder].append(entry)
                if commit == SHA:
                    candidate_inputs[path] = entry
            for parent, values in by_parent.items():
                oid = directories[parent]
                api_responses[f"{prefix}/git/trees/{oid}"] = {"sha": oid, "truncated": False, "tree": values}
        calls = []

        def reader(endpoint, **kwargs):
            calls.append(endpoint)
            if "/actions/runs?" in endpoint:
                return page("workflow_runs", [run_record(head_branch=gate.MAINTENANCE_BRANCH)])
            if "/attempts/2/jobs?" in endpoint:
                return page("jobs", jobs())
            return api_responses[endpoint]

        result = gate.verify(REPO, SHA, gate.MAINTENANCE_BRANCH, reader, trusted_main=policy)
        self.assertEqual((result["source_branch"], result["workflow_event"], result["trusted_main"]),
                         (gate.MAINTENANCE_BRANCH, "push", policy))
        for path in (gate.WORKFLOW_PATH, ".github/scripts/check-unit-test-results.py",
                     ".github/ci-required-tests.json", ".github/ci-required-tests-stable-8.35.json",
                     "gradlew", "gradle/wrapper"):
            with self.subTest(replaced_input=path):
                entry = candidate_inputs[path]
                original = entry["sha"]
                entry["sha"] = "d" * 40
                calls.clear()
                with self.assertRaisesRegex(gate.Failure, f"differs from trusted main: {path}"):
                    gate.verify(REPO, SHA, gate.MAINTENANCE_BRANCH, reader, trusted_main=policy)
                self.assertFalse(any("/actions/" in endpoint for endpoint in calls))
                entry["sha"] = original
        api_responses[f"{prefix}/compare/{policy}...{live_main}"]["status"] = "diverged"
        with self.assertRaisesRegex(gate.Failure, "outside protected main"):
            gate.verify(REPO, SHA, gate.MAINTENANCE_BRANCH, reader, trusted_main=policy)
        with self.assertRaisesRegex(gate.Failure, "requires an immutable"):
            gate.verify(REPO, SHA, gate.MAINTENANCE_BRANCH, reader)

    def test_stable_inventory_only_changes_suite_requirements(self):
        root = Path(__file__).resolve().parents[2]
        default = json.loads((root / ".github/ci-required-tests.json").read_text())
        stable = json.loads((root / ".github/ci-required-tests-stable-8.35.json").read_text())
        self.assertEqual(stable["required_tasks"], default["required_tasks"])
        self.assertEqual(
            {s["class"].split(".")[-1]: s["min_executed"] for s in stable["security_suites"]},
            {"ResettableLazyTests": 4, "SignalBaseIdentityKeyStoreTest": 3,
             "KeyCachingServiceTest": 5, "MollyBackupPreferencesTest": 11,
             "UnifiedPushReceiverTest": 6, "UnifiedPushRefreshJobTest": 3},
        )
        workflow = (root / gate.WORKFLOW_PATH).read_text()
        self.assertIn("github.base_ref == 'release/stable/8.35'", workflow)
        self.assertIn("github.ref == 'refs/heads/release/stable/8.35'", workflow)
        self.assertIn("check-unit-test-results.py --policy \"$CI_TEST_POLICY\"", workflow)

    def test_input_validation_precedes_api_calls(self):
        def forbidden(*args, **kwargs):
            self.fail("invalid input reached GitHub")
        for repo, sha in (("../../repos", SHA), (REPO, "main"), (REPO, SHA.upper())):
            with self.subTest(repo=repo, sha=sha), self.assertRaises(gate.Failure):
                gate.verify(repo, sha, reader=forbidden)


if __name__ == "__main__":
    unittest.main()
