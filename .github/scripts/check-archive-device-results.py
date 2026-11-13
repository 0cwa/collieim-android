#!/usr/bin/env python3
"""Require the selected archive shard and the same assembled APK pair."""
import os
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

CLASS = 'org.thoughtcrime.securesms.backup.v2.ArchiveImportExportTests'
METHOD = 'shard1'


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


def check(root, build_outcome, device_outcome):
    evidence = root / 'build/archive-diagnostic'
    if build_outcome != 'success' or device_outcome != 'success':
        raise ValueError('build or device step failed')
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
        for suite in report.iter('testsuite'):
            suites.append(suite)
            if any(int(suite.get(key, '0')) for key in ('failures', 'errors', 'skipped')):
                raise ValueError('test suite failed or skipped')
        for case in report.iter('testcase'):
            if any(case.find(tag) is not None for tag in ('failure', 'error', 'skipped')):
                raise ValueError('test case failed or skipped')
            cases.append((case.get('classname'), case.get('name')))
    if len(suites) != 1 or suites[0].get('tests') != '1':
        raise ValueError('expected one declared test in one suite')
    if cases != [(CLASS, METHOD)]:
        raise ValueError(f'expected exactly {CLASS}#{METHOD}; got {cases}')
    print(f'Passed: {CLASS}#{METHOD}; matching APK pair and API 35')


if __name__ == '__main__':
    try:
        check(Path.cwd(), os.environ.get('BUILD_OUTCOME'), os.environ.get('DEVICE_OUTCOME'))
    except (OSError, ValueError, ET.ParseError) as error:
        print(f'Archive diagnostic failed: {error}', file=sys.stderr)
        sys.exit(1)
