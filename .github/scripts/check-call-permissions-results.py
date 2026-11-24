#!/usr/bin/env python3
"""Require real Beta smoke and the exact call-permissions API 35 instrumentation evidence."""
import hashlib
import os
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

from PIL import Image, ImageStat

PREFIX = 'org.thoughtcrime.securesms.calls.permissions.'
METHODS = {
    (PREFIX + 'CallPermissionsScreenshotTest', 'callsSettingsAndContactGroupOverrides'),
    (PREFIX + 'CallLinkKeyNativeParityTest', 'canonicalVersionsAndRoomIdentityMatchPinnedNative'),
    (PREFIX + 'CallLinkKeyNativeParityTest', 'malformedBinaryKeysAreRejectedWithoutBroadeningNativeAcceptance'),
    (PREFIX + 'CallEraIdNativeParityTest', 'managedEraIdMatchesPinnedNative'),
}
SCREENSHOTS = {
    '01-collie-hardening.png', '02-calls-defaults.png', '03-calls-disabled.png',
    '04-separate-group-default.png', '05-contact-inherit.png', '06-contact-choices.png',
    '07-contact-blocked.png', '08-group-choices.png', '09-group-allowed.png',
}
ANDROID = '{http://schemas.android.com/apk/res/android}'
EVIDENCE = 'build/call-permissions-api35'


def apk_hashes(path):
    result = {}
    for line in path.read_text().splitlines():
        digest, filename = line.split(maxsplit=1)
        if not re.fullmatch(r'[0-9a-f]{64}', digest) or filename in result:
            raise ValueError('invalid or duplicate APK hash')
        result[filename] = digest
    parents = {
        'app/build/outputs/apk/prodWebsite/beta',
        'app/build/outputs/apk/prodWebsite/debug',
        'app/build/outputs/apk/androidTest/prodWebsite/debug',
        EVIDENCE,
    }
    if len(result) != 4 or {str(Path(name).parent) for name in result} != parents or any(Path(name).suffix != '.apk' for name in result) or EVIDENCE + '/beta-smoke.apk' not in result:
        raise ValueError('expected unsigned Beta, disposable-signed Beta and the matching IMO APK pair')
    return result


def signer_digest(path):
    matches = re.findall(r'^Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})$', path.read_text(), re.MULTILINE)
    if len(matches) != 1 or 'Signer #2 ' in path.read_text():
        raise ValueError('missing or unexpected APK signer')
    return matches[0]


def check(root, build_outcome, device_outcome, expected_sha):
    evidence = root / EVIDENCE
    if (build_outcome, device_outcome) != ('success', 'success'):
        raise ValueError('build or API 35 device step failed')
    source = (evidence / 'source.txt').read_text().splitlines()
    if len(source) != 2 or any(not re.fullmatch(r'[0-9a-f]{40}', value) for value in source) or source[0] != expected_sha:
        raise ValueError('missing or mismatched source SHA/tree')
    if (evidence / 'source-after.txt').read_text().splitlines() != source:
        raise ValueError('source changed during execution')
    if (evidence / 'configuration.txt').read_text().splitlines() != [
        ':app:assembleProdWebsiteBeta',
        '-PimoTests :app:assembleProdWebsiteDebug :app:assembleProdWebsiteDebugAndroidTest',
        'disposable-test-signer-only',
    ]:
        raise ValueError('unexpected build or signing configuration')
    if (evidence / 'exit-code.txt').read_text().strip() != '0':
        raise ValueError('device command failed')
    if (evidence / 'device-api.txt').read_text().strip() != '35' or not (evidence / 'device-fingerprint.txt').read_text().strip():
        raise ValueError('missing API 35 device identity')

    before = apk_hashes(evidence / 'apk-before.txt')
    if before != apk_hashes(evidence / 'apk-after.txt'):
        raise ValueError('APK changed during execution')
    for filename, expected in before.items():
        with (root / filename).open('rb') as apk:
            if hashlib.file_digest(apk, 'sha256').hexdigest() != expected:
                raise ValueError('recorded APK hash does not match the built file')
    beta_signer = signer_digest(evidence / 'beta-signer.txt')
    if beta_signer != (evidence / 'beta-test-signer-sha256.txt').read_text().strip():
        raise ValueError('Beta smoke APK does not use the disposable signer')
    if signer_digest(evidence / 'app-signer.txt') != signer_digest(evidence / 'test-signer.txt'):
        raise ValueError('instrumentation APKs have different signers')

    beta = ET.parse(evidence / 'beta-manifest.xml').getroot()
    app = ET.parse(evidence / 'app-manifest.xml').getroot()
    test = ET.parse(evidence / 'test-manifest.xml').getroot()
    runners = test.findall('instrumentation')
    if beta.get('package') != 'im.collie.app.trailblazer' or beta.find('application') is None or beta.find('application').get(ANDROID + 'debuggable', 'false') != 'false':
        raise ValueError('smoke APK is not the production Beta variant')
    if app.get('package') != 'im.collie.app.imo' or test.get('package') != 'im.collie.app.imo.test' or len(runners) != 1 or runners[0].get(ANDROID + 'name') != 'org.thoughtcrime.securesms.testing.incomingmessageobserver.IncomingMessageObserverTestRunner' or runners[0].get(ANDROID + 'targetPackage') != 'im.collie.app.imo':
        raise ValueError('wrong isolated instrumentation packages or runner')
    if not re.search(r'^Success\s*$', (evidence / 'beta-install.txt').read_text(), re.MULTILINE) or not re.search(r'^Status: ok\s*$', (evidence / 'beta-launch.txt').read_text(), re.MULTILINE):
        raise ValueError('Beta installation or launch failed')
    pids = (evidence / 'beta-pids.txt').read_text().splitlines()
    if len(pids) != 5 or len(set(pids)) != 1 or not re.fullmatch(r'[1-9][0-9]*', pids[0]):
        raise ValueError('Beta did not stay alive for the smoke observation')
    if not re.search(r'(?:mResumedActivity|topResumedActivity)[^\n]*im\.collie\.app\.trailblazer/', (evidence / 'beta-activities.txt').read_text()):
        raise ValueError('Beta did not retain its foreground activity')
    if not any(node.get('package') == 'im.collie.app.trailblazer' for node in ET.parse(evidence / 'beta-ui.xml').getroot().iter('node')):
        raise ValueError('Beta UI hierarchy did not contain the running app')
    if re.search(r'Process: im\.collie\.app\.trailblazer[,\s]|ANR in im\.collie\.app\.trailblazer|>>> im\.collie\.app\.trailblazer <<<', (evidence / 'beta-logcat.txt').read_text()):
        raise ValueError('Beta crashed or stopped responding during smoke')

    cases = []
    total = 0
    for path in (root / 'app/build/outputs/androidTest-results').rglob('TEST-*.xml'):
        report = ET.parse(path).getroot()
        suites = list(report.iter('testsuite'))
        if not suites:
            raise ValueError('unexpected instrumentation report format')
        for suite in suites:
            total += int(suite.get('tests', '0'))
            if any(int(suite.get(key, '0')) != 0 for key in ('failures', 'errors', 'skipped')):
                raise ValueError('failed or skipped instrumentation suite')
        for case in report.iter('testcase'):
            if any(case.find(tag) is not None for tag in ('failure', 'error', 'skipped')):
                raise ValueError('nonpassing instrumentation case')
            cases.append((case.get('classname'), case.get('name')))
    if total != len(METHODS) or len(cases) != len(METHODS) or set(cases) != METHODS:
        raise ValueError(f'expected exactly four methods in the three selected classes; found {cases}')

    outputs = root / 'app/build/outputs/connected_android_test_additional_output'
    captures = list(outputs.rglob('call-permissions-proof/*.png'))
    if len(captures) != len(SCREENSHOTS) or {path.name for path in captures} != SCREENSHOTS:
        raise ValueError('missing, duplicate or unexpected call-permissions screenshots')
    for path in captures:
        with Image.open(path, formats=['PNG']) as image:
            image.verify()
        with Image.open(path, formats=['PNG']) as image:
            image.load()
            if min(image.size) < 50 or max(ImageStat.Stat(image.convert('RGB')).var) < 1:
                raise ValueError(f'blank or undersized screenshot: {path.name}')
    print('Passed real Beta startup, four isolated instrumentation methods and nine API 35 screenshots with matching source and APK hashes')


if __name__ == '__main__':
    try:
        check(Path.cwd(), os.environ.get('BUILD_OUTCOME'), os.environ.get('DEVICE_OUTCOME'), os.environ.get('EXPECTED_SHA'))
    except (OSError, ValueError, ET.ParseError) as error:
        print(f'Call permissions API 35 evidence failed: {error}', file=sys.stderr)
        sys.exit(1)
