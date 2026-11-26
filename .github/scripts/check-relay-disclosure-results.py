#!/usr/bin/env python3
"""Require the two selected API 35 UnifiedPush flows and their synthetic screenshots."""
import os
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

from PIL import Image, ImageStat

CLASS = 'im.molly.unifiedpush.enrollment.RelayDisclosureUiTest'
METHODS = {
    'addReceiverConsentShowsFourRowsAndExactDeviceWording',
    'confirmedV4ReceiverAppearsInLinkedRelaySettings',
}
SCREENSHOTS = {'relay-08-add-receiver-consent.png', 'relay-09-v4-linked-settings.png'}
ANDROID = '{http://schemas.android.com/apk/res/android}'


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


def check(root, build_outcome, device_outcome, expected_sha):
    evidence = root / 'build/relay-disclosure'
    if (build_outcome, device_outcome) != ('success', 'success'):
        raise ValueError('build or API 35 device step failed')
    source = (evidence / 'source.txt').read_text().splitlines()
    if len(source) != 2 or any(not re.fullmatch(r'[0-9a-f]{40}', value) for value in source) or source[0] != expected_sha:
        raise ValueError('missing or mismatched source identity')
    if (evidence / 'exit-code.txt').read_text().strip() != '0':
        raise ValueError('connected test command failed')
    if (evidence / 'device-api.txt').read_text().strip() != '35' or not (evidence / 'device-fingerprint.txt').read_text().strip():
        raise ValueError('missing API 35 device identity')
    app = ET.parse(evidence / 'app-manifest.xml').getroot()
    test = ET.parse(evidence / 'test-manifest.xml').getroot()
    runners = test.findall('instrumentation')
    if app.get('package') != 'im.collie.app' or test.get('package') != 'im.collie.app.test' or len(runners) != 1 or runners[0].get(ANDROID + 'name') != 'org.thoughtcrime.securesms.testing.SignalTestRunner' or runners[0].get(ANDROID + 'targetPackage') != 'im.collie.app':
        raise ValueError('wrong APK package or instrumentation runner')
    before = hashes(evidence / 'apk-before.txt')
    if before != hashes(evidence / 'apk-after.txt'):
        raise ValueError('APK pair changed during execution')
    expected_apks = {
        'app/build/outputs/apk/prodWebsite/debug/app-prod-website-debug.apk',
        'app/build/outputs/apk/androidTest/prodWebsite/debug/app-prod-website-debug-androidTest.apk',
    }
    if set(before) != expected_apks:
        raise ValueError('expected matching prodWebsite app and Android test APKs')

    cases = []
    for path in (evidence / 'results').rglob('TEST-*.xml'):
        report = ET.parse(path).getroot()
        for suite in report.iter('testsuite'):
            if any(int(suite.get(key, '0')) for key in ('failures', 'errors', 'skipped')):
                raise ValueError(f'failed or skipped test suite: {path}')
        for case in report.iter('testcase'):
            if case.get('classname') != CLASS or any(case.find(tag) is not None for tag in ('failure', 'error', 'skipped')):
                raise ValueError(f'wrong class or nonpassing test case: {path}')
            cases.append(case.get('name'))
    if len(cases) != 2 or set(cases) != METHODS:
        raise ValueError(f'missing, duplicate, or unexpected selected test cases: {cases}')

    screenshots = list((evidence / 'screenshots').glob('*.png'))
    if len(screenshots) != len(SCREENSHOTS) or {path.name for path in screenshots} != SCREENSHOTS:
        raise ValueError('missing, duplicate, or unexpected screenshots')
    for path in screenshots:
        with Image.open(path, formats=['PNG']) as image:
            image.verify()
        with Image.open(path, formats=['PNG']) as image:
            image.load()
            if min(image.size) < 50 or max(ImageStat.Stat(image.convert('RGB')).var) < 1:
                raise ValueError(f'blank or undersized screenshot: {path.name}')
    report = [f'Source commit: {source[0]}', 'Device API: 35', 'Passing tests:']
    report.extend(f'- {name}' for name in sorted(cases))
    report.append('Screenshots:')
    report.extend(f'- {path.name} ({path.stat().st_size} bytes)' for path in sorted(screenshots))
    (evidence / 'report.txt').write_text('\n'.join(report) + '\n')
    print('Passed both selected UnifiedPush UI tests on API 35 with matching APKs and nonempty screenshots')


if __name__ == '__main__':
    try:
        check(Path.cwd(), os.environ.get('BUILD_OUTCOME'), os.environ.get('DEVICE_OUTCOME'), os.environ.get('EXPECTED_SHA'))
    except (OSError, ValueError, ET.ParseError) as error:
        print(f'Relay disclosure validation failed: {error}', file=sys.stderr)
        sys.exit(1)
