#!/usr/bin/env python3
"""Require all host navigation cases and the selected-route device case."""
import os
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

from PIL import Image, ImageStat

HOST_CLASS = 'org.signal.appsettings.routes.AppSettingsNavigationTest'
HOST_METHODS = {
    'a split-pane window opens the default detail',
    'a split-pane window keeps the detail it was opened with',
    'a phone stays on the settings list',
    'a split-pane window leaves settings rather than emptying the detail pane',
    'a split-pane window pops stacked detail',
    'a phone pops its only detail',
    'a route the host opens as an activity stays off the back stack',
    'home selection follows a replaced section',
}
DEVICE_CASES = {'org.thoughtcrime.securesms.maps.MapLibreOfflinePickerProofTest': {'pickerRendersVectorRailInTheSharePreview'}}
SCREENSHOTS = {
    'maplibre-offline-picker.png',
    'maplibre-offline-landscape.png',
    'maplibre-offline-share-preview.png',
}
ANDROID = '{http://schemas.android.com/apk/res/android}'


def require_cases(directory, expected):
    cases = []
    suite_counts = {}
    for path in directory.rglob('TEST-*.xml'):
        report = ET.parse(path).getroot()
        for suite in report.iter('testsuite'):
            name = suite.get('name')
            if name not in expected or name in suite_counts or any(int(suite.get(key, '0')) for key in ('failures', 'errors', 'skipped')):
                raise ValueError(f'wrong, duplicate, failed or skipped suite: {path}')
            suite_counts[name] = int(suite.get('tests', '0'))
        for case in report.iter('testcase'):
            if any(case.find(tag) is not None for tag in ('failure', 'error', 'skipped')):
                raise ValueError(f'nonpassing case: {path}')
            cases.append((case.get('classname'), case.get('name')))
    expected_cases = {(classname, case) for classname, methods in expected.items() for case in methods}
    if suite_counts != {classname: len(methods) for classname, methods in expected.items()} or len(cases) != len(expected_cases) or set(cases) != expected_cases:
        raise ValueError(f'missing, duplicate, or unexpected test cases: {cases}')

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


def check(root, host_outcome, build_outcome, device_outcome, expected_sha):
    if (host_outcome, build_outcome, device_outcome) != ('success', 'success', 'success'):
        raise ValueError('host, build or device step failed')
    evidence = root / 'build/navigation-validation'
    identity = (evidence / 'source.txt').read_text().splitlines()
    if len(identity) != 2 or not all(re.fullmatch(r'[0-9a-f]{40}', value) for value in identity) or identity[0] != expected_sha:
        raise ValueError('missing or mismatched source identity')
    if (evidence / 'exit-code.txt').read_text().strip() != '0':
        raise ValueError('device Gradle command failed')
    if (evidence / 'device-api.txt').read_text().strip() != '35':
        raise ValueError('device is not API 35')
    if not (evidence / 'device-fingerprint.txt').read_text().strip():
        raise ValueError('missing device fingerprint')
    app = ET.parse(evidence / 'app-manifest.xml').getroot()
    test = ET.parse(evidence / 'test-manifest.xml').getroot()
    runners = test.findall('instrumentation')
    if app.get('package') != 'im.collie.app' or test.get('package') != 'im.collie.app.test' or len(runners) != 1 or runners[0].get(ANDROID + 'name') != 'org.thoughtcrime.securesms.testing.SignalTestRunner' or runners[0].get(ANDROID + 'targetPackage') != 'im.collie.app':
        raise ValueError('wrong APK package or instrumentation runner')
    if hashes(evidence / 'apk-before.txt') != hashes(evidence / 'apk-after.txt'):
        raise ValueError('APK pair changed during execution')
    app_apk = 'app/build/outputs/apk/prodWebsite/debug/app-prod-website-debug.apk'
    test_apk = 'app/build/outputs/apk/androidTest/prodWebsite/debug/app-prod-website-debug-androidTest.apk'
    if set(hashes(evidence / 'apk-before.txt')) != {app_apk, test_apk}:
        raise ValueError('expected matching prodWebsite app and Android test APKs')
    require_cases(root / 'feature/app-settings/build/test-results/testDebugUnitTest', {HOST_CLASS: HOST_METHODS})
    require_cases(root / 'app/build/outputs/androidTest-results', DEVICE_CASES)
    screenshot_paths = list((evidence / 'screenshots').rglob('*.png'))
    if len(screenshot_paths) != len(SCREENSHOTS) or {path.name for path in screenshot_paths} != SCREENSHOTS:
        raise ValueError('missing, duplicate or unexpected MapLibre picker screenshots')
    for path in screenshot_paths:
        with Image.open(path, formats=['PNG']) as image:
            image.verify()
        with Image.open(path, formats=['PNG']) as image:
            image.load()
            if min(image.size) < 50 or max(ImageStat.Stat(image.convert('RGB')).var) < 1:
                raise ValueError(f'blank or undersized bulk attachment removal screenshot: {path.name}')
    print('Passed eight host navigation cases and one API 35 MapLibre picker case with matching APKs and decoded MapLibre picker screenshots')


if __name__ == '__main__':
    try:
        check(Path.cwd(), os.environ.get('HOST_OUTCOME'), os.environ.get('BUILD_OUTCOME'), os.environ.get('DEVICE_OUTCOME'), os.environ.get('EXPECTED_SHA'))
    except (OSError, ValueError, ET.ParseError) as error:
        print(f'Navigation validation failed: {error}', file=sys.stderr)
        sys.exit(1)
