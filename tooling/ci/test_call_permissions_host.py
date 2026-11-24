"""Reject incomplete host evidence and source inventories before claiming admission proof."""

import copy
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

import call_permissions_host as host


class CallPermissionsHostTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.app = 'app/src/test/java/example/AppTest.kt'
        self.settings = 'feature/app-settings/src/test/java/example/SettingsTest.kt'
        for filename, name, methods in [(self.app, 'AppTest', ['one', 'two']), (self.settings, 'SettingsTest', ['three'])]:
            path = self.root / filename
            path.parent.mkdir(parents=True)
            path.write_text('package example\nclass ' + name + ' {\n' + ''.join(f'  @Test\n  fun `{method}`() {{}}\n' for method in methods) + '}\n')
        (self.root / 'gradle').mkdir()
        (self.root / 'gradle/test-libs.versions.toml').write_text('[versions]\nrobolectric = "4.16"\n')
        manifest = {'schema': 1, 'robolectric_version': '4.16', 'expected_classes': 2, 'expected_methods': 3,
                    'tasks': {':app:testProdWebsiteDebugUnitTest': {self.app: 2}, ':feature:app-settings:testDebugUnitTest': {self.settings: 1}}}
        (self.root / host.MANIFEST).parent.mkdir(parents=True)
        (self.root / host.MANIFEST).write_text(json.dumps(manifest))
        self.selected = host.inventory(self.root)
        self.write_reports()

    def write_reports(self):
        self.reports = []
        for suite in self.selected['suites']:
            report = ET.Element('testsuite', name=suite['class_name'], tests=str(len(suite['methods'])), failures='0', errors='0', skipped='0')
            for method in suite['methods']:
                ET.SubElement(report, 'testcase', classname=suite['class_name'], name=method)
            path = self.root / host.REPORTS[suite['task']] / ('TEST-' + suite['class_name'] + '.xml')
            path.parent.mkdir(parents=True, exist_ok=True)
            ET.ElementTree(report).write(path)
            self.reports.append(path)

    def change_report(self, mutate):
        report = ET.parse(self.reports[0]).getroot()
        mutate(report)
        ET.ElementTree(report).write(self.reports[0])

    def rejected(self):
        with self.assertRaises(ValueError):
            host.check_reports(self.root, self.selected)

    def test_current_inventory_covers_all_44_classes_and_309_methods(self):
        actual = host.inventory(host.ROOT)
        self.assertEqual(44, len(actual['suites']))
        self.assertEqual(309, sum(len(s['methods']) for s in actual['suites']))

    def test_complete_exact_method_reports_pass(self):
        self.assertEqual(3, sum(row['passed'] for row in host.check_reports(self.root, self.selected)))

    def test_missing_suite_or_method_is_rejected(self):
        self.reports[1].unlink()
        self.rejected()
        self.write_reports()
        self.change_report(lambda report: report.remove(report.findall('testcase')[0]))
        self.rejected()

    def test_duplicate_suite_or_method_is_rejected(self):
        duplicate = self.reports[0].with_name('TEST-duplicate.xml')
        duplicate.write_bytes(self.reports[0].read_bytes())
        self.rejected()
        duplicate.unlink()
        self.change_report(lambda report: report.append(copy.deepcopy(report.findall('testcase')[0])))
        self.rejected()

    def test_failure_error_skip_and_class_setup_result_cannot_pass(self):
        for tag in ['failure', 'error', 'skipped']:
            with self.subTest(tag=tag):
                self.write_reports()
                self.change_report(lambda report: ET.SubElement(report.findall('testcase')[0], tag))
                self.rejected()
        self.write_reports()
        self.change_report(lambda report: report.findall('testcase')[0].set('name', 'classMethod'))
        self.rejected()

    def test_wrong_class_or_misleading_report_totals_are_rejected(self):
        self.change_report(lambda report: report.findall('testcase')[0].set('classname', 'example.Other'))
        self.rejected()
        self.write_reports()
        self.change_report(lambda report: report.set('tests', '99'))
        self.rejected()
        self.write_reports()
        self.change_report(lambda report: report.attrib.pop('skipped'))
        self.rejected()

    def test_removed_source_class_or_method_is_rejected(self):
        path = self.root / self.app
        original = path.read_text()
        path.write_text(original.replace('  @Test\n  fun `two`() {}\n', ''))
        with self.assertRaises(ValueError):
            host.inventory(self.root)
        path.unlink()
        with self.assertRaises(OSError):
            host.inventory(self.root)

    def test_ambiguous_or_duplicate_source_methods_are_rejected(self):
        path = self.root / self.app
        original = path.read_text()
        for modified in [original.replace('`two`()', '`two`(argument: Int)'), original.replace('`two`', '`one`')]:
            path.write_text(modified)
            with self.assertRaises(ValueError):
                host.inventory(self.root)

    def test_source_edit_after_preparation_invalidates_evidence(self):
        identity = ['a' * 40, 'b' * 40]
        with patch.object(host, 'source', return_value=identity), patch.dict('os.environ', {'GITHUB_SHA': identity[0]}):
            host.prepare(self.root)
            evidence = self.root / host.EVIDENCE
            (evidence / 'source-after.txt').write_text('\n'.join(identity) + '\n')
            (evidence / 'test-exit-code.txt').write_text('0\n')
            path = self.root / self.app
            path.write_text(path.read_text().replace('{}', '{ /* changed */ }'))
            with self.assertRaisesRegex(ValueError, 'inventory/source changed'):
                host.check(self.root, identity[0])

    def test_preparation_clears_only_its_own_stale_evidence_and_reports(self):
        evidence = self.root / host.EVIDENCE
        evidence.mkdir(parents=True)
        for name in ['results.json', 'sdk-receipt.json', 'native-before.json', 'test-exit-code.txt',
                     'source-after.txt', 'host-tests.log', 'prefetch.log', '_old_task.json']:
            (evidence / name).write_text('stale')
        unrelated = self.root / 'app/build/test-results/anotherTask/TEST-other.xml'
        unrelated.parent.mkdir(parents=True)
        unrelated.write_text('keep')
        identity = ['a' * 40, 'b' * 40]
        with patch.object(host, 'source', return_value=identity), patch.dict('os.environ', {'GITHUB_SHA': identity[0]}):
            host.prepare(self.root)
        self.assertEqual({'inventory.json', 'source-before.txt'}, {p.name for p in evidence.iterdir()})
        self.assertTrue(unrelated.exists())
        self.assertTrue(all(not p.exists() for p in self.reports))

    def test_download_attempts_and_total_deadline_are_bounded(self):
        failure = subprocess.CompletedProcess([], 22, '', 'HTTP 429')
        with patch.object(host.time, 'monotonic', return_value=1), patch.object(host.subprocess, 'run', return_value=failure) as run:
            with self.assertRaisesRegex(ValueError, 'Bounded SDK download failed'):
                host.download(host.MAVEN + '/artifact.jar', self.root / 'partial', 1000)
            self.assertEqual(2, run.call_count)
            for call in run.call_args_list:
                self.assertEqual(181, call.kwargs['timeout'])
                self.assertIn('--max-time', call.args[0])
                self.assertIn('--connect-timeout', call.args[0])
        with patch.object(host.time, 'monotonic', return_value=1001), patch.object(host.subprocess, 'run') as run:
            with self.assertRaisesRegex(ValueError, 'total time budget'):
                host.download(host.MAVEN + '/artifact.jar', self.root / 'partial', 1000)
            run.assert_not_called()

    def test_wrong_official_checksum_cannot_install_a_jar(self):
        (self.root / host.EVIDENCE).mkdir(parents=True)

        def fake_download(url, target, deadline):
            target.write_bytes(b'0' * 128 if url.endswith('.sha512') else b'bad jar')

        with patch.object(host, 'download', side_effect=fake_download):
            with self.assertRaisesRegex(ValueError, 'SHA-512 mismatch'):
                host.prefetch(self.root)
        self.assertFalse(list((self.root / host.SDK_DIRECTORY).glob('*.jar')))
        self.assertFalse((self.root / host.EVIDENCE / 'sdk-receipt.json').exists())

    def test_jar_download_uses_mirror_before_canonical_fallback(self):
        url = host.MAVEN + '/artifact.jar'
        mirror = 'https://maven-central.storage-download.googleapis.com/maven2/org/robolectric/android-all-instrumented/artifact.jar'
        failure = subprocess.CompletedProcess([], 22, '', 'HTTP 429')
        success = subprocess.CompletedProcess([], 0, '', '')
        with patch.object(host.time, 'monotonic', return_value=1), patch.object(host.subprocess, 'run', side_effect=[failure, success]) as run:
            host.download(url, self.root / 'partial', 1000)
        self.assertEqual([mirror, url], [call.args[0][-1] for call in run.call_args_list])

    def test_checksum_download_stays_on_canonical_maven_central(self):
        url = host.MAVEN + '/artifact.jar.sha512'
        failure = subprocess.CompletedProcess([], 22, '', 'HTTP 429')
        success = subprocess.CompletedProcess([], 0, '', '')
        with patch.object(host.time, 'monotonic', return_value=1), patch.object(host.subprocess, 'run', side_effect=[failure, success]) as run:
            host.download(url, self.root / 'checksum', 1000)
        self.assertEqual([url, url], [call.args[0][-1] for call in run.call_args_list])

    def test_all_three_verified_sdks_are_recorded_without_network_fallback(self):
        (self.root / host.EVIDENCE).mkdir(parents=True)
        payload = b'test fixture bytes'
        checksum = hashlib.sha512(payload).hexdigest().encode()

        def fake_download(url, target, deadline):
            target.write_bytes(checksum if url.endswith('.sha512') else payload)

        with patch.object(host, 'download', side_effect=fake_download) as download:
            host.prefetch(self.root)
        receipts = json.loads((self.root / host.EVIDENCE / 'sdk-receipt.json').read_text())
        self.assertEqual({28, 35, 36}, {r['api'] for r in receipts})
        self.assertEqual(6, download.call_count)
        self.assertTrue(all(r['url'].startswith(host.MAVEN + '/') for r in receipts))


if __name__ == '__main__':
    unittest.main()
