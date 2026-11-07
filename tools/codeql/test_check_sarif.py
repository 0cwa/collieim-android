"""Security regression tests for the independent CodeQL result gate."""

import contextlib
import copy
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import check_sarif


SOURCE = 'tools/patches/release.py'


def clean_sarif():
    return {
        'version': '2.1.0',
        'runs': [{
            'tool': {'driver': {
                'name': 'CodeQL',
                'semanticVersion': '2.27.4',
                'rules': [{'id': 'py/example-security-query'}],
            }},
            'invocations': [{
                'executionSuccessful': True,
                'toolExecutionNotifications': [{
                    'descriptor': {'id': 'py/diagnostics/successfully-extracted-files'},
                    'level': 'none',
                    'locations': [{'physicalLocation': {'artifactLocation': {'uri': SOURCE}}}],
                }],
            }],
            'results': [],
        }],
    }


class SarifGateTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.sarif = self.directory / 'python.sarif'

    def check(self, document, sources=(SOURCE,)):
        self.sarif.write_text(json.dumps(document))
        return check_sarif.check_directory(self.directory, 'python', sources)

    def test_clean_successful_scan_passes(self):
        result = self.check(clean_sarif())
        self.assertTrue(result['passed'])
        self.assertEqual(result['extracted_source_count'], 1)

    def test_missing_malformed_or_empty_output_cannot_pass(self):
        self.assertFalse(check_sarif.check_directory(self.directory, 'python', [SOURCE])['passed'])
        self.sarif.write_text('{not json')
        self.assertFalse(check_sarif.check_directory(self.directory, 'python', [SOURCE])['passed'])
        for document in ({}, {'version': '2.1.0', 'runs': []}, {'version': '2.1.0', 'runs': [{}]}):
            with self.subTest(document=document):
                self.assertFalse(self.check(document)['passed'])

    def test_old_or_suppressed_findings_still_fail(self):
        for extra in ({}, {'baselineState': 'unchanged'}, {'level': 'note'},
                      {'suppressions': [{'kind': 'inSource', 'status': 'accepted'}]}):
            with self.subTest(extra=extra):
                document = clean_sarif()
                document['runs'][0]['results'] = [{'ruleId': 'py/example-security-query', **extra}]
                result = self.check(document)
                self.assertFalse(result['passed'])
                self.assertEqual(result['finding_count'], 1)

    def test_missing_queries_or_wrong_language_cannot_pass(self):
        for rules in ([], [{'id': 'js/example-security-query'}]):
            with self.subTest(rules=rules):
                document = clean_sarif()
                document['runs'][0]['tool']['driver']['rules'] = rules
                self.assertFalse(self.check(document)['passed'])

    def test_unsuccessful_or_missing_invocation_cannot_pass(self):
        for invocations in ([], [{}], [{'executionSuccessful': False}]):
            with self.subTest(invocations=invocations):
                document = clean_sarif()
                document['runs'][0]['invocations'] = invocations
                self.assertFalse(self.check(document)['passed'])

    def test_warning_and_error_diagnostics_fail(self):
        for level in ('warning', 'error'):
            for key in ('toolExecutionNotifications', 'toolConfigurationNotifications'):
                with self.subTest(level=level, key=key):
                    document = clean_sarif()
                    invocation = document['runs'][0]['invocations'][0]
                    invocation.setdefault(key, []).append({
                        'descriptor': {'id': 'py/diagnostics/extraction-warnings'},
                        'level': level,
                        'message': {'text': 'A source file could not be parsed.'},
                    })
                    self.assertFalse(self.check(document)['passed'])

    def test_informational_limitations_are_retained_without_failing(self):
        document = clean_sarif()
        invocation = document['runs'][0]['invocations'][0]
        invocation['toolExecutionNotifications'].append({
            'descriptor': {'id': 'cpp/autobuilder/buildless/mode-active'},
            'level': 'note',
            'message': {'text': 'C/C++ was extracted with build-mode set to none.'},
        })
        result = self.check(document)
        self.assertTrue(result['passed'])
        self.assertEqual(len(result['runs'][0]['diagnostics']), 1)

    def test_empty_or_incomplete_extraction_cannot_pass(self):
        document = clean_sarif()
        document['runs'][0]['invocations'][0]['toolExecutionNotifications'] = []
        self.assertFalse(self.check(document)['passed'])
        self.assertFalse(self.check(clean_sarif(), sources=(SOURCE, 'another/source.py'))['passed'])

    def test_action_grouped_query_metadata_and_artifact_references_are_supported(self):
        document = clean_sarif()
        run = document['runs'][0]
        run['tool']['extensions'] = [{'name': 'codeql/python-queries',
                                     'rules': run['tool']['driver'].pop('rules')}]
        run['artifacts'] = [{'location': {'uri': SOURCE}}]
        run['invocations'][0]['toolExecutionNotifications'][0]['locations'] = [
            {'physicalLocation': {'artifactLocation': {'index': 0}}}]
        self.assertTrue(self.check(document)['passed'])
        run['invocations'][0]['toolExecutionNotifications'][0]['locations'][0][
            'physicalLocation']['artifactLocation']['index'] = -1
        self.assertFalse(self.check(document)['passed'])

    def test_a_second_failing_run_or_file_cannot_be_hidden(self):
        document = clean_sarif()
        extra_run = copy.deepcopy(document['runs'][0])
        extra_run['results'] = [{'ruleId': 'py/example-security-query'}]
        document['runs'].append(extra_run)
        self.assertFalse(self.check(document)['passed'])
        (self.directory / 'other.sarif').write_text(json.dumps({'version': '2.1.0', 'runs': [extra_run]}))
        self.assertFalse(self.check(clean_sarif())['passed'])

    def test_cli_writes_machine_report_and_returns_failure_for_findings(self):
        document = clean_sarif()
        document['runs'][0]['results'] = [{
            'ruleId': 'py/example-security-query',
            'message': {'text': 'Source text\n::notice::must stay escaped'},
        }]
        self.sarif.write_text(json.dumps(document))
        output = io.StringIO()
        report = self.directory / 'check.json'
        with contextlib.redirect_stdout(output), patch.dict('os.environ', {'GITHUB_STEP_SUMMARY': ''}):
            status = check_sarif.main([
                '--sarif-dir', str(self.directory), '--language', 'python',
                '--require-source', SOURCE, '--report', str(report),
            ])
        self.assertEqual(status, 1)
        self.assertEqual(json.loads(report.read_text())['finding_count'], 1)
        self.assertNotIn('\n::notice::', output.getvalue())

    def reviewed_fixture(self):
        source = self.directory / SOURCE
        source.parent.mkdir(parents=True)
        source.write_text('reviewed source\n')
        document = clean_sarif()
        document['runs'][0]['results'] = [{
            'ruleId': 'py/example-security-query',
            'locations': [{'physicalLocation': {'artifactLocation': {'uri': SOURCE}}}],
            'partialFingerprints': {'primaryLocationLineHash': '1234567890abcdef:1'},
        }]
        review = {
            'id': 'reviewed-test-boundary',
            'language': 'python',
            'rule': 'py/example-security-query',
            'path': SOURCE,
            'primary_location_line_hash': '1234567890abcdef:1',
            'source_sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
            'rationale': 'Test fixture for a narrowly reviewed trust boundary.',
        }
        review_path = self.directory / 'reviewed.json'
        review_path.write_text(json.dumps({'schema': 1, 'findings': [review]}))
        return document, review_path, source

    def check_reviewed(self, document, review_path):
        self.sarif.write_text(json.dumps(document))
        return check_sarif.check_directory(self.directory, 'python', [SOURCE], review_path, self.directory)

    def test_reviewed_result_remains_visible_and_raw_sarif_is_not_modified(self):
        document, review_path, _ = self.reviewed_fixture()
        result = self.check_reviewed(document, review_path)
        self.assertTrue(result['passed'])
        self.assertEqual(result['finding_count'], 1)
        self.assertEqual(result['reviewed_finding_count'], 1)
        self.assertEqual(result['unreviewed_finding_count'], 0)
        self.assertEqual(len(result['runs'][0]['findings']), 1)
        self.assertEqual(json.loads(self.sarif.read_text()), document)

    def test_any_reviewed_source_drift_invalidates_the_disposition(self):
        document, review_path, source = self.reviewed_fixture()
        source.write_text('reviewed source\nchanged permissions\n')
        result = self.check_reviewed(document, review_path)
        self.assertFalse(result['passed'])
        self.assertEqual(result['reviewed_finding_count'], 0)
        self.assertIn('reviewed source changed', '\n'.join(result['errors']))

    def test_review_does_not_cover_a_new_rule_fingerprint_or_extra_location(self):
        document, review_path, _ = self.reviewed_fixture()
        for change in ('rule', 'fingerprint', 'extra-location', 'duplicate-result'):
            with self.subTest(change=change):
                modified = copy.deepcopy(document)
                finding = modified['runs'][0]['results'][0]
                if change == 'rule':
                    finding['ruleId'] = 'py/different-rule'
                elif change == 'fingerprint':
                    finding['partialFingerprints']['primaryLocationLineHash'] = 'new-fingerprint'
                elif change == 'extra-location':
                    finding['locations'].append(copy.deepcopy(finding['locations'][0]))
                else:
                    modified['runs'][0]['results'].append(copy.deepcopy(finding))
                self.assertFalse(self.check_reviewed(modified, review_path)['passed'])

    def test_stale_review_must_be_removed_when_the_finding_disappears(self):
        _, review_path, _ = self.reviewed_fixture()
        self.assertFalse(self.check_reviewed(clean_sarif(), review_path)['passed'])

    def test_source_bound_review_cannot_read_outside_the_checkout(self):
        document, review_path, _ = self.reviewed_fixture()
        review = json.loads(review_path.read_text())
        review['findings'][0]['path'] = '../outside-workspace'
        review_path.write_text(json.dumps(review))
        result = self.check_reviewed(document, review_path)
        self.assertFalse(result['passed'])
        self.assertIn('repository-relative', '\n'.join(result['errors']))


if __name__ == '__main__':
    unittest.main()
