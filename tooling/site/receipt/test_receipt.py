"""Tests for deterministic, source-bound development receipt rendering."""
import copy
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import freeze
import render
import metrics


class ReceiptTests(unittest.TestCase):
    def setUp(self):
        self.data, _ = freeze.load_editorial()

    def test_frozen_receipt_and_render_are_reproducible(self):
        first = freeze.freeze(self.data)
        second = freeze.freeze(copy.deepcopy(self.data))
        self.assertEqual(freeze.encode(first), freeze.encode(second))
        for layout in ('app', 'release'):
            self.assertEqual(render.render(first, layout), render.render(second, layout))
            with patch('subprocess.check_output', side_effect=AssertionError('render queried Git')):
                self.assertEqual(render.render(first, layout), render.render(second, layout))
        self.assertEqual(first['membership']['status'], 'unknown')
        self.assertEqual(first['release_metrics']['production'], [84, 16])

    def test_reordering_and_extra_items_do_not_drop_stories(self):
        data = copy.deepcopy(self.data)
        data['features'].reverse()
        extra = copy.deepcopy(data['release_changes'][0])
        extra['display_title'] = 'Another reviewed item'
        extra['_record_path'] = 'changes/another-reviewed-item.json'
        data['release_changes'].append(extra)
        receipt = freeze.freeze(data)
        app = render.render(receipt, 'app')
        release = render.render(receipt, 'release')
        for f in data['features']:
            if f['placement'] != 'hidden':
                self.assertIn(f['display_title'], app)
        self.assertIn(extra['display_title'], release)
        self.assertEqual(release.count('class="release-story"'), len(data['release_changes']))

    def test_release_copy_is_data_driven(self):
        data = copy.deepcopy(self.data)
        data['release_headline'] = 'A different reviewed headline.'
        receipt = freeze.freeze(data)
        self.assertIn(data['release_headline'], render.render(receipt, 'release'))
        self.assertIn(data['release_status'], render.render(receipt, 'app'))

    def test_invalid_source_and_visual_evidence_are_rejected(self):
        for mutation in ('source', 'blob', 'support', 'absent', 'visual', 'membership', 'release_path', 'missing_copy'):
            bad = copy.deepcopy(self.data)
            if mutation == 'source': bad['source'] = '0' * 40
            if mutation == 'blob': bad['features'][0]['source_blob'] = '0' * 40
            if mutation == 'support': bad['features'][1]['supporting_paths'][0]['blob'] = '0' * 40
            if mutation == 'absent': bad['features'][-1]['absent_paths'] = [bad['features'][0]['path']]
            if mutation == 'visual': bad['features'][0]['visual'] = 'unknown'
            if mutation == 'membership': bad['membership']['status'] = 'confirmed'
            if mutation == 'release_path': bad['release_changes'][0]['path'] = bad['features'][1]['path']
            if mutation == 'missing_copy': bad['release_changes'][0]['change_type'] = ''
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                freeze.freeze(bad)

    def test_reconciliation_flags_record_changes_and_removals(self):
        previous = freeze.freeze(self.data)
        changed = copy.deepcopy(self.data)
        changed['features'][0]['summary'] += ' Edited.'
        removed = changed['features'].pop()['_record_path']
        result = freeze.freeze(changed, previous)['reconciliation']
        self.assertEqual(result['status'], 'review-needed')
        self.assertIn(removed, result['removed'])
        self.assertIn(changed['features'][0]['_record_path'], result['changed'])
        self.assertEqual(freeze.freeze(self.data, previous)['reconciliation']['status'], 'unchanged')

    def test_editorial_revision_reads_git_objects_not_working_files(self):
        scratch = Path.home() / 'agent-scratch/collieim-android/receipt-tests'
        scratch.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=scratch) as directory:
            root = Path(directory).resolve()
            folder = root / 'receipt'
            (folder / 'subjects').mkdir(parents=True)
            (folder / 'changes').mkdir()
            manifest = folder / 'pilot.json'
            manifest.write_text(json.dumps({'subjects': ['subjects/example.json'], 'release_records': ['changes/example.json']}))
            subject = folder / 'subjects/example.json'
            subject.write_text(json.dumps({'title': 'Committed subject'}))
            (folder / 'changes/example.json').write_text(json.dumps({'title': 'Committed change'}))
            env = {k: v for k, v in os.environ.items() if not k.startswith('GIT_')}
            env.update(GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM='1')
            def git(*args):
                return subprocess.run(['git', *args], cwd=root, env=env, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True).stdout.strip()
            git('init', '--initial-branch=main')
            git('config', 'user.name', 'Test Author')
            git('config', 'user.email', 'test@example.com')
            git('config', 'commit.gpgsign', 'false')
            git('add', 'receipt')
            git('commit', '-m', 'Fixture')
            revision = git('rev-parse', 'HEAD')
            subject.write_text(json.dumps({'title': 'Uncommitted replacement'}))
            with patch.object(render, 'ROOT', root):
                loaded, resolved = freeze.load_editorial(manifest, revision)
            self.assertEqual(resolved, revision)
            self.assertEqual(loaded['features'][0]['title'], 'Committed subject')

    def test_editorial_text_is_escaped(self):
        data = copy.deepcopy(self.data)
        data['features'][1]['summary'] = '<script>alert(1)</script>'
        self.assertNotIn('<script>', render.render(freeze.freeze(data), 'app'))

    def test_metric_classification_and_history_are_separate(self):
        change = '1a0c18c32c5286e9061839cd37d96e432888fd05'
        self.assertEqual(metrics.change_metrics(change)['tests'], [92, 3])
        self.assertEqual(metrics.classify('app/src/main/res/values-fr/strings.xml'), 'translations')
        self.assertEqual(metrics.classify('app/src/main/generated/example.java'), 'generated')


if __name__ == '__main__':
    unittest.main()
