#!/usr/bin/env python3
"""Require the selected diagnostic suite, APK manifests and unchanged APKs."""
import os
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ANDROID = '{http://schemas.android.com/apk/res/android}'
SUITES = {
    'archive': {
        'class': 'org.thoughtcrime.securesms.backup.v2.ArchiveImportExportTests',
        'methods': {'shard1'},
        'package': 'im.collie.app',
        'runner': 'org.thoughtcrime.securesms.testing.SignalTestRunner',
        'property': '(none)',
    },
    'imo': {
        'class': 'org.thoughtcrime.securesms.messages.incomingmessageobserver.IncomingGroupMessageTest',
        'methods': {'deliveredGroupText_isPersistedInGroupThread', 'multipleGroupMembers_messagesPersistedFromEach'},
        'package': 'im.collie.app.imo',
        'runner': 'org.thoughtcrime.securesms.testing.incomingmessageobserver.IncomingMessageObserverTestRunner',
        'property': '-PimoTests',
    },
}


def hashes(path):
    result = {}
    for line in path.read_text().splitlines():
        digest, filename = line.split(maxsplit=1)
        if not re.fullmatch(r'[0-9a-f]{64}', digest) or filename in result:
            raise ValueError('invalid or duplicate APK digest')
        result[filename] = digest
    if len(result) != 2 or sum('/androidTest/' in name for name in result) != 1:
        raise ValueError('expected one app and one test APK')
    return result


def check(root, build_outcome, device_outcome, suite, expected_sha):
    if suite not in SUITES:
        raise ValueError('unknown diagnostic suite')
    expected = SUITES[suite]
    evidence = root / 'build/archive-diagnostic'
    if build_outcome != 'success' or device_outcome != 'success':
        raise ValueError('build or device step failed')
    source = (evidence / 'source.txt').read_text().splitlines()
    if len(source) != 2 or any(not re.fullmatch(r'[0-9a-f]{40}', value) for value in source) or source[0] != expected_sha:
        raise ValueError('missing or mismatched source identity')
    configuration = [suite, expected['property']]
    if (evidence / 'configuration.txt').read_text().splitlines() != configuration:
        raise ValueError('incorrect suite or Gradle property')
    app_manifest = ET.parse(evidence / 'app-manifest.xml').getroot()
    test_manifest = ET.parse(evidence / 'test-manifest.xml').getroot()
    if app_manifest.get('package') != expected['package'] or test_manifest.get('package') != expected['package'] + '.test':
        raise ValueError('unexpected app or test package')
    instrumentation = test_manifest.findall('instrumentation')
    if len(instrumentation) != 1 or instrumentation[0].get(ANDROID + 'name') != expected['runner'] or instrumentation[0].get(ANDROID + 'targetPackage') != expected['package']:
        raise ValueError('unexpected instrumentation runner or target package')
    if (evidence / 'exit-code.txt').read_text().strip() != '0':
        raise ValueError('connected test command failed')
    if (evidence / 'device-api.txt').read_text().strip() != '35':
        raise ValueError('device is not API 35')
    if not (evidence / 'device-fingerprint.txt').read_text().strip():
        raise ValueError('missing device fingerprint')
    if hashes(evidence / 'apk-before.txt') != hashes(evidence / 'apk-after.txt'):
        raise ValueError('APK pair changed during execution')
    cases = []
    suites = []
    for path in (root / 'app/build/outputs/androidTest-results').rglob('TEST-*.xml'):
        report = ET.parse(path).getroot()
        for item in report.iter('testsuite'):
            suites.append(item)
            if any(int(item.get(key, '0')) for key in ('failures', 'errors', 'skipped')):
                raise ValueError('test suite failed or skipped')
        for case in report.iter('testcase'):
            if case.get('classname') != expected['class'] or any(case.find(tag) is not None for tag in ('failure', 'error', 'skipped')):
                raise ValueError('wrong test class or nonpassing case')
            cases.append(case.get('name'))
    methods = expected['methods']
    if not suites or (suite == 'archive' and len(suites) != 1) or sum(int(item.get('tests', '0')) for item in suites) != len(methods) or len(cases) != len(methods) or set(cases) != methods:
        raise ValueError(f'expected exactly {sorted(methods)}; got {cases}')
    print(f'Passed {suite}: {expected["class"]} {sorted(methods)}, matching APK pair and API 35')


if __name__ == '__main__':
    try:
        check(Path.cwd(), os.environ.get('BUILD_OUTCOME'), os.environ.get('DEVICE_OUTCOME'), os.environ.get('DIAG_SUITE'), os.environ.get('EXPECTED_SHA'))
    except (OSError, ValueError, ET.ParseError) as error:
        print(f'Device diagnostic failed: {error}', file=sys.stderr)
        sys.exit(1)
