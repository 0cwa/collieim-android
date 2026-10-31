#!/usr/bin/env python3
"""Check a stack boundary against its explicit base and immutable source trees."""
import argparse
import json
import subprocess
import sys

MOLLY = '0e202dda90785771ff8c87526eaaf30c655f54f3'
OLD = 'c47a74ba0961903eeaeda6d22347a990056c3e83'
NEW = '6e9dddc01f1b26e608ac683807c51e139c141ed2'
FOREST = '7a280ac23b61cef2c09009a6dbed02773fff3dfa'
WORKFLOWS = {'.github/workflows/android.yml', '.github/workflows/molly-patch-release.yml'}
BEHAVIOR = {'reregistration-linked-devices-reminder', 'legacy-backup-precedence',
            'donation-completion', 'backup-notification-id-upgrade'}


def git(repo, *args, check=True):
    return subprocess.run(['git', '-C', str(repo), *args], stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, check=check).stdout


def metadata(path):
    return (path.startswith('tools/patches/') or path.startswith('patches/') or
            path in {'molly-build.json', 'docs/PATCH-PIPELINE.md', 'docs/PORT-REPORT.md'} or
            path in WORKFLOWS)


def allowed(scope, path):
    if scope == 'pipeline':
        return path.startswith('tools/patches/') or path == 'docs/PATCH-PIPELINE.md' or path in WORKFLOWS
    if scope in ('switch', 'release'):
        if not metadata(path):
            return True  # exact source projection checked independently below
        if scope == 'switch':
            return path.startswith('patches/molly/') or path == 'molly-build.json'
        return (path.startswith('patches/ports/signal-8.33.2/') and not any(
            path.startswith('patches/ports/signal-8.33.2/followups/' + name + '/') for name in BEHAVIOR)) or path in {'molly-build.json', 'docs/PORT-REPORT.md'}
    if scope == 'followups':
        prefix = 'patches/ports/signal-8.33.2/'
        if path in {'molly-build.json', 'docs/PORT-REPORT.md', prefix + 'series', prefix + 'provenance.json'}:
            return True
        return any(path.startswith(prefix + 'followups/' + name + '/') for name in BEHAVIOR)
    return False


def check_scope(repo, scope, base, head='HEAD'):
    if scope not in ('pipeline', 'switch', 'release', 'followups'):
        raise ValueError('unknown scope')
    if subprocess.run(['git', '-C', str(repo), 'merge-base', '--is-ancestor', base, head],
                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
        raise ValueError('base is not an ancestor of head')
    changed = git(repo, 'diff', '--no-renames', '--name-only', '-z', base, head).split(b'\0')
    for raw in filter(None, changed):
        path = raw.decode('utf-8', 'surrogateescape')
        if not allowed(scope, path):
            raise ValueError('path outside scope: ' + path)
    source = {'pipeline': MOLLY, 'switch': OLD, 'release': NEW, 'followups': NEW}[scope]
    paths = git(repo, 'diff', '--no-renames', '--name-only', '-z', source, head).split(b'\0')
    for raw in filter(None, paths):
        path = raw.decode('utf-8', 'surrogateescape')
        if not metadata(path):
            raise ValueError('source projection differs: ' + path)
    probe = subprocess.run(['git', '-C', str(repo), 'rev-parse', head + ':patches/molly'],
                           stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    forest = probe.stdout.decode().strip() if probe.returncode == 0 else ''
    if scope == 'pipeline':
        if forest:
            raise ValueError('pipeline branch must not contain Molly forest')
    elif forest != FOREST:
        raise ValueError('Molly forest does not match the official snapshot')
    profile = git(repo, 'show', head + ':molly-build.json', check=False)
    if scope == 'pipeline':
        if profile:
            raise ValueError('pipeline branch must not contain a profile')
    else:
        try:
            config = json.loads(profile)
            if (config['signal']['commit'] != source or config['molly']['target'] != MOLLY or
                config['molly']['tree'] != '3cd0bb01cf840e4b702187085432020d163d80ce' or
                config['native'] is not None or (config['port'] is None) != (scope == 'switch')):
                raise ValueError('invalid profile pins')
        except (KeyError, TypeError, json.JSONDecodeError) as exc:
            raise ValueError('invalid profile') from exc
    return {'scope': scope, 'profile_present': scope != 'pipeline', 'port_present': scope in ('release', 'followups')}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--scope', required=True, choices=('pipeline', 'switch', 'release', 'followups'))
    parser.add_argument('--base', required=True)
    parser.add_argument('--head', default='HEAD')
    parser.add_argument('--repo', default='.')
    args = parser.parse_args()
    try:
        print(json.dumps(check_scope(args.repo, args.scope, args.base, args.head), sort_keys=True))
    except (ValueError, subprocess.CalledProcessError) as exc:
        print('scope check failed: ' + str(exc), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
