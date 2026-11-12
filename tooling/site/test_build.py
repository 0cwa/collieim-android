"""Checks for generated roadmap content and safe output handling."""
import io
import json
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
