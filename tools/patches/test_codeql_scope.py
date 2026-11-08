"""Exercise the post-PR38 CodeQL boundary with real Git commits and trees."""
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

import scope


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / '.github/workflows/molly-patch-release.yml'
REVIEWED_CODEQL_MAIN_BASE = scope.CODEQL_MAIN_BASE
REVIEWED_SPINNER_BLOB_MANIFEST = dict(scope.CODEQL_SPINNER_BLOB_MANIFEST)


class CodeQLFixture(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.repo = Path(temporary.name)
        self.git('init', '-q')
        self.git('config', 'user.name', 'Scope test')
        self.git('config', 'user.email', 'scope-test@example.invalid')
        self.write('app/source.kt', 'unchanged application\n')
        self.write('lib/network/source.kt', 'unchanged library\n')
        self.commit('reconstructed application')
        self.legacy = self.git('rev-parse', 'HEAD')
        self.application_tree = self.git('rev-parse', 'HEAD^{tree}')
        self.config = {
            'signal': {'commit': scope.NEXT_PORT_SIGNAL},
            'molly': {'target': scope.MOLLY},
            'native': 'tools/native/native-lock.json',
            'profiles': {'collie': {'target_tree': self.application_tree}},
        }
        self.write('molly-build.json', json.dumps(self.config) + '\n')
        self.write('tools/patches/main-clean-entries.json', '{}\n')
        for path in (
                'patches/molly/original', 'patches/ports/signal-8.35.1/series',
                'patches/forks/signal-foss/series', 'tools/native/native-lock.json',
                '.github/workflows/molly-patch-release.yml',
                '.github/workflows/molly-unsigned-release.yml',
                *scope.CODEQL_REPOSITORY_OVERLAYS):
            self.write(path, 'reviewed release input\n')
        self.commit('reviewed main release')
        self.base = self.git('rev-parse', 'HEAD')
        forest = self.git('rev-parse', 'HEAD:patches/molly')
        before_blobs = {
            path: self.git('rev-parse', self.base + ':' + path)
            for path in scope.CODEQL_REPOSITORY_OVERLAYS
        }
        for name, value in (
                ('CODEQL_MAIN_BASE', self.base), ('CODEQL_APPLICATION_TREE', self.application_tree),
                ('MAIN_INTEGRATION_LOWER', self.legacy), ('FOREST', forest)):
            patcher = mock.patch.object(scope, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)
        self.write('.github/workflows/codeql.yml', 'name: CodeQL\n')
        self.write('.github/codeql/codeql-config.yml', 'queries: []\n')
        for path in scope.CODEQL_REPOSITORY_OVERLAYS:
            self.write(path, 'reviewed security change\n')
        self.commit('CodeQL workflow')
        self.candidate = self.git('rev-parse', 'HEAD')
        fixture_manifest = {
            path: (before_blobs[path], self.git('rev-parse', self.candidate + ':' + path))
            for path in scope.CODEQL_REPOSITORY_OVERLAYS
        }
        self.spinner_manifest_patch = mock.patch.object(scope, 'CODEQL_SPINNER_BLOB_MANIFEST', fixture_manifest)
        self.spinner_manifest_patch.start()
        self.addCleanup(self.spinner_manifest_patch.stop)

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.repo), *args],
                                       text=True, stderr=subprocess.PIPE).strip()

    def write(self, path, content):
        target = self.repo / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content)

    def commit(self, message):
        self.git('add', '-A')
        self.git('commit', '-qm', message)

    def check(self, base=None):
        return scope.check_scope(self.repo, 'main-maintenance', base or self.base,
                                 integration_lower=self.legacy)


class CodeQLScopeTests(CodeQLFixture):
    def test_security_tools_and_exact_repository_assets_keep_application_frozen(self):
        for path in (*scope.CODEQL_REPOSITORY_OVERLAYS, 'tools/codeql/report.py',
                     'tools/codeql/test_spinner.mjs', 'tools/codeql/build_jni.py',
                     'tools/patches/test_generated_stages.py', '.github/dependabot.yml',
                     '.github/workflows/build-artifacts.yml', '.github/workflows/docker.yml',
                     '.github/workflows/stale.yml', 'docs/CODEQL.md'):
            self.write(path, 'reviewed security change\n')
        self.commit('security fixes and analysis')
        result = self.check()
        self.assertTrue(result['codeql_maintenance'])
        self.assertEqual(result['application_tree'], self.application_tree)
        self.assertTrue(result['profile_present'])
        self.assertTrue(result['port_present'])
        self.assertEqual(self.git('diff', self.base, 'HEAD', '--', 'molly-build.json',
                                  'patches', 'tools/native', 'app', 'lib/network'), '')
        self.assertEqual(scope.check_codeql_source_tree(self.repo, self.application_tree),
                         self.application_tree)

    def test_exact_additional_and_template_allowlists(self):
        self.assertEqual(len(scope.CODEQL_ADDITIONAL_FILES), 6)
        self.assertEqual(len(scope.CODEQL_TEMPLATE_FILES), 5)
        self.assertEqual(len(scope.CODEQL_TOOL_FILES), 10)
        for path in (*scope.CODEQL_ADDITIONAL_FILES, *scope.CODEQL_TEMPLATE_FILES,
                     *scope.CODEQL_TOOL_FILES):
            self.assertTrue(scope.codeql_maintenance_path(path), path)

    def test_no_other_application_or_release_inputs_are_permitted(self):
        for path in ('app/source.kt', 'lib/network/source.kt',
                     'lib/spinner/src/main/assets/js/tooltips.js',
                     'lib/spinner/src/main/java/org/signal/spinner/Spinner.kt',
                     'molly-build.json', 'patches/molly/original',
                     'patches/ports/signal-8.35.1/series', 'patches/forks/signal-foss/series',
                     'tools/native/native-lock.json', 'tools/patches/release.py',
                     '.github/workflows/unreviewed.yml'):
            with self.subTest(path=path):
                self.git('reset', '--hard', '-q', self.candidate)
                self.write(path, 'unreviewed change\n')
                self.commit('unreviewed source input')
                self.assertFalse(scope.main_codeql_only(self.repo, self.base, 'HEAD'))
                with self.assertRaisesRegex(ValueError, 'changes release/application input'):
                    scope.check_codeql_maintenance(self.repo, self.base, 'HEAD')

    def test_cumulative_projection_rejects_a_previously_landed_source_change(self):
        self.write('app/source.kt', 'previously landed direct edit\n')
        self.commit('bad prior source edit')
        bad_base = self.git('rev-parse', 'HEAD')
        self.write('tools/codeql/report.py', 'new security tool\n')
        self.commit('apparently safe maintenance')
        self.assertTrue(scope.main_codeql_only(self.repo, bad_base, 'HEAD'))
        with self.assertRaisesRegex(ValueError, 'changes release/application input: app/source.kt'):
            self.check(base=bad_base)
        with self.assertRaisesRegex(ValueError, 'changes release/application input: app/source.kt'):
            self.check(base='HEAD')

    def test_regular_source_files_cannot_be_deleted_or_replaced_by_links(self):
        for path in (*scope.CODEQL_REPOSITORY_OVERLAYS, '.github/workflows/codeql.yml'):
            for operation in ('delete', 'symlink', 'executable'):
                with self.subTest(path=path, operation=operation):
                    self.git('reset', '--hard', '-q', self.candidate)
                    target = self.repo / path
                    if operation == 'delete':
                        target.unlink()
                    elif operation == 'symlink':
                        target.unlink()
                        target.symlink_to('untrusted-target')
                    else:
                        target.chmod(0o755)
                    self.commit('invalid source mode')
                    with self.assertRaisesRegex(ValueError, 'regular'):
                        scope.check_codeql_maintenance(self.repo, self.base, 'HEAD')

    def test_mandatory_workflows_and_disabled_workflows_remain_protected(self):
        for path in ('.github/workflows/molly-patch-release.yml',
                     '.github/workflows/molly-unsigned-release.yml'):
            with self.subTest(path=path):
                self.git('reset', '--hard', '-q', self.candidate)
                (self.repo / path).unlink()
                self.commit('delete mandatory workflow')
                with self.assertRaises(ValueError):
                    scope.check_codeql_maintenance(self.repo, self.base, 'HEAD')
        for path in ('.github/workflows/android.yml', '.github/workflows/diffuse.yml'):
            with self.subTest(path=path):
                self.git('reset', '--hard', '-q', self.candidate)
                self.write(path, 'reintroduced Signal workflow\n')
                self.commit('restore disabled workflow')
                with self.assertRaises(ValueError):
                    scope.check_codeql_maintenance(self.repo, self.base, 'HEAD')

    def test_reviewed_anchor_and_current_base_must_be_ancestors(self):
        tree = self.git('rev-parse', 'HEAD^{tree}')
        unrelated = self.git('commit-tree', tree, '-m', 'unrelated root')
        with mock.patch.object(scope, 'CODEQL_MAIN_BASE', unrelated):
            with self.assertRaisesRegex(ValueError, 'reviewed CodeQL base is not an ancestor'):
                scope.check_codeql_maintenance(self.repo, self.base, 'HEAD')
        sibling = self.git('commit-tree', tree, '-p', self.base, '-m', 'different base')
        with self.assertRaisesRegex(ValueError, 'main base is not an ancestor'):
            scope.check_codeql_maintenance(self.repo, sibling, 'HEAD')

    def test_checked_out_spinner_manifest_matches_exact_reviewed_blobs(self):
        self.assertEqual(REVIEWED_CODEQL_MAIN_BASE, '9694af939eb3671f71eb53a277bea9608c8a2b32')
        for path, (before_blob, after_blob) in REVIEWED_SPINNER_BLOB_MANIFEST.items():
            before = subprocess.check_output(
                ['git', '-C', str(ROOT), 'ls-tree', REVIEWED_CODEQL_MAIN_BASE, '--', path], text=True
            ).strip()
            after = subprocess.check_output(
                ['git', '-C', str(ROOT), 'ls-tree', 'HEAD', '--', path], text=True
            ).strip()
            self.assertEqual(before, f'100644 blob {before_blob}\t{path}')
            self.assertEqual(after, f'100644 blob {after_blob}\t{path}')

    def test_exact_release_target_and_forest_pins_are_still_required(self):
        with mock.patch.object(scope, 'CODEQL_APPLICATION_TREE', '0' * 40):
            with self.assertRaisesRegex(ValueError, 'release pins differ'):
                self.check()
        with mock.patch.object(scope, 'FOREST', '0' * 40):
            with self.assertRaisesRegex(ValueError, 'Molly forest'):
                self.check()

    def test_reconstructed_tree_check_rejects_wrong_tree_and_present_overlay(self):
        with self.assertRaisesRegex(ValueError, 'application tree differs'):
            scope.check_codeql_source_tree(self.repo, 'HEAD')
        current_tree = self.git('rev-parse', 'HEAD^{tree}')
        with mock.patch.object(scope, 'CODEQL_APPLICATION_TREE', current_tree):
            with self.assertRaisesRegex(ValueError, 'overlay is present in application'):
                scope.check_codeql_source_tree(self.repo, 'HEAD')

    def test_security_path_allowlist_does_not_admit_siblings_or_traversals(self):
        for path in ('tools/codeql/../patches/release.py', 'tools/codeql/nested/test.py',
                     'tools/codeql/unreviewed.py', 'tools/codeql/publication/unreviewed.yml.in',
                     'tools/codeql/report.py.extra', '.github/codeql/codeql-config.yml.extra',
                     'lib/spinner/src/main/assets/query.hbs.extra',
                     'lib/spinner/src/main/assets/js/other.js', 'docs/OTHER.md'):
            self.assertFalse(scope.codeql_maintenance_path(path), path)


class CodeQLWorkflowTests(CodeQLFixture):
    """Execute the real early workflow shell and full validator on Git fixtures."""

    def setUp(self):
        super().setUp()
        validator = Path(scope.__file__).read_text()
        for name in ('CODEQL_MAIN_BASE', 'CODEQL_APPLICATION_TREE', 'FOREST'):
            validator, count = re.subn(r"(?m)^" + name + r" = '[^']+'$",
                                      name + ' = ' + repr(getattr(scope, name)), validator)
            self.assertEqual(count, 1)
        validator, count = re.subn(
            r'(?ms)^CODEQL_SPINNER_BLOB_MANIFEST = \{.*?^\}',
            'CODEQL_SPINNER_BLOB_MANIFEST = ' + repr(scope.CODEQL_SPINNER_BLOB_MANIFEST),
            validator,
        )
        self.assertEqual(count, 1)
        self.write('tools/patches/scope.py', validator)
        self.commit('security scope validator')
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.runner = Path(temporary.name)
        wrappers = self.runner / 'bin'
        wrappers.mkdir()
        # Only test-launching subprocesses are replaced. The workflow Python,
        # real scope validator and Git ancestry/tree checks execute unchanged.
        (wrappers / 'python3').write_text(
            '#!/bin/bash\n'
            'if [[ "$1" == -m && "$2" == unittest ]]; then\n'
            '  printf "python:%s\\n" "$*" >> "$TEST_LOG"\n'
            '  exit "${TEST_PYTHON_TEST_EXIT:-0}"\n'
            'fi\nexec ' + shlex.quote(sys.executable) + ' "$@"\n')
        (wrappers / 'node').write_text(
            '#!/bin/bash\nprintf "node:%s\\n" "$*" >> "$TEST_LOG"\n'
            'exit "${TEST_NODE_TEST_EXIT:-0}"\n')
        for wrapper in wrappers.iterdir():
            wrapper.chmod(0o700)
        self.env = dict(os.environ, PATH=str(wrappers) + ':' + os.environ['PATH'],
                        RUNNER_TEMP=str(self.runner), TEST_LOG=str(self.runner / 'tests.log'),
                        GITHUB_OUTPUT=str(self.runner / 'output'),
                        GITHUB_STEP_SUMMARY=str(self.runner / 'summary'))
        section = WORKFLOW.read_text().split('      - name: Validate CodeQL-only maintenance\n', 1)[1]
        section = section.split('      - name: Validate scope and tests\n', 1)[0]
        self.script = '\n'.join(line[10:] for line in section.split('        run: |\n', 1)[1].splitlines())

    def run_workflow(self, *, base=None, event='pull_request', **extra_env):
        for name in ('output', 'summary', 'tests.log'):
            (self.runner / name).unlink(missing_ok=True)
        env = dict(self.env, EVENT_NAME=event, BASE_SHA=self.base if base is None else base)
        env.update(extra_env)
        result = subprocess.run(['bash', '-e', '-o', 'pipefail', '-c', self.script],
                                cwd=self.repo, env=env, text=True, capture_output=True)
        output = self.runner / 'output'
        self.outputs = dict(line.split('=', 1) for line in output.read_text().splitlines()) if output.exists() else {}
        return result

    def test_validated_maintenance_runs_tests_before_publishing_skip_outputs(self):
        result = self.run_workflow()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.outputs, {'validated': 'true', 'profile': 'false', 'port': 'false',
                                       'stack_scope': 'main-maintenance', 'profiles': '[]', 'base': self.base})
        evidence = json.loads((self.runner / 'codeql-maintenance-scope.json').read_text())
        self.assertTrue(evidence['codeql_maintenance'])
        self.assertEqual(evidence['head'], self.git('rev-parse', 'HEAD'))
        self.assertEqual(evidence['application_tree'], self.application_tree)
        commands = (self.runner / 'tests.log').read_text().splitlines()
        self.assertEqual(len(commands), 3)
        self.assertIn('unittest discover -s tools/codeql', commands[0])
        self.assertEqual(commands[1], 'node:--test tools/codeql/test_spinner.mjs')
        self.assertIn('tools/patches/test_codeql_scope.py', commands[2])
        self.assertIn('test_stack_scope.ScopeTests.test_main_maintenance', commands[2])
        self.assertIn(self.application_tree, (self.runner / 'summary').read_text())

    def test_dispatch_snapshot_uses_full_validation_against_current_head(self):
        result = self.run_workflow(event='workflow_dispatch', base='')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.outputs['base'], self.git('rev-parse', 'HEAD'))
        self.assertEqual(self.outputs['validated'], 'true')

    def test_other_changes_keep_the_ordinary_release_route(self):
        self.write('app/source.kt', 'application change requires ordinary release checks\n')
        self.commit('application maintenance')
        result = self.run_workflow()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.outputs, {'validated': 'false'})
        self.assertFalse((self.runner / 'tests.log').exists())
        self.assertFalse((self.runner / 'codeql-maintenance-scope.json').exists())

    def test_dispatch_application_changes_keep_the_ordinary_release_route(self):
        self.write('app/source.kt', 'later application work on the same Signal release\n')
        self.commit('application maintenance')
        result = self.run_workflow(event='workflow_dispatch', base='')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.outputs, {'validated': 'false'})
        self.assertFalse((self.runner / 'tests.log').exists())
        self.assertFalse((self.runner / 'codeql-maintenance-scope.json').exists())

    def test_classifier_match_cannot_skip_cumulative_source_validation(self):
        self.write('app/source.kt', 'prior direct source edit\n')
        self.commit('prior source edit')
        bad_base = self.git('rev-parse', 'HEAD')
        self.write('tools/codeql/report.py', 'security maintenance\n')
        self.commit('otherwise permitted maintenance')
        self.assertTrue(scope.main_codeql_only(self.repo, bad_base, 'HEAD'))
        result = self.run_workflow(base=bad_base)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('changes release/application input: app/source.kt', result.stderr)
        self.assertEqual(self.outputs, {})
        self.assertFalse((self.runner / 'tests.log').exists())

    def test_required_workflow_failure_never_falls_through_or_skips_builds(self):
        (self.repo / '.github/workflows/codeql.yml').unlink()
        self.commit('missing required workflow')
        result = self.run_workflow()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('requires a regular 100644 file', result.stderr)
        self.assertEqual(self.outputs, {})

    def test_failed_security_tests_cannot_publish_skip_outputs(self):
        for variable in ('TEST_PYTHON_TEST_EXIT', 'TEST_NODE_TEST_EXIT'):
            with self.subTest(failing=variable):
                result = self.run_workflow(**{variable: '17'})
                self.assertEqual(result.returncode, 17, result.stderr)
                self.assertEqual(self.outputs, {})
                self.assertFalse((self.runner / 'summary').exists())

    def test_invalid_or_unrelated_base_fails_closed(self):
        tree = self.git('rev-parse', 'HEAD^{tree}')
        sibling = self.git('commit-tree', tree, '-p', self.base, '-m', 'different main base')
        for base in ('invalid', sibling):
            with self.subTest(base=base):
                result = self.run_workflow(base=base)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(self.outputs, {})

    def test_historical_release_still_uses_ordinary_routing(self):
        self.config['signal']['commit'] = '0' * 40
        self.write('molly-build.json', json.dumps(self.config))
        self.commit('historical release fixture')
        historical = self.git('rev-parse', 'HEAD')
        self.write('tools/codeql/report.py', 'tool change\n')
        self.commit('historical maintenance')
        result = self.run_workflow(base=historical)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.outputs, {'validated': 'false'})

    def test_job_guards_require_successful_validation_and_preserve_manual_instrumentation(self):
        workflow = WORKFLOW.read_text()
        early = workflow.split('      - name: Validate CodeQL-only maintenance\n', 1)[1]
        early = early.split('      - name: Validate scope and tests\n', 1)[0]
        self.assertIn("github.ref_name == 'main' && inputs.instrumentation == 'off'", early)
        ordinary = workflow.split('      - name: Validate scope and tests\n', 1)[1].split('  source-proof:', 1)[0]
        self.assertIn("if: steps.codeql_scope.outputs.validated != 'true'", ordinary)
        self.assertNotIn('always()', ordinary)
        self.assertNotIn('continue-on-error', early + ordinary)
        for name in ('profile', 'port', 'stack_scope', 'base', 'profiles'):
            self.assertIn('steps.codeql_scope.outputs.' + name + ' || steps.scope.outputs.' + name, workflow)
        self.assertIn('hosted_android: ${{ steps.hosted.outputs.android }}', workflow)
        self.assertIn("source-proof:\n    needs: scope\n    if: needs.scope.outputs.profile == 'true'", workflow)
        self.assertIn("android:\n    if: needs.scope.outputs.hosted_android == 'true' && needs.scope.outputs.stack_scope != 'next-port'", workflow)
        self.assertIn('needs: [scope, source-proof]', workflow.split('  android:', 1)[1].split('  instrumentation:', 1)[0])
        self.assertIn("needs.scope.outputs.hosted_android == 'true' &&", workflow.split('  instrumentation:', 1)[1])
        self.assertIn("needs.scope.outputs.profiles != '[]' &&", workflow.split('  instrumentation:', 1)[1])


if __name__ == '__main__':
    unittest.main()
