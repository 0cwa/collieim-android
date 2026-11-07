#!/usr/bin/env python3
"""Fail CodeQL CI on unreviewed findings, incomplete extraction, or unusable SARIF."""

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import sys
from urllib.parse import unquote


LANGUAGE_PREFIXES = {
    'actions': 'actions',
    'python': 'py',
    'javascript-typescript': 'js',
    'c-cpp': 'cpp',
}


def object_value(value, description):
    if not isinstance(value, dict):
        raise ValueError(f'{description} must be an object')
    return value


def array_value(value, description):
    if not isinstance(value, list):
        raise ValueError(f'{description} must be an array')
    return value


def locations(item, artifacts):
    """Return source URIs from inline locations or SARIF artifact references."""
    paths = []
    for location in array_value(item.get('locations', []), 'locations'):
        physical = object_value(location, 'location').get('physicalLocation', {})
        artifact = object_value(physical, 'physicalLocation').get('artifactLocation', {})
        artifact = object_value(artifact, 'artifactLocation')
        uri = artifact.get('uri')
        if uri is None and 'index' in artifact:
            index = artifact['index']
            if type(index) is not int or not 0 <= index < len(artifacts):
                raise ValueError('invalid artifact index')
            uri = object_value(artifacts[index], 'artifact').get('location', {}).get('uri')
        if uri is not None:
            if not isinstance(uri, str):
                raise ValueError('artifact URI must be a string')
            paths.append(unquote(uri))
    return paths


def inspect_run(run, language, filename):
    prefix = LANGUAGE_PREFIXES[language]
    run = object_value(run, 'run')
    tool = object_value(run.get('tool'), 'tool')
    driver = object_value(tool.get('driver'), 'tool.driver')
    if driver.get('name') != 'CodeQL':
        raise ValueError('expected a CodeQL run')

    components = [driver, *array_value(tool.get('extensions', []), 'tool.extensions')]
    rules = []
    for component in components:
        component = object_value(component, 'tool component')
        rules.extend(array_value(component.get('rules', []), 'rules'))
    rule_ids = {object_value(rule, 'rule')['id'] for rule in rules}
    if not any(isinstance(rule, str) and rule.startswith(prefix + '/') for rule in rule_ids):
        raise ValueError(f'no {language} query metadata; the queries may not have run')

    artifacts = array_value(run.get('artifacts', []), 'artifacts')
    findings = []
    for result in array_value(run.get('results'), 'results'):
        result = object_value(result, 'result')
        # Do not discard existing, low-severity, or suppressed findings. This is
        # a clean-suite gate, independent of GitHub's new-alert/dismissal state.
        findings.append({
            'rule': result.get('ruleId', result.get('rule', {}).get('id', 'unknown')),
            'paths': locations(result, artifacts),
            'primary_location_count': len(result.get('locations', [])),
            'primary_location_line_hash': result.get('partialFingerprints', {}).get('primaryLocationLineHash'),
            'message': result.get('message', {}).get('text', ''),
        })

    invocations = array_value(run.get('invocations'), 'invocations')
    if not invocations:
        raise ValueError('no invocation status')
    errors = []
    extracted = set()
    diagnostics = []
    for invocation in invocations:
        invocation = object_value(invocation, 'invocation')
        if invocation.get('executionSuccessful') is not True:
            errors.append('CodeQL did not report a successful invocation')
        for notification_key in ('toolExecutionNotifications', 'toolConfigurationNotifications'):
            for notification in array_value(invocation.get(notification_key, []), notification_key):
                notification = object_value(notification, 'notification')
                descriptor = object_value(notification.get('descriptor', {}), 'descriptor')
                identifier = descriptor.get('id', 'unknown')
                level = notification.get('level', 'warning')
                paths = locations(notification, artifacts)
                if identifier == prefix + '/diagnostics/successfully-extracted-files':
                    extracted.update(paths)
                if level not in ('none', 'note', 'warning', 'error'):
                    raise ValueError(f'unknown diagnostic level: {level!r}')
                if level in ('warning', 'error'):
                    errors.append(f'{level} diagnostic: {identifier}')
                if level != 'none':
                    diagnostics.append({
                        'id': identifier,
                        'level': level,
                        'paths': paths,
                        'message': notification.get('message', {}).get('text', ''),
                    })
    if not extracted:
        errors.append(f'no successfully extracted {language} source files were reported')
    return {
        'file': filename,
        'codeql_version': driver.get('semanticVersion', driver.get('version')),
        'query_count': len(rule_ids),
        'findings': findings,
        'extracted_sources': sorted(extracted),
        'diagnostics': diagnostics,
        'errors': errors,
    }


def load_reviews(path, language, source_root):
    """Load exact, source-bound dispositions without changing any SARIF result."""
    document = object_value(json.loads(path.read_text(encoding='utf-8')), 'reviewed findings')
    if document.get('schema') != 1:
        raise ValueError('unsupported reviewed-findings schema')
    reviews = []
    identifiers = set()
    matches = set()
    for review in array_value(document.get('findings'), 'reviewed findings'):
        review = object_value(review, 'reviewed finding')
        for key in ('id', 'language', 'rule', 'path', 'primary_location_line_hash', 'source_sha256', 'rationale'):
            if not isinstance(review.get(key), str) or not review[key].strip():
                raise ValueError(f'reviewed finding needs a nonempty {key}')
        if review['language'] not in LANGUAGE_PREFIXES:
            raise ValueError('unknown reviewed-finding language')
        if not re.fullmatch(r'[0-9a-f]{64}', review['source_sha256']):
            raise ValueError('reviewed source SHA256 must be 64 lowercase hexadecimal characters')
        match = (review['language'], review['rule'], review['path'], review['primary_location_line_hash'])
        if review['id'] in identifiers or match in matches:
            raise ValueError('duplicate reviewed-finding identity or location')
        identifiers.add(review['id'])
        matches.add(match)
        if review['language'] != language:
            continue
        relative = PurePosixPath(review['path'])
        if relative.is_absolute() or '..' in relative.parts or '\\' in review['path'] or relative.as_posix() != review['path']:
            raise ValueError('reviewed source path must be canonical and repository-relative')
        root = source_root.resolve()
        source = (root / review['path']).resolve()
        if not source.is_relative_to(root):
            raise ValueError('reviewed source path escapes the repository')
        digest = hashlib.sha256(source.read_bytes()).hexdigest()
        if digest != review['source_sha256']:
            raise ValueError(f"reviewed source changed: {review['path']}; re-review the finding")
        reviews.append(review)
    return reviews


def apply_reviews(report, reviews):
    matched = set()
    for run in report['runs']:
        for finding in run['findings']:
            for review in reviews:
                if (review['id'] not in matched and finding['rule'] == review['rule'] and
                        finding['primary_location_count'] == 1 and finding['paths'] == [review['path']] and
                        finding['primary_location_line_hash'] == review['primary_location_line_hash']):
                    finding['reviewed_disposition'] = review['id']
                    matched.add(review['id'])
                    break
    for review in reviews:
        if review['id'] not in matched:
            report['errors'].append(f"reviewed finding was not found exactly once: {review['id']}; remove or re-review it")
    report['reviewed_finding_count'] = len(matched)
    report['unreviewed_finding_count'] = report['finding_count'] - len(matched)


def check_directory(directory, language, required_sources, reviews_path=None, source_root=None):
    report = {'schema': 1, 'language': language, 'runs': [], 'errors': []}
    files = sorted(directory.glob('*.sarif'))
    if not files:
        report['errors'].append('no SARIF files were produced')
    for path in files:
        try:
            document = object_value(json.loads(path.read_text(encoding='utf-8')), 'SARIF')
            if document.get('version') != '2.1.0':
                raise ValueError('expected SARIF version 2.1.0')
            runs = array_value(document.get('runs'), 'runs')
            if not runs:
                raise ValueError('no analysis runs')
            for run in runs:
                report['runs'].append(inspect_run(run, language, path.name))
        except (OSError, UnicodeError, ValueError, KeyError, TypeError, AttributeError) as exc:
            report['errors'].append(f'{path.name}: {exc}')

    extracted = {path for run in report['runs'] for path in run['extracted_sources']}
    for source in required_sources:
        if source not in extracted:
            report['errors'].append(f'required source was not extracted: {source}')
    report['finding_count'] = sum(len(run['findings']) for run in report['runs'])
    report['extracted_source_count'] = len(extracted)
    reviews = []
    if reviews_path is not None:
        try:
            if source_root is None:
                raise ValueError('reviewed findings require a source root')
            reviews = load_reviews(reviews_path, language, source_root)
        except (OSError, UnicodeError, ValueError, KeyError, TypeError, AttributeError) as exc:
            report['errors'].append(f'reviewed findings: {exc}')
    apply_reviews(report, reviews)
    report['passed'] = bool(report['runs']) and not (
        report['errors'] or report['unreviewed_finding_count'] or any(run['errors'] for run in report['runs'])
    )
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sarif-dir', type=Path, required=True)
    parser.add_argument('--language', choices=LANGUAGE_PREFIXES, required=True)
    parser.add_argument('--require-source', action='append', required=True)
    parser.add_argument('--source-root', type=Path)
    parser.add_argument('--reviewed-findings', type=Path)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args(argv)
    report = check_directory(args.sarif_dir, args.language, args.require_source,
                             args.reviewed_findings, args.source_root)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')

    outcome = 'PASS' if report['passed'] else 'FAIL'
    summary = (f"CodeQL {args.language}: {outcome}; {report['finding_count']} raw findings "
               f"({report['reviewed_finding_count']} reviewed, {report['unreviewed_finding_count']} unreviewed); "
               f"{report['extracted_source_count']} successfully extracted source files.")
    print(summary)
    # JSON quoting keeps source-derived messages from becoming workflow commands.
    for error in report['errors']:
        print(json.dumps({'error': error}))
    for run in report['runs']:
        for error in run['errors']:
            print(json.dumps({'error': error, 'file': run['file']}))
        for finding in run['findings']:
            print(json.dumps({'finding': finding}))
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as stream:
            stream.write(summary + '\n\nSee the SARIF and check.json artifact for findings and diagnostics.\n')
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    sys.exit(main())
