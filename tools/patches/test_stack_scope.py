import pathlib
import subprocess
import tempfile
import unittest
from unittest import mock
import scope


class ScopeTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.repo = pathlib.Path(self.tmp.name)
        self.git('init', '-q')
        self.git('config', 'user.email', 'test@example.invalid')
        self.git('config', 'user.name', 'Test')
        (self.repo / 'app.txt').write_text('original\n')
        self.git('add', '.')
        self.git('commit', '-qm', 'base')
        self.base = self.git('rev-parse', 'HEAD').strip()

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.repo), *args], text=True)

    def commit(self, name, content):
        path = self.repo / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        workflow = self.repo / '.github/workflows/molly-patch-release.yml'
        if not workflow.exists():
            workflow.parent.mkdir(parents=True, exist_ok=True)
            workflow.write_text('name: Molly\n')
        self.git('add', '.')
        self.git('commit', '-qm', 'change')

    def check(self):
        with mock.patch.object(scope, 'MOLLY', self.base):
            return scope.check_scope(self.repo, 'pipeline', self.base)

    def test_pipeline_accepts_tooling_and_rejects_app_changes(self):
        self.commit('tools/patches/example.py', 'print(1)\n')
        self.assertFalse(self.check()['profile_present'])
        self.commit('app.txt', 'unexpected\n')
        with self.assertRaisesRegex(ValueError, 'outside scope'):
            self.check()

    def test_pipeline_rejects_unapproved_workflow(self):
        self.commit('.github/workflows/other.yml', 'name: other\n')
        with self.assertRaisesRegex(ValueError, 'outside scope'):
            self.check()

    def test_base_must_be_ancestor(self):
        with mock.patch.object(scope, 'MOLLY', self.base):
            with self.assertRaisesRegex(ValueError, 'base is not an ancestor'):
                scope.check_scope(self.repo, 'pipeline', 'missing-base')

    def test_switch_accepts_unrelated_signal_tree_but_rejects_wrong_base_and_mutation(self):
        import json
        self.commit('tools/patches/tool.py', 'tool\n')
        lower = self.git('rev-parse', 'HEAD').strip()
        (self.repo / 'app.txt').write_text('signal version\n')
        self.git('add', 'app.txt')
        signal_tree = self.git('write-tree').strip()
        signal = subprocess.check_output(['git', '-C', str(self.repo), 'commit-tree', signal_tree], input=b'Signal\n').decode().strip()
        forest = self.repo / 'patches/molly/fixture'
        forest.parent.mkdir(parents=True)
        forest.write_text('immutable fixture\n')
        self.git('add', 'patches/molly')
        forest_tree = self.git('write-tree').strip()
        forest_id = self.git('rev-parse', forest_tree + ':patches/molly').strip()
        config = {'signal': {'commit': signal}, 'molly': {'target': self.base, 'tree': '3cd0bb01cf840e4b702187085432020d163d80ce'}, 'port': None, 'native': None}
        (self.repo / 'molly-build.json').write_text(json.dumps(config))
        self.git('add', '.')
        self.git('commit', '-qm', 'switch')
        with mock.patch.object(scope, 'MOLLY', self.base), mock.patch.object(scope, 'OLD', signal), mock.patch.object(scope, 'FOREST', forest_id):
            self.assertTrue(scope.check_scope(self.repo, 'switch', lower)['profile_present'])
            switch_head = self.git('rev-parse', 'HEAD').strip()
            with self.assertRaisesRegex(ValueError, 'lower base'):
                scope.check_scope(self.repo, 'switch', 'HEAD')
            with self.assertRaisesRegex(ValueError, 'outside scope'):
                scope.check_scope(self.repo, 'switch', self.base)
            with self.assertRaisesRegex(ValueError, 'base is not an ancestor'):
                scope.check_scope(self.repo, 'switch', signal)
            self.commit('app.txt', 'unexpected source mutation\n')
            with self.assertRaisesRegex(ValueError, 'source projection differs'):
                scope.check_scope(self.repo, 'switch', lower)

            self.git('checkout', '-q', '--detach', lower)
            self.commit('patches/ports/hidden', 'unreviewed port\n')
            hidden_base = self.git('rev-parse', 'HEAD').strip()
            self.git('checkout', switch_head, '--', 'app.txt', 'molly-build.json', 'patches/molly')
            self.git('add', '.')
            self.git('commit', '-qm', 'switch with hidden base port')
            with self.assertRaisesRegex(ValueError, 'outside scope'):
                scope.check_scope(self.repo, 'switch', hidden_base)
            self.git('checkout', '-q', '--detach', switch_head)
            self.commit('tools/patches/hidden.py', 'hidden tool change\n')
            hidden_tool_base = self.git('rev-parse', 'HEAD').strip()
            config['port'] = {'mechanical_tree': 'unused'}
            (self.repo / 'molly-build.json').write_text(json.dumps(config))
            port_series = self.repo / 'patches/ports/signal-8.33.2/series'
            port_series.parent.mkdir(parents=True)
            port_series.write_text('initial\n')
            self.git('add', '.')
            self.git('commit', '-qm', 'release')
            with mock.patch.object(scope, 'NEW', signal):
                with self.assertRaisesRegex(ValueError, 'no validated preceding stack phase'):
                    scope.check_scope(self.repo, 'release', hidden_tool_base)

    def test_followups_allow_only_named_adjacent_report_metadata(self):
        self.assertTrue(scope.allowed('followups', 'patches/ports/signal-8.33.2/report-metadata.json'))
        self.assertFalse(scope.allowed('followups', 'patches/ports/signal-8.33.2/unexpected.json'))

    def test_followups_allow_pipeline_pin_document_but_not_other_docs_or_tools(self):
        self.assertTrue(scope.allowed('followups', 'docs/PATCH-PIPELINE.md'))
        self.assertFalse(scope.allowed('followups', 'docs/OTHER.md'))
        self.assertFalse(scope.allowed('followups', 'docs/PATCH-PIPELINE.md.extra'))
        self.assertFalse(scope.allowed('followups', 'tools/patches/scope.py'))


class BundleScopeTests(unittest.TestCase):
    def test_release_allows_only_pipeline_document(self):
        self.assertTrue(scope.allowed('release', 'docs/PATCH-PIPELINE.md'))
        self.assertFalse(scope.metadata('docs/PATCH-PIPELINE.md.extra'))
        self.assertFalse(scope.metadata('docs/OTHER.md'))

    def test_only_exact_release_bundle_path_is_allowed(self):
        self.assertTrue(scope.allowed('release', 'patches/ports/signal-8.33.2/source-checkpoint.bundle'))
        self.assertFalse(scope.allowed('release', 'patches/ports/signal-8.33.2/unrelated.bundle'))


class ReportEvidenceScopeTests(unittest.TestCase):
    def test_only_bounded_report_evidence_paths_are_allowed(self):
        prefix = 'patches/ports/signal-8.33.2/report-evidence/'
        for phase in ('release', 'followups'):
            for name in ('receipts.md', 'fmt-01.patch', 'fmt-16.patch', 'lex-01.patch', 'lex-74.patch'):
                self.assertTrue(scope.allowed(phase, prefix + name))
            for name in ('other.patch', 'fmt-17.patch', 'lex-75.patch', '../series', 'receipts.md.extra'):
                self.assertFalse(scope.allowed(phase, prefix + name))
