#!/usr/bin/env python3
"""Prepare, fetch SDKs for, and verify the scoped call-permissions host tests."""

import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import tomllib
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]
MANIFEST = 'tooling/ci/call-permissions-host-tests.json'
EVIDENCE = 'build/call-permissions-host'
SDK_DIRECTORY = 'build/call-permissions-host-sdks'
ROBOLECTRIC_VERSION = '4.16'
# Exact DefaultSdkProvider 4.16 mapping. API 36 is also SandboxManager's compile
# SDK on Java 21, including when the test's runtime SDK is 28 or 35.
SDKS = {28: '9-robolectric-4913185-2-i7', 35: '15-robolectric-13954326-i7', 36: '16-robolectric-13921718-i7'}
REPORTS = {
    ':app:testProdWebsiteDebugUnitTest': 'app/build/test-results/testProdWebsiteDebugUnitTest',
    ':feature:app-settings:testDebugUnitTest': 'feature/app-settings/build/test-results/testDebugUnitTest',
}
TEST_METHOD = re.compile(r'(?m)^\s*@Test(?:\([^\n]*\))?\s+fun\s+(?:`([^`]+)`|(\w+))\s*\(\s*\)')
MAVEN = 'https://repo.maven.apache.org/maven2/org/robolectric/android-all-instrumented'
MAVEN_MIRROR = 'https://maven-central.storage-download.googleapis.com/maven2/org/robolectric/android-all-instrumented'
PREFETCH_SECONDS = 600
ATTEMPT_SECONDS = 180


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(path, algorithm='sha256'):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, algorithm).hexdigest()


def git(root, *args):
    return subprocess.check_output(['git', *args], cwd=root, text=True).strip()


def source(root):
    require(not git(root, 'diff', '--name-only', 'HEAD', '--'), 'Tracked source changed during host validation')
    return git(root, 'rev-parse', 'HEAD', 'HEAD^{tree}').splitlines()


def inventory(root):
    manifest = json.loads((root / MANIFEST).read_text())
    version = tomllib.loads((root / 'gradle/test-libs.versions.toml').read_text())['versions']['robolectric']
    require(manifest['schema'] == 1 and version == manifest['robolectric_version'] == ROBOLECTRIC_VERSION,
            'Review the offline SDK mapping when changing the pinned Robolectric version')
    require(set(manifest['tasks']) == set(REPORTS), 'Unexpected host test tasks')
    suites = []
    for task, files in manifest['tasks'].items():
        for filename, expected_count in files.items():
            path = Path(filename)
            require(not path.is_absolute() and '..' not in path.parts and path.suffix == '.kt', 'Invalid test source path')
            require(filename.startswith(task.rsplit(':', 1)[0].strip(':').replace(':', '/') + '/src/test/'), 'Test source is outside its task')
            content = (root / path).read_text()
            packages = re.findall(r'(?m)^package ([\w.]+)\s*$', content)
            declarations = re.findall(r'(?m)^\s*(?:internal\s+|public\s+)?class\s+' + re.escape(path.stem) + r'\b', content)
            require(len(packages) == len(declarations) == 1, f'Ambiguous test class: {filename}')
            require(not re.search(r'@(?:Ignore|Disabled|Parameterized)\b', content), f'Unsupported skipped/parameterized source: {filename}')
            methods = [match.group(1) or match.group(2) for match in TEST_METHOD.finditer(content)]
            annotations = re.findall(r'(?m)^\s*@Test\b', content)
            require(type(expected_count) is int and expected_count > 0 and len(methods) == len(annotations) == expected_count,
                    f'Test methods changed or cannot be parsed: {filename}; update the maintained inventory deliberately')
            require(len(set(methods)) == len(methods), f'Duplicate source method: {filename}')
            suites.append({'task': task, 'class_name': packages[0] + '.' + path.stem, 'source': filename,
                           'source_sha256': digest(root / path), 'methods': methods})
    require(len(suites) == manifest['expected_classes'], 'Class inventory count changed')
    require(sum(len(s['methods']) for s in suites) == manifest['expected_methods'], 'Method inventory count changed')
    require(len({(s['task'], s['class_name']) for s in suites}) == len(suites), 'Duplicate inventory class')
    return {'manifest_sha256': digest(root / MANIFEST), 'robolectric_version': version, 'suites': suites}


def prepare(root):
    evidence = root / EVIDENCE
    evidence.mkdir(parents=True, exist_ok=True)
    for filename in ('results.json', 'sdk-receipt.json', 'native-before.json',
                     'test-exit-code.txt', 'source-after.txt', 'host-tests.log', 'prefetch.log'):
        (evidence / filename).unlink(missing_ok=True)
    for receipt in evidence.glob('_*.json'):
        receipt.unlink()
    selected = inventory(root)
    identity = source(root)
    require(identity[0] == os.environ.get('GITHUB_SHA', identity[0]), 'Unexpected checkout for this workflow')
    # Only generated results for these two tasks are removed; stale XML cannot
    # satisfy a failed, skipped or narrower current invocation.
    for directory in REPORTS.values():
        shutil.rmtree(root / directory, ignore_errors=True)
    (evidence / 'inventory.json').write_text(json.dumps(selected, indent=2) + '\n')
    (evidence / 'source-before.txt').write_text('\n'.join(identity) + '\n')


def download(url, target, deadline):
    """Two finite attempts, with a shared deadline for the entire SDK prefetch."""
    require(url.startswith(MAVEN + '/'), 'SDK downloads must use official Maven Central')
    # Match the general unit-test workflow's mirror-first route for large jars.
    # Checksums still come from canonical Central and are verified before install.
    candidates = [MAVEN_MIRROR + url[len(MAVEN):], url] if url.endswith('.jar') else [url, url]
    last_error = ''
    for candidate in candidates:
        remaining = deadline - time.monotonic()
        require(remaining > 1, 'SDK prefetch exceeded its total time budget')
        limit = min(ATTEMPT_SECONDS, int(remaining))
        try:
            result = subprocess.run([
                'curl', '--fail', '--silent', '--show-error', '--proto', '=https',
                '--location', '--max-redirs', '0', '--connect-timeout', str(min(15, limit)),
                '--max-time', str(limit), '--max-filesize', str(512 * 1024 * 1024),
                '--output', str(target), candidate,
            ], capture_output=True, text=True, timeout=limit + 1)
            if result.returncode == 0:
                return
            last_error = result.stderr[-1000:]
        except subprocess.TimeoutExpired:
            last_error = 'download attempt timed out'
        target.unlink(missing_ok=True)
    raise ValueError(f'Bounded SDK download failed: {url}: {last_error}')


def prefetch(root):
    inventory(root)  # Refuse a stale Robolectric mapping before fetching bytes.
    directory = root / SDK_DIRECTORY
    directory.mkdir(parents=True, exist_ok=True)
    deadline = time.monotonic() + PREFETCH_SECONDS
    receipts = []
    for api, version in SDKS.items():
        name = f'android-all-instrumented-{version}.jar'
        url = f'{MAVEN}/{version}/{name}'
        checksum = directory / (name + '.sha512')
        temporary = directory / (name + '.partial')
        try:
            download(url + '.sha512', checksum, deadline)
            expected = checksum.read_text().strip().lower()
            require(re.fullmatch(r'[0-9a-f]{128}', expected), f'Invalid official SHA-512: {name}')
            download(url, temporary, deadline)
            require(digest(temporary, 'sha512') == expected, f'Official SHA-512 mismatch: {name}')
            temporary.replace(directory / name)
            receipts.append({'api': api, 'version': version, 'file': name, 'url': url,
                             'bytes': (directory / name).stat().st_size,
                             'sha512': expected, 'sha256': digest(directory / name)})
        finally:
            temporary.unlink(missing_ok=True)
    (root / EVIDENCE / 'sdk-receipt.json').write_text(json.dumps(receipts, indent=2) + '\n')


def check_reports(root, selected):
    expected = {(s['task'], s['class_name']): set(s['methods']) for s in selected['suites']}
    seen = set()
    results = []
    for task, directory in REPORTS.items():
        for path in sorted((root / directory).glob('TEST-*.xml')):
            report = ET.parse(path).getroot()
            key = (task, report.get('name'))
            require(report.tag == 'testsuite' and key in expected, f'Unexpected suite: {path}')
            require(key not in seen, f'Duplicate suite: {key}')
            seen.add(key)
            cases = report.findall('testcase')
            names = Counter(case.get('name') for case in cases)
            require(set(names) == expected[key] and all(count == 1 for count in names.values()),
                    f'Missing, duplicate or unexpected methods (including class setup failure): {key}')
            require(all(case.get('classname') == key[1] for case in cases), f'Mismatched testcase class: {key}')
            require(all(case.find(tag) is None for case in cases for tag in ('failure', 'error', 'skipped')),
                    f'Failed or skipped method: {key}')
            require([int(report.get(field, '-1')) for field in ('tests', 'failures', 'errors', 'skipped')] == [len(cases), 0, 0, 0],
                    f'JUnit totals disagree with passing methods: {key}')
            results.append({'task': task, 'class_name': key[1], 'passed': len(cases),
                            'xml': str(path.relative_to(root)), 'sha256': digest(path)})
    require(seen == set(expected), f'Missing test suites: {sorted(set(expected) - seen)}')
    return results


def check(root, expected_sha):
    evidence = root / EVIDENCE
    before = (evidence / 'source-before.txt').read_text().splitlines()
    require(len(before) == 2 and all(re.fullmatch(r'[0-9a-f]{40}', value) for value in before), 'Invalid source record')
    require(before[0] == expected_sha and before == source(root), 'Host results belong to another checkout')
    require(before == (evidence / 'source-after.txt').read_text().splitlines(), 'Source changed while testing')
    require((evidence / 'test-exit-code.txt').read_text().strip() == '0', 'Host test command failed')
    selected = inventory(root)
    require(selected == json.loads((evidence / 'inventory.json').read_text()), 'Test inventory/source changed after preparation')
    for task in REPORTS:
        receipt = json.loads((evidence / (task.replace(':', '_') + '.json')).read_text())
        classes = sorted(s['class_name'] for s in selected['suites'] if s['task'] == task)
        require(receipt == {'task': task, 'classes': classes, 'offline': True,
                            'sdk_directory': str((root / SDK_DIRECTORY).resolve()), 'preinstrumented': True,
                            'host_jni_directory': str((root / 'build/host-tests/resources').resolve())},
                f'Missing or changed scoped task/resolver receipt: {task}')
    sdk_receipts = json.loads((evidence / 'sdk-receipt.json').read_text())
    require(len(sdk_receipts) == len(SDKS) and {r['api']: r['version'] for r in sdk_receipts} == SDKS, 'Missing/changed SDK receipt')
    for receipt in sdk_receipts:
        name = f"android-all-instrumented-{SDKS[receipt['api']]}.jar"
        path = root / SDK_DIRECTORY / name
        require(receipt['file'] == name and receipt['url'] == f"{MAVEN}/{receipt['version']}/{name}", 'Unexpected SDK identity')
        require(receipt['sha512'] == (path.parent / (name + '.sha512')).read_text().strip().lower() == digest(path, 'sha512'), 'SDK SHA-512 changed')
        require(receipt['sha256'] == digest(path) and receipt['bytes'] == path.stat().st_size, 'SDK bytes changed')
    # Use the canonical native verifier again after the test run, without rebuilding.
    subprocess.run([sys.executable, str(root / 'tooling/host-tests/build_libsignal.py'), '--verify-only'], cwd=root, check=True)
    require(json.loads((evidence / 'native-before.json').read_text()) == json.loads((root / 'build/host-tests/receipt.json').read_text()),
            'Host JNI receipt changed during execution')
    results = check_reports(root, selected)
    result = {'passed': True, 'source_commit': before[0], 'source_tree': before[1],
              'classes': len(results), 'methods_passed': sum(row['passed'] for row in results),
              'failures': 0, 'skips': 0, 'suites': results}
    (evidence / 'results.json').write_text(json.dumps(result, indent=2) + '\n')
    print(f"Call-permissions host tests: {result['methods_passed']} methods passed in {result['classes']} suites")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['prepare', 'prefetch', 'check'])
    args = parser.parse_args()
    try:
        if args.action == 'prepare':
            prepare(ROOT)
        elif args.action == 'prefetch':
            prefetch(ROOT)
        else:
            check(ROOT, os.environ['EXPECTED_SHA'])
    except (OSError, KeyError, ValueError, subprocess.SubprocessError, ET.ParseError) as error:
        print(f'Call-permissions host validation failed: {error}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
