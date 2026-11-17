"""Focused checks for the experimental receipt renderer."""
import copy
import json
import unittest
import render
import metrics


class ReceiptTests(unittest.TestCase):
    def setUp(self):
        self.data = json.loads(render.DATA.read_text())

    def test_release_and_app_are_separate_and_source_bound(self):
        data = render.validate(self.data)
        app = render.render(data, 'app')
        release = render.render(data, 'release')
        self.assertIn('background connection enabled', app)
        self.assertNotIn('background connection enabled', release)
        self.assertIn('Find Stories in Settings', release)
        self.assertIn(data['source'], release)
        self.assertIn('<details>', app)
        self.assertIn('whole app', app)
        self.assertIn('Restored by 0cwa in Collie.', release)

    def test_wrong_source_missing_blob_and_absence_are_rejected(self):
        for mutate in ('source', 'blob', 'absent'):
            bad = copy.deepcopy(self.data)
            if mutate == 'source': bad['source'] = '0' * 40
            if mutate == 'blob': bad['features'][0]['source_blob'] = '0' * 40
            if mutate == 'absent': bad['features'][-1]['absent_paths'] = [bad['features'][0]['path']]
            with self.subTest(mutate=mutate), self.assertRaises(ValueError):
                render.validate(bad)

    def test_release_story_requires_a_real_tree_difference(self):
        bad = copy.deepcopy(self.data)
        bad['release_changes'][0]['path'] = bad['features'][1]['path']
        with self.assertRaises(ValueError):
            render.validate(bad)

    def test_editorial_text_is_escaped(self):
        data = copy.deepcopy(self.data)
        data['features'][1]['summary'] = '<script>alert(1)</script>'
        self.assertNotIn('<script>', render.render(data, 'app'))

    def test_metrics_are_replayable_and_separate_categories(self):
        change = '1a0c18c32c5286e9061839cd37d96e432888fd05'
        counts = metrics.change_metrics(change)
        self.assertEqual(counts['tests'], [92, 3])
        self.assertEqual(counts, metrics.change_metrics(change))
        release = metrics.release_metrics(self.data['release_base'], self.data['source'])
        self.assertEqual(release['production'], [84, 16])
        self.assertEqual(release, metrics.release_metrics(self.data['release_base'], self.data['source']))
        self.assertEqual(metrics.classify('app/src/main/res/values-fr/strings.xml'), 'translations')
        self.assertEqual(metrics.classify('app/src/main/generated/example.java'), 'generated')


if __name__ == '__main__':
    unittest.main()
