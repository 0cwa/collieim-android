#!/usr/bin/env python3
"""Validate API 35 UI smoke evidence against the curated edition manifest."""
import argparse
import json
import os
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / 'tooling/ci/ui_smoke_manifest.json'
ANDROID = '{http://schemas.android.com/apk/res/android}'
ANIMATION_SETTINGS = ('window_animation_scale', 'transition_animation_scale', 'animator_duration_scale')


def hashes(path):
    result = {}
    for line in path.read_text().splitlines():
        digest, filename = line.split(maxsplit=1)
        if not re.fullmatch(r'[0-9a-f]{64}', digest) or filename in result:
            raise ValueError('invalid or duplicate APK digest')
        result[filename] = digest
    if len(result) != 2:
        raise ValueError('expected exactly one app and one test APK')
    return result


def check(root, edition, expected_sha, build_outcome='success', device_outcome='success', expected_api=35):
    if (build_outcome, device_outcome) != ('success', 'success'):
        raise ValueError('build or API 35 device step failed')
    config = json.loads(MANIFEST.read_text())['editions'][edition]
    evidence = Path(root)
    identity = (evidence / 'source.txt').read_text().splitlines()
    if len(identity) != 2 or any(not re.fullmatch(r'[0-9a-f]{40}', value) for value in identity) or identity[0] != expected_sha:
        raise ValueError('missing or mismatched source identity')
    if (evidence / 'exit-code.txt').read_text().strip() != '0':
        raise ValueError('instrumentation command failed')
    if (evidence / 'device-api.txt').read_text().strip() != str(expected_api):
        raise ValueError(f'device is not API {expected_api}')
    if not (evidence / 'device-fingerprint.txt').read_text().strip():
        raise ValueError('missing device fingerprint')
    animations = json.loads((evidence / 'animations.json').read_text())
    if animations.get('applied_during_tests') != {setting: '0' for setting in ANIMATION_SETTINGS} or set(animations.get('original', {})) != set(ANIMATION_SETTINGS):
        raise ValueError('animations were not disabled consistently for instrumentation')
    app = ET.parse(evidence / 'app-manifest.xml').getroot()
    test = ET.parse(evidence / 'test-manifest.xml').getroot()
    runners = test.findall('instrumentation')
    if app.get('package') != config['application_id'] or test.get('package') != config['test_application_id'] or len(runners) != 1 or runners[0].get(ANDROID + 'name') != config['runner'] or runners[0].get(ANDROID + 'targetPackage') != config['application_id']:
        raise ValueError('wrong APK package or instrumentation runner')
    before = hashes(evidence / 'apk-before.txt')
    after = hashes(evidence / 'apk-after.txt')
    if before != after:
        raise ValueError('APK pair changed during execution')
    if set(before) != {config['app_apk'], config['test_apk']}:
        raise ValueError('expected matching edition app and Android test APKs')
    expected_classes = (evidence / 'classes.json')
    classes = json.loads(expected_classes.read_text())
    if classes != config['classes']:
        raise ValueError('run class list differs from the exact edition manifest')
    summary = json.loads((evidence / 'summary.json').read_text())
    if [item.get('class') for item in summary] != classes:
        raise ValueError('missing, duplicate, or unexpected class runs')
    if set(config['methods']) != set(classes):
        raise ValueError('manifest test methods do not cover the exact class list')
    total = 0
    seconds = 0.0
    for item in summary:
        if int(item.get('passed', 0)) <= 0 or item.get('error') or not isinstance(item.get('seconds'), (int, float)):
            raise ValueError(f'class did not pass: {item.get("class")}')
        raw = (evidence / (item['class'].replace('.', '_') + '.log')).read_text()
        starts, passes, bad, skips, runs = [], [], [], [], []
        status = {}
        for line in raw.splitlines():
            kind, sep, value = line.partition(': ')
            if not sep:
                continue
            if kind == 'INSTRUMENTATION_STATUS':
                key, _, val = value.partition('=')
                status[key] = val
            elif kind == 'INSTRUMENTATION_STATUS_CODE':
                event = (value, status.get('class'), status.get('test'))
                if value == '1': starts.append(event)
                elif value == '0': passes.append(event)
                elif value == '-2': bad.append(event)
                elif value in ('-3', '-4'): skips.append(event)
                status.clear()
            elif kind == 'INSTRUMENTATION_CODE':
                runs.append(value)
            elif kind == 'INSTRUMENTATION_FAILED' or line.startswith('INSTRUMENTATION_RESULT: shortMsg='):
                raise ValueError(f'instrumentation crash in {item["class"]}: {line}')
        expected_tests = config['methods'][item['class']]
        observed_tests = [event[2] for event in passes]
        if item.get('tests') != observed_tests or sorted(observed_tests) != sorted(expected_tests) or len(starts) != item['passed'] or len(passes) != item['passed'] or bad or skips or runs != ['-1'] or any(e[1] != item['class'] for e in starts + passes):
            raise ValueError(f'failed, skipped, zero-test, or malformed run for {item["class"]}')
        total += item['passed']
        seconds += item['seconds']
    print(f'Passed {total} tests across {len(classes)} {edition} classes on API {expected_api} in {seconds:.3f}s with matching APKs')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--edition', choices=('stable', 'beta'), required=True)
    parser.add_argument('--evidence-dir', default='build/ui-smoke')
    parser.add_argument('--expected-api-level', type=int, default=35)
    args = parser.parse_args()
    try:
        check(args.evidence_dir, args.edition, os.environ.get('EXPECTED_SHA', ''),
              os.environ.get('BUILD_OUTCOME', 'success'), os.environ.get('DEVICE_OUTCOME', 'success'), args.expected_api_level)
    except (OSError, ValueError, KeyError, ET.ParseError, json.JSONDecodeError) as error:
        print(f'UI smoke validation failed: {error}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
