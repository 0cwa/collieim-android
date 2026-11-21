"""Offline source/build contract tests; these run in depth-1 website CI."""
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import build
from receipt import evidence, freeze, pipeline, render

SCRATCH = Path.home() / 'agent-scratch/collie-android-receipt-pilot/2026-11-17'


class SourcePipelineTests(unittest.TestCase):
    def setUp(self):
        self.receipt = evidence.load_snapshot()
        SCRATCH.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=SCRATCH)
        self.addCleanup(self.temp.cleanup)
        self.out = Path(self.temp.name)

    def test_source_v2_survives_presentation_edits_but_v1_keeps_asset_guarantee(self):
        before = render.render(self.receipt, 'app')
        with patch.object(render, 'asset_hashes', return_value={'new-font': 'hash'}):
            self.assertEqual(before, render.render(self.receipt, 'app'))
            legacy = dict(self.receipt, receipt_version=1, asset_hashes={'old-font': 'hash'})
            with self.assertRaisesRegex(ValueError, 'assets differ'):
                render.render(legacy, 'app')
        legacy = dict(self.receipt, receipt_version=1, asset_hashes=render.asset_hashes())
        self.assertEqual(before, render.render(legacy, 'app'))
        # Real CSS change is accepted with the same durable evidence and recorded by the build.
        original = Path.read_text
        def changed(path, *args, **kwargs):
            result = original(path, *args, **kwargs)
            return result + '\n.subject{padding-block:25px}' if path.name == 'stories.css' else result
        with patch.object(Path, 'read_text', changed):
            self.assertNotEqual(before, render.render(self.receipt, 'app'))
        assets = self.out / 'assets-checkout'
        for name in render.asset_hashes():
            target = assets / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(render.ROOT / name, target)
        (assets / 'docs/site/assets/collie-icon.svg').write_text('<svg xmlns="http://www.w3.org/2000/svg"/>')
        with patch.object(render, 'ROOT', assets):
            self.assertNotEqual(before, render.render(self.receipt, 'app'))
        bad = dict(self.receipt, receipt_version=999)
        with self.assertRaisesRegex(ValueError, 'Unsupported frozen receipt'):
            render.render(bad, 'app')

    def test_exact_source_record_changes_fail_offline_reconciliation(self):
        folder = self.out / 'records'
        shutil.copytree(evidence.DATA.parent, folder, ignore=shutil.ignore_patterns('*.py', '__pycache__'))
        for mutation in ('source', 'copy', 'remove', 'reorder', 'extra', 'missing'):
            with self.subTest(mutation=mutation):
                manifest = json.loads(evidence.DATA.read_text())
                if mutation == 'source': manifest['source'] = '0' * 40
                if mutation == 'remove': manifest['subjects'].pop()
                if mutation == 'reorder': manifest['subjects'].reverse()
                if mutation == 'extra':
                    manifest['subjects'].append('subjects/new.json')
                    (folder / 'subjects/new.json').write_text((evidence.DATA.parent / 'subjects/contact-badges.json').read_text())
                record = folder / 'subjects/contact-badges.json'
                record.write_text((evidence.DATA.parent / 'subjects/contact-badges.json').read_text())
                if mutation == 'copy':
                    item = json.loads(record.read_text()); item['summary'] = 'Replacement claim'; record.write_text(json.dumps(item))
                if mutation == 'missing': record.unlink()
                (folder / 'pilot.json').write_text(json.dumps(manifest))
                with self.assertRaises((ValueError, FileNotFoundError)):
                    evidence.load_snapshot(folder / 'pilot.json')
        tampered = copy.deepcopy(self.receipt)
        tampered['editorial']['source'] = '0' * 40
        with self.assertRaisesRegex(ValueError, 'hashes/manifest'):
            render.render(tampered, 'app')

    def test_published_history_is_notes_only_and_never_relabels_reviewed_edition(self):
        releases = json.loads((evidence.DATA.parent / 'fixtures/releases.json').read_text())
        tags = build.published_tags(releases)
        self.assertEqual(tags, ['v99.0-rc1', 'v8.35.1-3-rc2', 'v8.35.1-3-rc1'])
        manifest = pipeline.build(self.out, published=tags)
        page = (self.out / 'releases/index.html').read_text()
        self.assertNotIn('v100.0', page)
        self.assertEqual(manifest['newer_releases'], ['v99.0-rc1'])
        # The newest release leads as a notes link only; the reviewed edition keeps its own heading and stories.
        newest, reviewed = page.split('<main', 1)[1].split('<section class="current-edition"', 1)
        self.assertIn('<h2 id="newest-title">v99.0-rc1</h2>', newest)
        self.assertIn('/releases/tag/v99.0-rc1', newest)
        self.assertNotIn('edition-story', newest)
        self.assertIn('<h2>v8.35.1-3-rc2</h2>', reviewed.split('<aside', 1)[0])
        self.assertNotIn('v99.0-rc1', reviewed.split('<aside', 1)[0])
        self.assertNotIn('v99.0-rc1', reviewed.split('<aside', 1)[1])
        self.assertIn('/releases/tag/v8.35.1-3-rc1', reviewed.split('<aside', 1)[1])
        self.assertEqual(page.count('class="edition-story"'), 2)
        app = (self.out / 'patches/index.html').read_text()
        self.assertIn('Newer release: <a href="https://github.com/0cwa/collieim-android/releases/tag/v99.0-rc1">v99.0-rc1</a>', app)
        self.assertEqual(self.receipt['editorial']['source'], 'cc55c02bef64b2980c5f384a6a04f52ffb7bef92')

    def test_newer_release_detection(self):
        def newer(published):
            receipt = copy.deepcopy(self.receipt)
            if published is not None:
                receipt['published_releases'] = published
            return render.newer_releases(receipt)
        self.assertEqual(newer(None), [])
        self.assertEqual(newer(['v8.35.1-3-rc2', 'v8.35.1-3-rc1']), [])
        self.assertEqual(newer(['v9.1', 'v9.0', 'v8.35.1-3-rc2']), ['v9.1', 'v9.0'])
        # An unpublished reviewed release: only tags before the first known-older release are newer.
        self.assertEqual(newer(['v9.1', 'v9.0']), ['v9.1', 'v9.0'])
        self.assertEqual(newer(['v9.1', 'v8.35.1-3-rc1', 'v8.35.1-2-rc1']), ['v9.1'])
        self.assertEqual(newer(['v8.35.1-3-rc1', 'v8.35.1-2-rc1']), [])
        unpublished = copy.deepcopy(self.receipt)
        unpublished['published_releases'] = ['v8.35.1-3-rc1']
        self.assertIs(render.reviewed_release_published(unpublished), False)
        self.assertIsNone(render.reviewed_release_published(self.receipt))
        current = pipeline.build(self.out, published=['v8.35.1-3-rc2', 'v8.35.1-3-rc1'])
        self.assertEqual(current['newer_releases'], [])
        for name in ('releases/index.html', 'patches/index.html'):
            text = (self.out / name).read_text()
            self.assertNotIn('class="newest"', text)
            self.assertNotIn('class="newer"', text)

    def test_build_reports_when_reviewed_pages_trail_a_release(self):
        err = io.StringIO()
        with patch.dict(os.environ, {'GITHUB_ACTIONS': ''}), patch('sys.stderr', err):
            build.report_behind({'reviewed_release': 'v8.35.1-3-rc2', 'newer_releases': [], 'reviewed_release_published': False})
        self.assertIn('v8.35.1-3-rc2, which is not a published release yet', err.getvalue())
        manifest = {'reviewed_release': 'v8.35.1-3-rc2', 'newer_releases': ['v9.1', 'v9.0']}
        for actions, stream in (('true', 'stdout'), ('', 'stderr')):
            out, err = io.StringIO(), io.StringIO()
            with self.subTest(actions=actions), patch.dict(os.environ, {'GITHUB_ACTIONS': actions}), patch('sys.stdout', out), patch('sys.stderr', err):
                build.report_behind(manifest)
                text = (out if stream == 'stdout' else err).getvalue()
                self.assertIn('describe v8.35.1-3-rc2; newer published release(s): v9.1, v9.0', text)
                self.assertEqual(text.startswith('::notice '), actions == 'true')
        out = io.StringIO()
        with patch('sys.stdout', out), patch('sys.stderr', out):
            build.report_behind({'reviewed_release': 'v8.35.1-3-rc2', 'newer_releases': []})
        self.assertEqual(out.getvalue(), '')

    def test_release_discovery_paginates_and_fails_at_bound(self):
        row = {'tag_name': 'v1.0', 'draft': False, 'published_at': '2026-01-01T00:00:00Z'}
        def response(rows): return io.BytesIO(json.dumps(rows).encode())
        with patch('urllib.request.urlopen', side_effect=[response([row]*100), response([])]) as request:
            self.assertEqual(build.fetch_releases('owner/repo'), ['v1.0'])
            self.assertTrue(request.call_args.args[0].full_url.endswith('page=2'))
        with patch('urllib.request.urlopen', return_value=response([row]*100)):
            with self.assertRaisesRegex(ValueError, 'exceeded'):
                build.fetch_releases('owner/repo', max_pages=1)

    def test_offline_build_determinism_routes_and_manifest(self):
        with patch('subprocess.check_output', side_effect=AssertionError('build queried Git')), patch('urllib.request.urlopen', side_effect=AssertionError('build queried network')):
            for routes in ('permanent', 'pilot'):
                first = pipeline.build(self.out, routes=routes)
                second = pipeline.build(self.out, routes=routes)
                self.assertEqual(first, second)
                for name, digest in first['outputs'].items():
                    page = self.out / name
                    self.assertEqual(hashlib.sha256(page.read_bytes()).hexdigest(), digest)
                    text = page.read_text()
                    for link in render.ROUTES[routes].values():
                        self.assertIn('href="' + link + '"', text)
                        self.assertTrue(((page.parent / link).resolve() / 'index.html').is_file() if link.endswith('/') else (page.parent / link).is_file())
        self.assertNotIn('asset_hashes', self.receipt)
        self.assertIn('assets', first)

    def test_site_export_without_git_or_cached_source_objects_builds_both_routes(self):
        # Export just the site/tooling, not an Android repo clone or worktree.
        root = self.out / 'export'
        shutil.copytree(build.ROOT / 'tooling/site', root / 'tooling/site', ignore=shutil.ignore_patterns('__pycache__'))
        shutil.copytree(build.SOURCE, root / 'docs/site')
        (root / 'roadmap.json').write_text('[]')
        env = dict(os.environ, PYTHONDONTWRITEBYTECODE='1')
        command = [sys.executable, 'tooling/site/build.py', '--out', str(self.out / 'built'), '--tag', 'v1.2', '--roadmap-json', 'roadmap.json']
        subprocess.run(command, cwd=root, env=env, check=True, stdout=subprocess.PIPE)
        self.assertFalse((root / '.git').exists())
        self.assertTrue((self.out / 'built/patches/index.html').is_file())
        self.assertTrue((self.out / 'built/releases/index.html').is_file())
        self.assertNotIn('../patches/', (self.out / 'built/index.html').read_text())
        self.assertEqual((root / 'docs/site/index.html').read_bytes(), (build.SOURCE / 'index.html').read_bytes())


if __name__ == '__main__':
    unittest.main()
