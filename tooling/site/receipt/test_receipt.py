"""Checks for the development receipt pilot, independent of release approvals."""
import copy
import json
import unittest
import render
import metrics


class ReceiptTests(unittest.TestCase):
    def setUp(self):
        self.data = json.loads(render.DATA.read_text())

    def test_published_source_reconciles_and_renders_both_layouts(self):
        data = render.validate(self.data)
        for layout in ('atlas', 'story'):
            page = render.render(data, layout)
            self.assertIn('How this release was built', page)
            self.assertIn(data['source'], page)
            self.assertEqual(page.count('<article>'), 4)
            self.assertIn('<details>', page)

    def test_duplicate_ids_and_wrong_source_are_rejected(self):
        bad = copy.deepcopy(self.data)
        bad['features'][1]['id'] = bad['features'][0]['id']
        with self.assertRaises(ValueError):
            render.validate(bad)
        bad = copy.deepcopy(self.data)
        bad['source'] = '0' * 40
        with self.assertRaises(ValueError):
            render.validate(bad)

    def test_editorial_text_is_escaped(self):
        data = copy.deepcopy(self.data)
        data['features'][0]['title'] = '<script>alert(1)</script>'
        self.assertNotIn('<script>', render.render(data, 'story'))

    def test_missing_record_and_changed_source_evidence_are_rejected(self):
        bad = copy.deepcopy(self.data)
        bad['features'].pop()
        with self.assertRaises(ValueError):
            render.validate(bad)

    def test_metrics_are_replayable_and_separate_test_files(self):
        change = '1a0c18c32c5286e9061839cd37d96e432888fd05'
        counts = metrics.change_metrics(change)
        self.assertEqual(counts['tests'], [92, 3])
        self.assertEqual(counts, metrics.change_metrics(change))
        bad = copy.deepcopy(self.data)
        bad['features'][0]['source_blob'] = '0' * 40
        with self.assertRaises(ValueError):
            render.validate(bad)


if __name__ == '__main__':
    unittest.main()
