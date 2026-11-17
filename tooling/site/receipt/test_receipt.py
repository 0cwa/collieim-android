"""Tests for deterministic, source-bound development receipt rendering."""
import copy
import json
import os
import shutil
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
        self.assertEqual(release.count('class="edition-story"'), len(data['release_changes']))

    def test_release_archive_is_ordered_and_data_driven(self):
        data = copy.deepcopy(self.data)
        data['release_history'] = ['v2-rc1', 'v1-rc1']
        page = render.render(freeze.freeze(data), 'release')
        self.assertLess(page.index('/releases/tag/v2-rc1'), page.index('/releases/tag/v1-rc1'))
        self.assertEqual(page.count('class="history-card"'), 2)
        data['release_history'] = []
        self.assertIn('Earlier releases will appear here.', render.render(freeze.freeze(data), 'release'))
        for history in ([data['release']], ['v1-rc1', 'v1-rc1'], ['v1-rc1/../../bad']):
            data['release_history'] = history
            with self.assertRaisesRegex(ValueError, 'release history'):
                freeze.freeze(data)

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
        renamed = copy.deepcopy(self.data)
        renamed['release_headline'] = 'A reviewed different headline'
        renamed['features'].reverse()
        result = freeze.freeze(renamed, previous)['reconciliation']
        self.assertEqual(result['status'], 'review-needed')
        self.assertIn('release_headline', result['manifest_changed'])
        self.assertIn('subject_order', result['manifest_changed'])

    def test_reconciliation_flags_valid_source_change(self):
        original = copy.deepcopy(self.data)
        original['features'] = []
        original['release_changes'] = []
        previous = freeze.freeze(original)
        changed = copy.deepcopy(original)
        changed['release'] = changed['release_base_tag']
        changed['source'] = changed['release_base']
        changed['release_history'] = []
        result = freeze.freeze(changed, previous)['reconciliation']
        self.assertEqual(result['status'], 'review-needed')
        self.assertIn('source', result['manifest_changed'])
        self.assertIn('release', result['manifest_changed'])

    def test_release_change_must_be_in_range_and_touch_cited_path(self):
        unrelated = subprocess.check_output(['git', 'rev-parse', '13f86f9c0c^{commit}'], cwd=render.ROOT, text=True).strip()
        for commit in (self.data['release_base'], unrelated):
            bad = copy.deepcopy(self.data)
            bad['release_changes'][0]['change'] = commit
            with self.subTest(commit=commit), self.assertRaises(ValueError):
                freeze.freeze(bad)

    def test_release_pathspec_is_not_treated_as_a_pattern(self):
        bad = copy.deepcopy(self.data)
        bad['release_changes'][1]['path'] = ':(glob)**/*.kt'
        with self.assertRaisesRegex(ValueError, 'did not touch'):
            freeze.freeze(bad)

    def test_release_directory_cannot_stand_in_for_a_file(self):
        bad = copy.deepcopy(self.data)
        bad['release_changes'][1]['path'] = 'feature/app-settings/src/main/java/org/signal/appsettings/home'
        with self.assertRaisesRegex(ValueError, 'did not touch'):
            freeze.freeze(bad)

    def test_asset_change_rejected_at_render(self):
        receipt = freeze.freeze(self.data)
        scratch = Path.home() / 'agent-scratch/collieim-android/receipt-tests'
        scratch.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=scratch) as directory:
            root = Path(directory).resolve()
            for name in receipt['asset_hashes']:
                target = root / name
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(render.ROOT / name, target)
            with patch.object(render, 'ROOT', root):
                render.render(receipt, 'app')
                (root / 'docs/site/assets/collie-icon.svg').write_text('<svg/>')
                with self.assertRaisesRegex(ValueError, 'assets differ'):
                    render.render(receipt, 'app')

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
            subject.unlink()
            subject.symlink_to('../changes/example.json')
            with patch.object(render, 'ROOT', root):
                loaded, _ = freeze.load_editorial(manifest, revision)
            self.assertEqual(loaded['features'][0]['title'], 'Committed subject')
            subject.unlink()
            subject.symlink_to('/nonexistent-outside-record')
            with patch.object(render, 'ROOT', root):
                loaded, _ = freeze.load_editorial(manifest, revision)
            self.assertEqual(loaded['features'][0]['title'], 'Committed subject')

    def test_tags_are_explicit_even_with_conflicting_branches(self):
        scratch = Path.home() / 'agent-scratch/collieim-android/receipt-tests'
        scratch.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=scratch) as directory:
            root = Path(directory).resolve()
            env = {k: v for k, v in os.environ.items() if not k.startswith('GIT_')}
            env.update(GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM='1')
            def git(*args):
                return subprocess.run(['git', *args], cwd=root, env=env, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True).stdout.strip()
            git('init', '--initial-branch=main')
            git('config', 'user.name', 'Test Author')
            git('config', 'user.email', 'test@example.com')
            git('config', 'commit.gpgsign', 'false')
            git('commit', '--allow-empty', '-m', 'First')
            source = git('rev-parse', 'HEAD')
            git('tag', 'v9.9.8')
            git('branch', 'v9.9.9')
            data = copy.deepcopy(self.data)
            data.update(source=source, release='v9.9.9', release_base=source, release_base_tag='v9.9.8', features=[], release_changes=[])
            with patch.object(render, 'ROOT', root):
                with self.assertRaises(subprocess.CalledProcessError):
                    render.validate(data)
                git('tag', 'v9.9.9')
                git('commit', '--allow-empty', '-m', 'Second')
                git('branch', '-f', 'v9.9.9', 'HEAD')
                self.assertEqual(render.validate(data)['source'], source)

    def test_metrics_ignore_local_config_and_worktree_attributes(self):
        scratch = Path.home() / 'agent-scratch/collieim-android/receipt-tests'
        scratch.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=scratch) as directory:
            root = Path(directory).resolve()
            env = {k: v for k, v in os.environ.items() if not k.startswith('GIT_')}
            env.update(GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM='1')
            def git(*args):
                return subprocess.run(['git', *args], cwd=root, env=env, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True).stdout.strip()
            git('init', '--initial-branch=main')
            git('config', 'user.name', 'Test Author')
            git('config', 'user.email', 'test@example.com')
            git('config', 'commit.gpgsign', 'false')
            source_file = root / 'app/src/main/code.txt'
            source_file.parent.mkdir(parents=True)
            source_file.write_text('one\ntwo\n')
            (root / '.gitattributes').write_text('*.txt text\n')
            git('add', '.')
            git('commit', '-m', 'Base')
            base = git('rev-parse', 'HEAD')
            source_file.write_text('one\nthree\n')
            git('add', '.')
            git('commit', '-m', 'Update')
            source = git('rev-parse', 'HEAD')
            with patch.object(render, 'ROOT', root):
                expected = metrics.release_metrics(base, source)
                self.assertEqual(expected['production'], [1, 1])
                git('config', 'diff.algorithm', 'histogram')
                (root / '.git/info/attributes').write_text('*.txt binary\n')
                (root / '.gitattributes').write_text('*.txt binary\n')
                with patch.dict(os.environ, {'GIT_ATTR_SOURCE': base, 'GIT_CONFIG_COUNT': '1', 'GIT_CONFIG_KEY_0': 'diff.algorithm', 'GIT_CONFIG_VALUE_0': 'patience'}):
                    self.assertEqual(metrics.release_metrics(base, source), expected)
                (root / '.git/info/attributes').write_text('')
                (root / '.gitattributes').write_text('*.txt binary\n')
                source_file.write_text('one\nfour\n')
                git('add', '.')
                git('commit', '-m', 'Binary attributes')
                binary_source = git('rev-parse', 'HEAD')
                (root / '.gitattributes').write_text('*.txt text\n')
                (root / '.git/info/attributes').write_text('*.txt text\n')
                binary = metrics.release_metrics(source, binary_source)
                self.assertIn('app/src/main/code.txt', binary['binary_paths'])
                self.assertEqual(binary['production'], [0, 0])

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
