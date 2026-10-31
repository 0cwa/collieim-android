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
