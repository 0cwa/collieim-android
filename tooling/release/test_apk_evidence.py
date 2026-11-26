import unittest
from unittest.mock import Mock

from apk_evidence import build_artifact
from publication import Failure, REPOSITORY


SOURCE = "a" * 40


class BuildArtifactTest(unittest.TestCase):
    def setUp(self):
        self.workflow = {"id": 701, "path": ".github/workflows/pr.yml"}
        self.workflow_run = {
            "id": 123, "head_sha": SOURCE, "head_branch": "main", "run_attempt": 1,
            "head_repository": {"full_name": REPOSITORY}, "path": ".github/workflows/pr.yml",
            "event": "push", "status": "completed", "conclusion": "success", "workflow_id": self.workflow["id"],
        }
        self.job = {"id": 234, "name": "Unsigned production build", "head_sha": SOURCE,
                    "run_id": 123, "conclusion": "success", "run_attempt": 1,
                    "started_at": "2026-11-17T19:07:41Z", "completed_at": "2026-11-17T19:30:49Z"}
        self.artifact = {"id": 456, "name": "production-build-123", "expired": False,
                         "digest": "sha256:" + "b" * 64, "created_at": "2026-11-17T19:30:48Z"}
        self.github = Mock()
        self.github.api.side_effect = self.api

    def api(self, path):
        if path == "actions/workflows/" + self.workflow["path"].rsplit("/", 1)[1]:
            return self.workflow
        if path.startswith(f"actions/workflows/{self.workflow['id']}/runs?"):
            return {"workflow_runs": [self.workflow_run]}
        if "/jobs?" in path:
            return {"jobs": [self.job]}
        if "/artifacts?" in path:
            return {"artifacts": [self.artifact]}
        self.fail(f"Unexpected API request: {path}")

    def approved(self):
        return {"run_attempt": 1, "source_branch": "main", "workflow_path": self.workflow["path"],
                "workflow_event": "push", "workflow_id": self.workflow["id"],
                "jobs": [{"name": self.job["name"], "id": self.job["id"]}]}

    def test_reuses_the_production_artifact_from_the_bound_run(self):
        self.assertEqual(build_artifact(self.github, SOURCE, 123, legacy=False), (123, 456))
        with self.assertRaisesRegex(Failure, "No trusted"):
            build_artifact(self.github, SOURCE, 124, legacy=False)

    def test_rejects_a_build_for_another_commit(self):
        self.job["head_sha"] = "c" * 40
        with self.assertRaisesRegex(Failure, "did not build the exact commit"):
            build_artifact(self.github, SOURCE, 123, legacy=False)

    def test_pins_the_artifact_from_the_approved_attempt(self):
        approved = self.approved()
        self.assertEqual(build_artifact(self.github, SOURCE, 123, legacy=False, approved=approved), (123, 456))
        self.artifact["created_at"] = "2026-11-17T19:40:48Z"
        with self.assertRaisesRegex(Failure, "does not belong to the approved CI job"):
            build_artifact(self.github, SOURCE, 123, legacy=False, approved=approved)
        self.workflow_run["run_attempt"] = 2
        with self.assertRaisesRegex(Failure, "Approved CI attempt changed"):
            build_artifact(self.github, SOURCE, 123, legacy=False, approved=approved)

    def test_registered_workflow_must_match_both_report_and_run(self):
        approved = self.approved()
        self.workflow["id"] = 702
        with self.assertRaisesRegex(Failure, "different source branch or workflow"):
            build_artifact(self.github, SOURCE, 123, legacy=False, approved=approved)
        approved["workflow_id"] = self.workflow["id"]
        with self.assertRaisesRegex(Failure, "differs from the registered workflow"):
            build_artifact(self.github, SOURCE, 123, legacy=False, approved=approved)
        self.workflow_run["workflow_id"] = self.workflow["id"]
        self.assertEqual(build_artifact(self.github, SOURCE, 123, legacy=False, approved=approved), (123, 456))

    def test_registered_workflow_must_report_the_expected_path(self):
        self.github.api.side_effect = None
        self.github.api.return_value = {"id": 701, "path": ".github/workflows/other.yml"}
        with self.assertRaisesRegex(Failure, "Cannot resolve the registered workflow"):
            build_artifact(self.github, SOURCE, 123, legacy=False)

    def test_only_the_reported_branch_and_event_can_bind_a_build(self):
        approved = self.approved()
        with self.assertRaisesRegex(Failure, "Unsupported release source branch"):
            build_artifact(self.github, SOURCE, 123, legacy=False, source_branch="release/stable/8.35")
        with self.assertRaisesRegex(Failure, "different source branch"):
            build_artifact(self.github, SOURCE, 123, legacy=False,
                           approved={**approved, "source_branch": "release/stable/8.35"})
        self.workflow_run["event"] = "workflow_dispatch"
        with self.assertRaisesRegex(Failure, "event changed"):
            build_artifact(self.github, SOURCE, 123, legacy=False, approved=approved)
        approved["workflow_event"] = "workflow_dispatch"
        self.job["run_attempt"] = 2
        with self.assertRaisesRegex(Failure, "different attempt"):
            build_artifact(self.github, SOURCE, 123, legacy=False, approved=approved)
        self.job["run_attempt"] = 1
        self.job["id"] += 1
        with self.assertRaisesRegex(Failure, "does not belong to the approved CI job"):
            build_artifact(self.github, SOURCE, 123, legacy=False, approved=approved)

    def test_an_expired_artifact_does_not_trigger_a_rebuild(self):
        self.artifact["expired"] = True
        with self.assertRaisesRegex(Failure, "unexpired"):
            build_artifact(self.github, SOURCE, 123, legacy=False)

    def test_accepts_the_legacy_build_when_its_job_names_the_exact_commit(self):
        self.workflow_run.update(path=".github/workflows/build-release.yml", event="workflow_dispatch")
        self.workflow["path"] = self.workflow_run["path"]
        self.job["name"] = f"Build {SOURCE} (0)"
        self.artifact["name"] = "unsigned-release-apk"
        with self.assertRaisesRegex(Failure, "must be positive"):
            build_artifact(self.github, SOURCE, 0, legacy=True)
        self.assertEqual(build_artifact(self.github, SOURCE, 123, legacy=True), (123, 456))
        self.job["name"] = "Build main (0)"
        with self.assertRaisesRegex(Failure, "did not build the exact commit"):
            build_artifact(self.github, SOURCE, 123, legacy=True)


if __name__ == "__main__":
    unittest.main()
