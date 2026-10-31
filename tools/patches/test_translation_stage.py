"""Exercise translation generation and its fail-before-mutation guarantees."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

import forest
import translation_stage as stage


class TranslationStageTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.git('init', '-q')
        self.git('config', 'user.name', 'Test')
        self.git('config', 'user.email', 'test@example.invalid')
        self.path = 'app/src/main/res/values-fr/strings.xml'
        self.base = 'app/src/main/res/values/strings.xml'
        self.target = 'app/src/main/res/values-fr/strings2.xml'
        self.write(self.path, b'<resources><string name="title">Signal &amp; signal.org</string></resources>\n')
        self.write(self.base, b'<resources><string name="title" mollyify="true">Signal</string></resources>\n')
        self.write(self.target, b'<resources>\n</resources>\n')
        self.git('add', '.')
        self.git('commit', '-qm', 'Signal fixture')
        signal = self.git('rev-parse', 'HEAD^{tree}')
        signal_bytes = (self.root / self.path).read_bytes()
        self.write(self.path, b'<resources>mechanical preimage</resources>\n')
        self.git('add', '.')
        self.git('commit', '-qm', 'Mechanical fixture')
        before = self.git('rev-parse', 'HEAD^{tree}')
        outputs = {self.path: stage.transform(signal_bytes, (self.root / self.base).read_bytes(), self.path),
                   self.target: b'<resources>\n    <string name="please_wait">Attendez</string>\n</resources>\n'}
        self.data = {'schema': 1, 'type': 'generate-translations', 'source_commit': self.git('rev-parse', 'HEAD'),
                     'before_tree': before, 'after_tree': '0' * 40, 'signal_tree': signal, 'reference_tree': before,
                     'base_inputs': {self.base: stage.digest((self.root / self.base).read_bytes())},
                     'translation_inputs': {self.path: stage.digest(signal_bytes)},
                     'please_wait': {self.path: {'target': self.target,
                        'input_sha256': stage.digest((self.root / self.target).read_bytes()),
                        'line': '    <string name="please_wait">Attendez</string>\n'}},
                     'output_sha256': {p: stage.digest(b) for p, b in outputs.items()},
                     'preimages': {p: {'mode': '100644', 'blob': self.git('rev-parse', 'HEAD:' + p)} for p in outputs}}
        self.data['after_tree'] = stage.generate(self.root, self.root, self.data, forest.clean_git_env(), dry_run=True)

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.root), *args], stderr=subprocess.STDOUT).decode().strip()

    def write(self, path, content):
        path = self.root / path
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)

    def test_generate_uses_signal_tree_and_preserves_entities(self):
        stage.validate(self.data, 'fixture')
        stage.generate(self.root, self.root, self.data, forest.clean_git_env())
        self.assertEqual(self.git('rev-parse', 'HEAD^{tree}'), self.data['after_tree'])
        self.assertIn(b'Molly &amp; molly.im', (self.root / self.path).read_bytes())
        self.assertIn(b'please_wait', (self.root / self.target).read_bytes())

    def test_rejects_tampered_pins_without_mutating_index_or_files(self):
        before = self.git('rev-parse', 'HEAD')
        for key in ('after_tree', 'output_sha256', 'preimages'):
            with self.subTest(key=key):
                d = json.loads(json.dumps(self.data))
                if key == 'after_tree': d[key] = '0' * 40
                elif key == 'output_sha256': d[key][self.path] = '0' * 64
                else: d[key][self.path]['blob'] = '0' * 40
                with self.assertRaises(forest.ForestError):
                    stage.generate(self.root, self.root, d, forest.clean_git_env())
                self.assertEqual(self.git('rev-parse', 'HEAD'), before)
                self.assertEqual(self.git('write-tree'), self.data['before_tree'])
                self.assertEqual(self.git('status', '--porcelain'), '')

    def test_rejects_incomplete_paths_and_traversal(self):
        d = json.loads(json.dumps(self.data))
        d['translation_inputs'].clear()
        with self.assertRaises(forest.ForestError): stage.generate(self.root, self.root, d, forest.clean_git_env())
        d = json.loads(json.dumps(self.data))
        d['preimages']['../bad'] = d['preimages'].pop(self.path)
        with self.assertRaises(forest.ForestError): stage.validate(d, 'fixture')

    def test_rejects_dirty_worktree_and_symlink(self):
        self.write(self.path, b'dirty')
        with self.assertRaises(forest.ForestError): stage.generate(self.root, self.root, self.data, forest.clean_git_env())
        self.git('reset', '--hard', '-q', 'HEAD')
        (self.root / self.path).unlink()
        (self.root / self.path).symlink_to(self.root / self.target)
        with self.assertRaises(forest.ForestError): stage.generate(self.root, self.root, self.data, forest.clean_git_env())

    def test_creates_translation_missing_from_before_tree(self):
        self.git('rm', '-q', self.path)
        self.git('commit', '-qm', 'Remove translation in mechanical result')
        self.data['before_tree'] = self.git('rev-parse', 'HEAD^{tree}')
        self.data['preimages'][self.path] = None
        stage.validate(self.data, 'fixture')
        self.data['after_tree'] = stage.generate(self.root, self.root, self.data, forest.clean_git_env(), dry_run=True)
        self.assertFalse((self.root / self.path).exists())
        stage.generate(self.root, self.root, self.data, forest.clean_git_env())
        self.assertEqual(self.git('rev-parse', 'HEAD^{tree}'), self.data['after_tree'])
        self.assertIn(b'Molly &amp; molly.im', (self.root / self.path).read_bytes())
