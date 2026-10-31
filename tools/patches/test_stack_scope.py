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
            with self.assertRaisesRegex(ValueError, 'base is not an ancestor'):
                scope.check_scope(self.repo, 'switch', signal)
            self.commit('app.txt', 'unexpected source mutation\n')
            with self.assertRaisesRegex(ValueError, 'source projection differs'):
                scope.check_scope(self.repo, 'switch', lower)
