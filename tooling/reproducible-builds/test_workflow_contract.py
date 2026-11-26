import json
from pathlib import Path
import re
import subprocess
import unittest


WORKFLOW = Path(__file__).parents[2] / ".github/workflows/build-release.yml"
STRICT_PREP = "COLLIE_REQUIRE_RELEASE_PREP"


class WorkflowContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # The GitHub-hosted ubuntu-24.04 image ships yq v4.
        cls.workflow = json.loads(subprocess.check_output(
            ["yq", "-o=json", ".", str(WORKFLOW)], text=True,
        ))

    def test_workflow_permissions_are_read_only(self):
        self.assertEqual(self.workflow["permissions"]["contents"], "read")
        for name, scope in [("workflow", self.workflow), *self.workflow["jobs"].items()]:
            with self.subTest(scope=name):
                permissions = scope.get("permissions", {})
                if permissions != "read-all":
                    self.assertIsInstance(permissions, dict)
                    self.assertLessEqual(set(permissions.values()), {"read", "none"})

    def test_only_direct_beta_dispatch_opts_into_strict_preparation(self):
        build = self.workflow["jobs"]["build"]
        environments = [scope.get("env", {}) for scope in (self.workflow, build, *build["steps"])]
        expressions = [env[STRICT_PREP] for env in environments if STRICT_PREP in env]
        self.assertTrue(expressions, f"Missing {STRICT_PREP} from the build")
        gates = (
            ("github.event_name", "'workflow_dispatch'"),
            ("github.repository", "'0cwa/collieim-android'"),
            ("github.ref", "'refs/heads/main'"),
            ("inputs.edition", "'beta'"),
            ("inputs.ref", "github.sha"),
            # A reusable workflow can inherit workflow_dispatch from its caller.
            ("github.workflow_ref", "job.workflow_ref"),
        )
        for expression in expressions:
            with self.subTest(expression=expression):
                match = re.fullmatch(r"\s*\$\{\{\s*(.*?)\s*&&\s*'1'\s*\|\|\s*'0'\s*\}\}\s*",
                                     expression, re.DOTALL)
                self.assertIsNotNone(match, "Expected a strict-preparation gate returning '1' or '0'")
                comparisons = [re.split(r"\s*==\s*", term.strip(), maxsplit=1) for term in match[1].split("&&")]
                # Check the conjunction as unordered equalities so formatting and
                # predicate order can change without weakening the gate.
                self.assertCountEqual([frozenset(pair) for pair in comparisons],
                                      [frozenset(pair) for pair in gates])

    def test_reusable_release_build_defaults_to_stable(self):
        self.assertEqual(self.workflow["on"]["workflow_call"]["inputs"]["edition"]["default"], "stable")


if __name__ == "__main__":
    unittest.main()
