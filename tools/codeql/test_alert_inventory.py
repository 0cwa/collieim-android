"""Verify that the inventory is complete, scoped, and contains no raw alert text."""

import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import alert_inventory


REPOSITORY = 'owner/repository'
REF = 'refs/heads/main'


def alert(number=1):
    return {
        'number': number,
        'state': 'open',
        'tool': {'name': 'CodeQL'},
        'rule': {'id': 'py/example', 'severity': 'error', 'security_severity_level': 'high'},
        'html_url': f'https://github.com/{REPOSITORY}/security/code-scanning/{number}',
        'dismissed_comment': 'DO NOT COPY reviewer text',
        'most_recent_instance': {
            'ref': REF,
            'commit_sha': 'a' * 40,
            'analysis_key': 'workflow:job',
            'category': '/language:python',
            'message': {'text': 'DO NOT COPY detailed source text'},
            'location': {'path': 'tool.py', 'start_line': 1, 'end_line': 2,
                         'snippet': {'text': 'DO NOT COPY code snippet'}},
        },
    }


class InventoryTests(unittest.TestCase):
    def test_all_pages_are_reduced_without_snippets_or_comments(self):
        result = alert_inventory.reduce_alerts([[alert(3)], [alert(1)], []], REPOSITORY, REF)
        self.assertEqual(result['status'], 'complete')
        self.assertEqual(result['alert_count'], 2)
        self.assertEqual(result['page_count'], 3)
        self.assertEqual([item['number'] for item in result['alerts']], [1, 3])
        self.assertNotIn('DO NOT COPY', json.dumps(result))
        self.assertEqual(result['alerts'][0]['commit_sha'], 'a' * 40)

    def test_empty_successful_page_is_different_from_missing_pages(self):
        self.assertEqual(alert_inventory.reduce_alerts([[]], REPOSITORY, REF)['alert_count'], 0)
        for pages in ([], {}, [alert()], [[None]]):
            with self.subTest(pages=pages), self.assertRaises(ValueError):
                alert_inventory.reduce_alerts(pages, REPOSITORY, REF)

    def test_duplicate_or_missing_alert_identity_fails(self):
        with self.assertRaises(ValueError):
            alert_inventory.reduce_alerts([[alert()], [alert()]], REPOSITORY, REF)
        for number in (None, True, 0, '1'):
            item = alert()
            item['number'] = number
            with self.subTest(number=number), self.assertRaises(ValueError):
                alert_inventory.reduce_alerts([[item]], REPOSITORY, REF)

    def test_wrong_tool_state_or_ref_cannot_claim_a_complete_inventory(self):
        wrong_tool = alert()
        wrong_tool['tool']['name'] = 'Other scanner'
        wrong_state = alert()
        wrong_state['state'] = 'fixed'
        wrong_ref = alert()
        wrong_ref['most_recent_instance']['ref'] = 'refs/heads/another'
        for item in (wrong_tool, wrong_state, wrong_ref):
            with self.subTest(item=item), self.assertRaises(ValueError):
                alert_inventory.reduce_alerts([[item]], REPOSITORY, REF)

    def test_missing_required_metadata_fails(self):
        for field in ('rule', 'most_recent_instance'):
            item = alert()
            del item[field]
            with self.subTest(field=field), self.assertRaises(KeyError):
                alert_inventory.reduce_alerts([[item]], REPOSITORY, REF)

    def test_invalid_api_data_is_unavailable_and_returns_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'inventory.json'
            with patch('sys.stdin', io.StringIO('not JSON')), patch.dict('os.environ', {'GITHUB_STEP_SUMMARY': ''}), \
                    contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                status = alert_inventory.main(['--repository', REPOSITORY, '--ref', REF, '--output', str(path)])
            self.assertEqual(status, 1)
            report = json.loads(path.read_text())
            self.assertEqual(report['status'], 'unavailable')
            self.assertNotIn('alert_count', report)

    def test_explicit_fork_unavailability_does_not_report_zero_alerts(self):
        report = alert_inventory.unavailable(REPOSITORY, REF, 'Fork token cannot read alerts.')
        self.assertEqual(report['status'], 'unavailable')
        self.assertNotIn('alerts', report)
        self.assertNotIn('alert_count', report)


if __name__ == '__main__':
    unittest.main()
