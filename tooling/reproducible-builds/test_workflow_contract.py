import unittest
from pathlib import Path


WORKFLOW = Path(__file__).parents[2] / '.github/workflows/build-release.yml'


class WorkflowContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = WORKFLOW.read_text()

    def test_direct_beta_dispatch_has_the_release_engineering_run_title(self):
        self.assertIn("run-name: ${{ github.event_name == 'workflow_dispatch' && inputs.edition == 'beta' && 'Build TrailBlazer Beta' || 'Build unsigned release APK' }}", self.workflow)

    def test_dispatch_beta_is_bound_to_the_owning_main_workflow_and_sha(self):
        for required in (
            "if: ${{ matrix.edition == 'beta' && github.event_name != 'pull_request' }}",
            'test "$GITHUB_EVENT_NAME" = workflow_dispatch',
            'test "$REPOSITORY" = 0cwa/collieim-android',
            'test "$DISPATCH_REF" = refs/heads/main',
            'test "$REQUESTED_REF" = "$DISPATCH_SHA"',
            'test "$(git rev-parse HEAD)" = "$GITHUB_SHA"',
        ):
            with self.subTest(required=required):
                self.assertIn(required, self.workflow)

    def test_beta_artifact_matches_release_engineering_contract(self):
        for required in (
            'name: unsigned-release-apk${{ github.event_name',
            'mv -- "${apks[0]}" "$RUNNER_TEMP/out/trailblazer-beta.apk"',
            'git rev-parse HEAD > "$RUNNER_TEMP/out/source-commit.txt"',
            'git rev-parse HEAD^{tree} > "$RUNNER_TEMP/out/source-tree.txt"',
            'sha256sum trailblazer-beta.apk > SHA256SUMS',
            'path: ${{ runner.temp }}/out/',
        ):
            with self.subTest(required=required):
                self.assertIn(required, self.workflow)

    def test_pull_requests_build_both_editions_and_compare_each_pair(self):
        self.assertIn("fromJSON('[\"stable\", \"beta\"]')", self.workflow)
        self.assertIn('edition: [stable, beta]', self.workflow)
        for edition in ('stable', 'beta'):
            self.assertIn(f'unsigned-release-apk-${{{{ matrix.edition }}}}', self.workflow)
        self.assertIn("COLLIE_BUILD_EDITION: ${{ matrix.edition }}", self.workflow)

    def test_reusable_release_build_defaults_to_stable(self):
        self.assertIn("default: stable", self.workflow)
        self.assertIn("inputs.edition || 'stable'", self.workflow)

    def test_only_exact_direct_beta_dispatch_opts_into_strict_preparation(self):
        gates = (
            "github.event_name == 'workflow_dispatch'",
            "inputs.edition == 'beta'",
            "github.repository == '0cwa/collieim-android'",
            "github.ref == 'refs/heads/main'",
            "inputs.ref == github.sha",
            "github.workflow_ref == job.workflow_ref",
        )
        prefix = "COLLIE_REQUIRE_RELEASE_PREP: "
        expressions = [line.removeprefix(prefix) for raw in self.workflow.splitlines()
                       if (line := raw.strip()).startswith(prefix)]
        self.assertEqual(expressions, ["${{ " + " && ".join(gates) + " && '1' || '0' }}"])
        self.assertNotIn("COLLIE_DEVELOPMENT_BASE", self.workflow)

    def test_release_preparation_tests_run_only_for_pull_requests(self):
        self.assertIn("test-preparation:\n    name: Test release preparation\n    if: github.event_name == 'pull_request'", self.workflow)
        self.assertIn("python3 -m unittest discover -s tooling/collie-log -p 'test_*.py'", self.workflow)
        self.assertIn("python3 -m unittest discover -s tooling/reproducible-builds -p 'test_*.py'", self.workflow)


if __name__ == '__main__':
    unittest.main()
