"""Checks for generated roadmap content and safe output handling."""
import io
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import build


def discussion(number, title, category='upcoming-planned-features', priority=False):
    return {'number': number, 'title': title, 'category': {'slug': category},
            'labels': {'nodes': [{'name': 'priority'}] if priority else []}}


class RoadmapTest(unittest.TestCase):
    def test_only_accepted_category_is_published_and_priority_comes_first(self):
        nodes = [discussion(2, 'Later'), discussion(9, 'Requested only', 'ideas-requests'),
                 discussion(1, 'First priority', priority=True)]
        result = build.render_roadmap(nodes, '0cwa/collieim-android')
        self.assertNotIn('Requested only', result)
        self.assertLess(result.index('First priority'), result.index('Later'))
        self.assertIn('/discussions/1', result)

    def test_discussion_titles_cannot_inject_markup(self):
        result = build.render_roadmap([discussion(1, '<script>alert("x")</script> & more')], 'owner/repo')
        self.assertNotIn('<script>', result)
        self.assertIn('&lt;script&gt;', result)
        self.assertIn('&amp; more', result)

    def test_mapping_uses_discussion_number_before_cleaning(self):
        self.assertEqual('UnifiedPush on linked devices', build.display_roadmap_title(discussion(55, '[Enhancement] Changed title')))
        self.assertEqual('Send a text with its own timer', build.display_roadmap_title(discussion(106, 'Press and hold for one-time message duration')))

    def test_cleaner_removes_editorial_prefixes(self):
        for title in ('High Priority - Future feature', 'High priority: Future feature', '[Enhancement] Future feature', '[Research] - Future feature', '[Enhancement] [Research]: Future feature'):
            with self.subTest(title=title):
                self.assertEqual('Future feature', build.display_roadmap_title(discussion(999, title)))

    def test_mapped_titles_still_escape_markup(self):
        with patch.dict(build.ROADMAP_TITLES, {55: '<>&"'}):
            result = build.render_roadmap([discussion(55, 'Source title')], 'owner/repo')
        self.assertIn('&lt;&gt;&amp;&quot;', result)
        self.assertIn('/discussions/55', result)

    def test_unknown_title_keeps_its_wording(self):
        self.assertEqual('A future feature: choices', build.display_roadmap_title(discussion(999, 'A future feature: choices')))

    def test_priority_badge_depends_on_label_not_title(self):
        result = build.render_roadmap([discussion(998, 'High Priority - Ordinary label'), discussion(999, 'Labelled plan', priority=True)], 'owner/repo')
        self.assertEqual(1, result.count('class="plan-badge"'))
        self.assertIn('<li class="priority-plan"><span class="plan-badge">Priority</span><a href="https://github.com/owner/repo/discussions/999">Labelled plan</a>', result)
        self.assertIn('<li><a href="https://github.com/owner/repo/discussions/998">Ordinary label</a>', result)

    def test_empty_category_does_not_imply_work_is_promised(self):
        self.assertIn('No upcoming features', build.render_roadmap([], 'owner/repo'))

    def test_fetch_reads_all_pages(self):
        def response(nodes, more, cursor):
            return io.BytesIO(json.dumps({'data': {'repository': {'discussions': {
                'nodes': nodes, 'pageInfo': {'hasNextPage': more, 'endCursor': cursor}}}}}).encode())
        pages = [response([discussion(1, 'One')], True, 'next'), response([discussion(2, 'Two')], False, 'last')]
        with patch.dict('os.environ', {'GITHUB_TOKEN': 'test-only-value'}), patch('urllib.request.urlopen', side_effect=pages) as fetch:
            self.assertEqual([1, 2], [n['number'] for n in build.fetch_roadmap('owner/repo')])
        self.assertEqual('next', json.loads(fetch.call_args_list[1].args[0].data)['variables']['after'])

    def test_api_errors_fail_instead_of_publishing_an_empty_roadmap(self):
        with patch.dict('os.environ', {'GITHUB_TOKEN': 'test-only-value'}), patch('urllib.request.urlopen', return_value=io.BytesIO(b'{"errors":[{"message":"denied"}]}')):
            with self.assertRaisesRegex(SystemExit, 'discussions:read'):
                build.fetch_roadmap('owner/repo')

    def test_source_and_ancestors_are_not_deleted(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'docs/site'
            source.mkdir(parents=True)
            sentinel = source / 'keep.txt'
            sentinel.write_text('keep')
            with patch.object(build, 'SOURCE', source):
                for out in (source, source.parent, root):
                    with self.subTest(out=out), self.assertRaisesRegex(SystemExit, 'must not contain'):
                        build.build(out, 'v1.2', 'owner/repo', [])
                    self.assertEqual('keep', sentinel.read_text())

    def test_built_page_contains_real_discussion_links_and_no_placeholders(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'source'
            (source / 'assets').mkdir(parents=True)
            (source / 'assets/site.css').write_text('body{}')
            (source / 'index.html').write_text('<p>{{RELEASE_TAG}}</p><ul>{{UPCOMING_FEATURES}}</ul>')
            with patch.object(build, 'SOURCE', source):
                build.build(root / 'out', 'v1.2', 'owner/repo', [discussion(5, 'Linked phones')])
            page = (root / 'out/index.html').read_text()
            self.assertNotIn('{{', page)
            self.assertIn('https://github.com/owner/repo/discussions/5', page)
            self.assertIn('Linked phones', page)


class OutputSafetyTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.base = Path(directory.name).resolve()
        self.root = self.base / 'checkout'
        self.source = self.root / 'docs/site'
        self.sentinels = {}
        self.keep(self.source / 'assets/site.css', 'body{}')
        self.keep(self.source / 'assets/screens/keep.txt', 'source asset')
        self.keep(self.source / 'index.html',
                  '<link href="assets/site.css" rel="stylesheet">'
                  '<p>{{RELEASE_TAG}}</p><ul>{{UPCOMING_FEATURES}}</ul>')
        self.keep(self.source / 'README.md', 'source notes')
        self.keep(self.root / 'tooling/keep.txt', 'repository tooling')
        self.keep(self.root / 'README.md', 'repository notes')
        for name, value in (('ROOT', self.root), ('SOURCE', self.source)):
            patcher = patch.object(build, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)
        network = patch('urllib.request.urlopen', side_effect=AssertionError('offline test used the network'))
        network.start()
        self.addCleanup(network.stop)

    def keep(self, path, content):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding='utf-8')
        self.sentinels[path] = path.read_bytes()
        return path

    def assert_sentinels_unchanged(self):
        for path, content in self.sentinels.items():
            self.assertEqual(content, path.read_bytes(), str(path))

    def assert_rejected(self, out, *, cli=False):
        existed = out.exists()
        was_symlink = out.is_symlink()
        if cli:
            roadmap = self.base / 'roadmap.json'
            roadmap.write_text('[]', encoding='utf-8')
            argv = ['build.py', '--out', str(out), '--tag', 'v1.2',
                    '--repo', 'owner/repo', '--roadmap-json', str(roadmap)]
        # These fail safely even when this regression is run against the old builder.
        with patch.object(build.shutil, 'rmtree', side_effect=AssertionError('unsafe output reached deletion')) as remove, \
                patch.object(build.shutil, 'copytree', side_effect=AssertionError('unsafe output reached copying')) as copy, \
                patch.object(build, 'fetch_roadmap', return_value=[]) as fetch:
            with self.assertRaisesRegex(SystemExit, 'output'):
                if cli:
                    with patch('sys.argv', argv):
                        build.main()
                else:
                    build.build(out, 'v1.2', 'owner/repo')
            remove.assert_not_called()
            copy.assert_not_called()
            fetch.assert_not_called()
        self.assert_sentinels_unchanged()
        self.assertEqual(existed, out.exists())
        self.assertEqual(was_symlink, out.is_symlink())

    def test_existing_source_descendants_are_rejected_before_deletion(self):
        for out in (self.source / 'assets', self.source / 'assets/screens'):
            with self.subTest(out=out):
                self.assert_rejected(out)

    def test_missing_nested_source_and_checkout_paths_are_not_created(self):
        for out in (self.source / 'generated', self.source / 'assets/new/nested',
                    self.source / '_site', self.source / 'build/site',
                    self.root / 'tooling/new/nested', self.root / 'new-output/nested'):
            with self.subTest(out=out):
                self.assertFalse(out.exists())
                self.assert_rejected(out)

    def test_repository_directories_files_and_prefix_lookalikes_are_protected(self):
        directories = [self.root / name for name in
                       ('tooling', 'docs/assets', 'fastlane', '.git', '_site-backup', 'build-tools')]
        for directory in directories:
            self.keep(directory / 'keep.txt', 'keep repository files')
        for out in (*directories, self.root / 'README.md', self.source / 'index.html'):
            with self.subTest(out=out):
                self.assert_rejected(out)

    def test_source_checkout_and_ancestors_are_protected(self):
        for out in (self.source, self.source.parent, self.root, self.base, self.base.parent):
            with self.subTest(out=out):
                self.assert_rejected(out)

    def test_relative_paths_and_parent_traversal_cannot_bypass_validation(self):
        (self.root / '_site').mkdir()
        (self.root / 'build/nested').mkdir(parents=True)
        previous = Path.cwd()
        try:
            os.chdir(self.root)
            for out in map(Path, ('docs/site/assets', '_site/../tooling',
                                  'build/nested/../../docs/site/assets', '.', '..')):
                with self.subTest(out=out):
                    self.assert_rejected(out)
        finally:
            os.chdir(previous)

    def test_external_symlinks_cannot_target_repository_paths(self):
        targets = (self.source, self.source / 'assets', self.root / 'tooling',
                   self.root, self.root / 'README.md')
        for number, target in enumerate(targets):
            alias = self.base / f'alias-{number}'
            alias.symlink_to(target, target_is_directory=target.is_dir())
            for out in (alias, alias / 'new/nested') if target.is_dir() else (alias,):
                with self.subTest(out=out, target=target):
                    self.assert_rejected(out)

    def test_disposable_root_symlinks_cannot_bless_protected_targets(self):
        for name, target in (('_site', self.source / 'assets'), ('build', self.root / 'tooling')):
            alias = self.root / name
            alias.symlink_to(target, target_is_directory=True)
            for out in (alias, alias / 'new/nested'):
                with self.subTest(out=out, target=target):
                    self.assert_rejected(out)

    def test_nested_disposable_symlinks_cannot_escape_to_checkout_files(self):
        for name, target in (('_site', self.root / 'tooling'), ('build', self.source / 'assets')):
            alias = self.root / name / 'redirect'
            alias.parent.mkdir()
            alias.symlink_to(target, target_is_directory=True)
            for out in (alias, alias / 'new/nested'):
                with self.subTest(out=out, target=target):
                    self.assert_rejected(out)

    def test_lexical_repository_paths_are_protected_when_symlinks_point_outside(self):
        target = self.base / 'external-assets'
        self.keep(target / 'keep.txt', 'external source dependency')
        for parent in (self.source / 'assets', self.root / 'tooling'):
            alias = parent / 'external'
            alias.symlink_to(target, target_is_directory=True)
            for out in (alias, alias / 'new/nested'):
                with self.subTest(out=out):
                    self.assert_rejected(out)

    def test_cli_preserves_source_symlink_spelling_for_validation(self):
        target = self.base / 'external-assets'
        self.keep(target / 'keep.txt', 'external source dependency')
        alias = self.source / 'assets/external'
        alias.symlink_to(target, target_is_directory=True)
        for out in (alias, alias / 'new/nested'):
            with self.subTest(out=out):
                self.assert_rejected(out, cli=True)

    def test_resolved_source_and_checkout_are_both_protected(self):
        external_source = self.base / 'external-source'
        self.keep(external_source / 'assets/keep.txt', 'external source')
        source_alias = self.root / 'docs/source-link'
        source_alias.symlink_to(external_source, target_is_directory=True)
        with patch.object(build, 'SOURCE', source_alias):
            for out in (external_source, external_source / 'assets',
                        external_source / 'new/nested', source_alias / 'assets',
                        self.root, self.root / 'tooling'):
                with self.subTest(out=out):
                    self.assert_rejected(out)

    def test_disposable_directories_support_initial_and_repeat_builds(self):
        for out in (self.root / '_site', self.root / '_site/preview/nested',
                    self.root / 'build', self.root / 'build/site/nested',
                    self.base / 'out', self.base / 'external/nested/out'):
            with self.subTest(out=out):
                build.build(out, 'v1.2', 'owner/repo', [discussion(5, 'Linked phones')])
                page = (out / 'index.html').read_text(encoding='utf-8')
                self.assertIn('v1.2', page)
                self.assertIn('https://github.com/owner/repo/discussions/5', page)
                self.assertNotIn('{{', page)
                self.assertTrue((out / 'assets/screens/keep.txt').is_file())
                self.assertFalse((out / 'README.md').exists())
                stale = out / 'obsolete.txt'
                stale.write_text('old build', encoding='utf-8')
                build.build(out, 'v1.3', 'owner/repo', [])
                self.assertFalse(stale.exists())
                self.assertIn('v1.3', (out / 'index.html').read_text(encoding='utf-8'))
                self.assert_sentinels_unchanged()

    def test_safe_symlink_outputs_remain_usable(self):
        targets = ((self.base / 'output-alias', self.root / '_site'),
                   (self.root / 'build', self.base / 'external-output'))
        for alias, target in targets:
            alias.symlink_to(target, target_is_directory=True)
            with self.subTest(alias=alias, target=target):
                build.build(alias, 'v1.2', 'owner/repo', [])
                (target / 'obsolete.txt').write_text('old build', encoding='utf-8')
                build.build(alias, 'v1.3', 'owner/repo', [])
                self.assertTrue(alias.is_symlink())
                self.assertFalse((target / 'obsolete.txt').exists())
                self.assertIn('v1.3', (target / 'index.html').read_text(encoding='utf-8'))
                self.assert_sentinels_unchanged()


class ObtainiumTest(unittest.TestCase):
    def test_filter_selects_the_canonical_apk(self):
        import re
        config = json.loads(Path(build.__file__).with_name('obtainium.json').read_text())
        settings = json.loads(config['additionalSettings'])
        self.assertRegex('collie.apk', settings['apkFilterRegEx'])
        self.assertIsNone(re.fullmatch(settings['apkFilterRegEx'], 'collie-fcm.apk'))
        self.assertFalse(settings['includePrereleases'])

    def test_handoff_keeps_github_updates_and_collie_homepage_separate(self):
        import re
        from urllib.parse import unquote
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory) / 'out'
            build.build(out, 'v1.2', '0cwa/collieim-android', [])
            page = (out / 'obtainium/index.html').read_text()
            uri = re.search(r'href="(obtainium://app/[^"]+)"', page).group(1)
            config = json.loads(unquote(uri.removeprefix('obtainium://app/')))
            self.assertEqual('im.collie.app', config['id'])
            self.assertEqual('https://github.com/0cwa/collieim-android', config['url'])
            self.assertIn('href="https://collie.im/#get"', page)
            self.assertNotIn('GetCollie', page)
            self.assertNotIn('{{', page)


if __name__ == '__main__':
    unittest.main()
