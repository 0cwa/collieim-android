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
import evidence


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

    def test_patch_interface_keeps_counts_in_release_scope(self):
        receipt = freeze.freeze(self.data)
        app = render.render(receipt, 'app')
        release = render.render(receipt, 'release')
        for page in (app, release):
            self.assertNotIn('Source and evidence', page)
            self.assertNotIn('<figure', page)
            self.assertNotIn('class="opening"', page)
        self.assertIn('aria-label="Patch index"', app)
        self.assertIn('Adaptation', app)
        self.assertIn('Restoration', app)
        self.assertNotIn('Production lines:', app)
        self.assertIn('Production lines:', release)
        self.assertIn('class="added">+84', release)
        self.assertIn('class="removed">−16', release)
        self.assertIn('Tests excluded', release)

        from html.parser import HTMLParser
        class Counts(HTMLParser):
            def __init__(self):
                super().__init__()
                self.details = []
                self.in_summary = False
                self.in_style = False
                self.counts = []
            def handle_starttag(self, tag, attrs):
                if tag == 'style': self.in_style = True
                if tag == 'details': self.details.append(dict(attrs))
                if tag == 'summary': self.in_summary = True
            def handle_endtag(self, tag):
                if tag == 'style': self.in_style = False
                if tag == 'details': self.details.pop()
                if tag == 'summary': self.in_summary = False
            def handle_data(self, text):
                if not self.in_style and ('+84' in text or '−16' in text):
                    self.counts.append((list(self.details), self.in_summary))
        counts = Counts()
        counts.feed(release)
        self.assertEqual(len(counts.counts), 2)
        for ancestors, in_summary in counts.counts:
            self.assertFalse(in_summary)
            self.assertTrue(any(d.get('class') == 'comparison' and 'open' not in d for d in ancestors))

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

    def test_preview_boundaries_and_variable_content(self):
        receipt = freeze.freeze(self.data)
        for layout in ('app', 'release'):
            page = render.render(receipt, layout)
            self.assertIn('Release membership is unknown.', page)
            self.assertIn('Behavior checks have not been run', page)
            self.assertNotIn('Featured<br>Release candidate', page)
            for item in self.data['features']:
                if item['placement'] == 'hidden':
                    self.assertNotIn(item['display_title'], page)
                    self.assertNotIn(item['path'], page)
        for count in (0, 1, 8):
            varied = copy.deepcopy(self.data)
            varied['release_changes'] = (varied['release_changes'] * 4)[:count]
            for i, item in enumerate(varied['release_changes']):
                varied['release_changes'][i] = dict(item, _record_path=f'changes/varied-{i}.json')
            page = render.render(freeze.freeze(varied), 'release')
            self.assertEqual(page.count('class="edition-story"'), count)
            if not count:
                self.assertIn('No selected release story is available', page)
        receipt['membership']['status'] = 'approved'
        with self.assertRaisesRegex(ValueError, 'adapter'):
            render.render(receipt, 'release')

    def test_opt_in_site_preview_preserves_scope(self):
        import importlib.util
        spec = importlib.util.spec_from_file_location('site_build', Path(render.ROOT) / 'tooling/site/build.py')
        site = importlib.util.module_from_spec(spec)
        import sys
        with patch.dict(sys.modules):
            sys.path.insert(0, str(Path(render.ROOT) / 'tooling/site'))
            try:
                spec.loader.exec_module(site)
            finally:
                sys.path.pop(0)
        scratch = Path.home() / 'agent-scratch/collieim-android/receipt-tests'
        scratch.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=scratch) as directory:
            root = Path(directory)
            frozen = root / 'receipt.json'
            frozen.write_bytes(freeze.encode(freeze.freeze(self.data)))
            site.build_receipt_preview(root, frozen)
            self.assertIn('Patch explorer', (root / 'pilot/app.html').read_text())
            self.assertIn('Release membership is unknown.', (root / 'pilot/release.html').read_text())
            self.assertFalse((root / 'index.html').exists())

    def test_invalid_source_and_visual_evidence_are_rejected(self):
        for mutation in ('source', 'blob', 'support', 'absent', 'visual', 'membership', 'release_path', 'missing_copy'):
            bad = copy.deepcopy(self.data)
            if mutation == 'source': bad['source'] = '0' * 40
            if mutation == 'blob': bad['features'][0]['source_blob'] = '0' * 40
            if mutation == 'support':
                supported = next(item for item in bad['features'] if item.get('supporting_paths'))
                supported['supporting_paths'][0]['blob'] = '0' * 40
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

    def test_update_pins_fills_ids_and_reports_changed_files(self):
        with tempfile.TemporaryDirectory(dir=Path.home() / 'agent-scratch') as temp:
            copy_dir = Path(temp) / 'receipt'
            shutil.copytree(evidence.DATA.parent, copy_dir, ignore=shutil.ignore_patterns('__pycache__', '*.py', '*.css'))
            originals = {p: p.read_bytes() for p in copy_dir.rglob('*.json')}
            manifest = json.loads((copy_dir / 'pilot.json').read_text())
            manifest['source'] = ''
            (copy_dir / 'pilot.json').write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + '\n')
            lead = copy_dir / 'subjects/background-connection.json'
            record = json.loads(lead.read_text())
            stale = record['supporting_paths'][0]['blob']
            record['source_blob'] = ''
            record['supporting_paths'][1]['blob'] = stale
            lead.write_text(json.dumps(record, indent=2, ensure_ascii=False) + '\n')
            report = freeze.update_pins(copy_dir / 'pilot.json')
            self.assertEqual(len(report), 3)
            self.assertIn('source (empty) -> ' + self.data['source'], report[0])
            self.assertTrue(any('(empty) -> ' + self.data['features'][0]['source_blob'] in line for line in report))
            self.assertTrue(any(f'review: git diff {stale} ' in line for line in report))
            # Restored pins match the reviewed files byte for byte, and a second run changes nothing.
            self.assertEqual({p: p.read_bytes() for p in copy_dir.rglob('*.json')}, originals)
            self.assertEqual(freeze.update_pins(copy_dir / 'pilot.json'), [])
            record['path'] = 'no/such/file.kt'
            lead.write_text(json.dumps(record, indent=2, ensure_ascii=False) + '\n')
            with self.assertRaisesRegex(ValueError, 'no/such/file.kt is not in'):
                freeze.update_pins(copy_dir / 'pilot.json')
            manifest['source'], manifest['release'] = self.data['source'], 'v0.0-missing'
            (copy_dir / 'pilot.json').write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + '\n')
            with self.assertRaisesRegex(ValueError, 'fetch tags'):
                freeze.update_pins(copy_dir / 'pilot.json')

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
        receipt['receipt_version'] = 1
        receipt['asset_hashes'] = render.asset_hashes()
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
            with patch.object(evidence, 'ROOT', root):
                loaded, resolved = freeze.load_editorial(manifest, revision)
            self.assertEqual(resolved, revision)
            self.assertEqual(loaded['features'][0]['title'], 'Committed subject')
            subject.unlink()
            subject.symlink_to('../changes/example.json')
            with patch.object(evidence, 'ROOT', root):
                loaded, _ = freeze.load_editorial(manifest, revision)
            self.assertEqual(loaded['features'][0]['title'], 'Committed subject')
            subject.unlink()
            subject.symlink_to('/nonexistent-outside-record')
            with patch.object(evidence, 'ROOT', root):
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
            with patch.object(evidence, 'ROOT', root):
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
