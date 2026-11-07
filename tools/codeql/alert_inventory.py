#!/usr/bin/env python3
"""Reduce paginated GitHub CodeQL alerts to review metadata, without code snippets."""

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import sys


def reduce_alerts(pages, repository, ref):
    if not isinstance(pages, list) or not pages:
        raise ValueError('expected at least one page from gh api --paginate --slurp')
    alerts = []
    numbers = set()
    for page in pages:
        if not isinstance(page, list):
            raise ValueError('each API page must be an array')
        for alert in page:
            if not isinstance(alert, dict):
                raise ValueError('each alert must be an object')
            number = alert.get('number')
            if type(number) is not int or number < 1 or number in numbers:
                raise ValueError('missing or duplicate alert number')
            numbers.add(number)
            if alert.get('state') != 'open' or alert.get('tool', {}).get('name') != 'CodeQL':
                raise ValueError('response contains an alert outside the requested open CodeQL filter')
            rule = alert['rule']
            instance = alert['most_recent_instance']
            location = instance['location']
            if instance.get('ref') != ref:
                raise ValueError('response contains an alert instance outside the requested ref')
            if not rule.get('id') or not location.get('path') or not alert.get('html_url'):
                raise ValueError('alert is missing its rule, location, or URL')
            alerts.append({
                'number': number,
                'rule_id': rule['id'],
                'severity': rule.get('severity'),
                'security_severity': rule.get('security_severity_level'),
                'state': alert['state'],
                'html_url': alert['html_url'],
                'path': location['path'],
                'start_line': location.get('start_line'),
                'end_line': location.get('end_line'),
                'ref': instance['ref'],
                'commit_sha': instance.get('commit_sha'),
                'analysis_key': instance.get('analysis_key'),
                'category': instance.get('category'),
            })
    return {
        'schema': 1,
        'status': 'complete',
        'repository': repository,
        'ref': ref,
        'tool': 'CodeQL',
        'state': 'open',
        'fetched_at': datetime.now(timezone.utc).isoformat(),
        'page_count': len(pages),
        'alert_count': len(alerts),
        'alerts': sorted(alerts, key=lambda item: item['number']),
    }


def unavailable(repository, ref, reason):
    return {
        'schema': 1,
        'status': 'unavailable',
        'repository': repository,
        'ref': ref,
        'tool': 'CodeQL',
        'state': 'open',
        'fetched_at': datetime.now(timezone.utc).isoformat(),
        'reason': reason,
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository', required=True)
    parser.add_argument('--ref', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--unavailable')
    args = parser.parse_args(argv)
    status = 0
    if args.unavailable:
        report = unavailable(args.repository, args.ref, args.unavailable)
    else:
        try:
            report = reduce_alerts(json.load(sys.stdin), args.repository, args.ref)
        except (ValueError, KeyError, TypeError, AttributeError) as exc:
            report = unavailable(args.repository, args.ref, 'API response could not be validated')
            print(json.dumps({'inventory_error': str(exc)}), file=sys.stderr)
            status = 1
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    if report['status'] == 'complete':
        summary = f"Default-branch CodeQL inventory: {report['alert_count']} open alerts ({report['page_count']} API pages)."
    else:
        summary = 'Default-branch CodeQL inventory: unavailable; see the artifact for the reason.'
    print(summary)
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as stream:
            stream.write(summary + '\n')
    return status


if __name__ == '__main__':
    sys.exit(main())
