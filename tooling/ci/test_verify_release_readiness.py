"""Exercise the release boundary with missing, stale and failed CI evidence."""
import unittest

import verify_release_readiness as gate

SHA = "a" * 40
REPO = "0cwa/collieim-android"


def run_record(**changes):
    return {"id": 100, "run_attempt": 2, "path": gate.WORKFLOW_PATH,
            "head_sha": SHA, "head_branch": "main", "event": "push",
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
                        {"head_branch": "topic"}, {"path": ".github/workflows/other.yml"},
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
        for latest in (run_record(run_attempt=3), run_record(id=101)):
            responses = iter([page("workflow_runs", [run_record()]), page("jobs", jobs()),
                              page("workflow_runs", [latest])])
            with self.subTest(latest=latest), self.assertRaises(gate.Failure):
                gate.verify(REPO, SHA, reader=lambda *args, **kwargs: next(responses))

    def test_input_validation_precedes_api_calls(self):
        def forbidden(*args, **kwargs):
            self.fail("invalid input reached GitHub")
        for repo, sha in (("../../repos", SHA), (REPO, "main"), (REPO, SHA.upper())):
            with self.subTest(repo=repo, sha=sha), self.assertRaises(gate.Failure):
                gate.verify(repo, sha, reader=forbidden)


if __name__ == "__main__":
    unittest.main()
