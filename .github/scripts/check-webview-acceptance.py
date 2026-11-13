#!/usr/bin/env python3
"""Require all offline WebView tests, matching APKs, and decoded screenshots."""
import os
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

from PIL import Image, ImageStat

CLASS = 'org.thoughtcrime.securesms.security.WebViewRuntimeAcceptanceTest'
METHODS = {
    'composeCaptchaEmitsSyntheticToken',
    'legacyCaptchaEmitsTokenAndNavigatesUp',
    'recaptchaSubmitsSyntheticChallengeThroughFakeTransport',
    'paypalReturnsSyntheticConfirmation',
    'stripeReturnsSyntheticIntentAccessor',
    'debugViewerLoadsAssetAndRoundTripsSyntheticLogs',
}
SCREENSHOTS = {'compose-captcha.png', 'legacy-captcha.png', 'recaptcha.png', 'paypal.png', 'stripe.png', 'debug-viewer.png'}
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
    evidence = root / 'build/webview-acceptance'
    if (build_outcome, device_outcome) != ('success', 'success'):
        raise ValueError('build or device step failed')
    source = (evidence / 'source.txt').read_text().splitlines()
    if len(source) != 2 or any(not re.fullmatch(r'[0-9a-f]{40}', value) for value in source) or source[0] != expected_sha:
        raise ValueError('missing or mismatched source identity')
    app = ET.parse(evidence / 'app-manifest.xml').getroot()
    test = ET.parse(evidence / 'test-manifest.xml').getroot()
    runners = test.findall('instrumentation')
    if app.get('package') != 'im.collie.app' or test.get('package') != 'im.collie.app.test' or len(runners) != 1 or runners[0].get(ANDROID + 'name') != 'org.thoughtcrime.securesms.testing.SignalTestRunner' or runners[0].get(ANDROID + 'targetPackage') != 'im.collie.app':
        raise ValueError('wrong APK package or instrumentation runner')
    if (evidence / 'exit-code.txt').read_text().strip() != '0':
        raise ValueError('connected test command failed')
    if (evidence / 'device-api.txt').read_text().strip() != '35' or not (evidence / 'device-fingerprint.txt').read_text().strip():
        raise ValueError('missing API 35 device identity')
    provider = (evidence / 'webview-provider.txt').read_text()
    if not re.search(r'Current WebView package[^\n]*:\s*\((?!null|none)[^,\n]+,\s*[^)\n]+\)', provider, re.IGNORECASE):
        raise ValueError('missing WebView provider identity')
    for phase in ('before', 'after'):
        if 'Active default network: none' not in (evidence / f'connectivity-{phase}.txt').read_text():
            raise ValueError('device was not confirmed offline')
    if hashes(evidence / 'apk-before.txt') != hashes(evidence / 'apk-after.txt'):
        raise ValueError('APK pair changed during execution')
    cases = []
    suites = []
    for path in (root / 'app/build/outputs/androidTest-results').rglob('TEST-*.xml'):
        report = ET.parse(path).getroot()
        for suite in report.iter('testsuite'):
            suites.append(suite)
            if any(int(suite.get(key, '0')) for key in ('failures', 'errors', 'skipped')):
                raise ValueError('test suite failed or skipped')
        for case in report.iter('testcase'):
            if case.get('classname') != CLASS or any(case.find(tag) is not None for tag in ('failure', 'error', 'skipped')):
                raise ValueError('wrong class or nonpassing test case')
            cases.append(case.get('name'))
    if not suites or sum(int(suite.get('tests', '0')) for suite in suites) != len(METHODS) or len(cases) != len(METHODS) or set(cases) != METHODS:
        raise ValueError(f'missing, duplicate or unexpected WebView tests: {cases}')
    screenshot_paths = list((evidence / 'screenshots').rglob('*.png'))
    if len(screenshot_paths) != len(SCREENSHOTS) or {p.name for p in screenshot_paths} != SCREENSHOTS:
        raise ValueError('missing, duplicate or unexpected screenshots')
    for path in screenshot_paths:
        with Image.open(path, formats=['PNG']) as image:
            image.verify()
        with Image.open(path, formats=['PNG']) as image:
            image.load()
            if min(image.size) < 50 or max(ImageStat.Stat(image.convert('RGB')).var) < 1:
                raise ValueError(f'blank or undersized screenshot: {path.name}')
    print('Passed six offline WebView methods with matching APKs and decoded screenshots')


if __name__ == '__main__':
    try:
        check(Path.cwd(), os.environ.get('BUILD_OUTCOME'), os.environ.get('DEVICE_OUTCOME'), os.environ.get('EXPECTED_SHA'))
    except (OSError, ValueError, ET.ParseError) as error:
        print(f'WebView acceptance failed: {error}', file=sys.stderr)
        sys.exit(1)
