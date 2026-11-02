#!/usr/bin/env python3
"""Check a stack boundary against its explicit base and immutable source trees."""
import argparse
import json
import subprocess
import re
import sys

MOLLY = '0e202dda90785771ff8c87526eaaf30c655f54f3'
OLD = 'c47a74ba0961903eeaeda6d22347a990056c3e83'
NEW = '6e9dddc01f1b26e608ac683807c51e139c141ed2'
FOREST = '7a280ac23b61cef2c09009a6dbed02773fff3dfa'
WORKFLOWS = {'.github/workflows/android.yml', '.github/workflows/molly-patch-release.yml'}
RELEASE_PATHS = frozenset(['patches/ports/signal-8.33.2/source-checkpoint.bundle', 'patches/ports/signal-8.33.2/.gitattributes', 'patches/ports/signal-8.33.2/followups/cdn-transport-test-fixture/0001-cdn-transport-test-fixture.patch', 'patches/ports/signal-8.33.2/followups/cdn-transport-test-fixture/series', 'patches/ports/signal-8.33.2/followups/integration-and-regression-tests/0001-integration-and-regression-tests.patch', 'patches/ports/signal-8.33.2/followups/integration-and-regression-tests/series', 'patches/ports/signal-8.33.2/followups/netcipher-sdk-dsl/0001-netcipher-sdk-dsl.patch', 'patches/ports/signal-8.33.2/followups/netcipher-sdk-dsl/series', 'patches/ports/signal-8.33.2/followups/restore-preference-import/0001-restore-preference-import.patch', 'patches/ports/signal-8.33.2/followups/restore-preference-import/series', 'patches/ports/signal-8.33.2/initial/app-updates/0001-resolve-app-updates.patch', 'patches/ports/signal-8.33.2/initial/app-updates/series', 'patches/ports/signal-8.33.2/initial/appearance/0001-resolve-appearance.patch', 'patches/ports/signal-8.33.2/initial/appearance/series', 'patches/ports/signal-8.33.2/initial/backup-and-restore/0001-resolve-backup-and-restore.patch', 'patches/ports/signal-8.33.2/initial/backup-and-restore/series', 'patches/ports/signal-8.33.2/initial/build-and-release/0001-resolve-build-and-release.patch', 'patches/ports/signal-8.33.2/initial/build-and-release/series', 'patches/ports/signal-8.33.2/initial/integration/0001-resolve-integration.patch', 'patches/ports/signal-8.33.2/initial/integration/0002-resolve-integration.patch', 'patches/ports/signal-8.33.2/initial/integration/0003-resolve-integration.patch', 'patches/ports/signal-8.33.2/initial/integration/series', 'patches/ports/signal-8.33.2/initial/maps/0001-resolve-maps.patch', 'patches/ports/signal-8.33.2/initial/maps/series', 'patches/ports/signal-8.33.2/initial/module-pruning/0001-resolve-module-pruning.patch', 'patches/ports/signal-8.33.2/initial/module-pruning/0002-resolve-module-pruning.patch', 'patches/ports/signal-8.33.2/initial/module-pruning/0003-resolve-module-pruning.patch', 'patches/ports/signal-8.33.2/initial/module-pruning/0004-resolve-module-pruning.patch', 'patches/ports/signal-8.33.2/initial/module-pruning/0005-resolve-module-pruning.patch', 'patches/ports/signal-8.33.2/initial/module-pruning/0006-resolve-module-pruning.patch', 'patches/ports/signal-8.33.2/initial/module-pruning/0007-resolve-module-pruning.patch', 'patches/ports/signal-8.33.2/initial/module-pruning/series', 'patches/ports/signal-8.33.2/initial/networking-and-proxies/0001-resolve-networking-and-proxies.patch', 'patches/ports/signal-8.33.2/initial/networking-and-proxies/series', 'patches/ports/signal-8.33.2/initial/notifications-and-unifiedpush/0001-resolve-notifications-and-unifiedpush.patch', 'patches/ports/signal-8.33.2/initial/notifications-and-unifiedpush/series', 'patches/ports/signal-8.33.2/initial/payments-and-donations/0001-resolve-payments-and-donations.patch', 'patches/ports/signal-8.33.2/initial/payments-and-donations/series', 'patches/ports/signal-8.33.2/initial/privacy-controls/0001-resolve-privacy-controls.patch', 'patches/ports/signal-8.33.2/initial/privacy-controls/series', 'patches/ports/signal-8.33.2/initial/registration-and-linking/0001-resolve-registration-and-linking.patch', 'patches/ports/signal-8.33.2/initial/registration-and-linking/0002-resolve-registration-and-linking.patch', 'patches/ports/signal-8.33.2/initial/registration-and-linking/series', 'patches/ports/signal-8.33.2/initial/resources-and-branding/0001-resolve-resources-and-branding.patch', 'patches/ports/signal-8.33.2/initial/resources-and-branding/0002-resolve-resources-and-branding.patch', 'patches/ports/signal-8.33.2/initial/resources-and-branding/0003-resolve-resources-and-branding.patch', 'patches/ports/signal-8.33.2/initial/resources-and-branding/0004-resolve-resources-and-branding.patch', 'patches/ports/signal-8.33.2/initial/resources-and-branding/0005-resolve-resources-and-branding.patch', 'patches/ports/signal-8.33.2/initial/resources-and-branding/0006-resolve-resources-and-branding.patch', 'patches/ports/signal-8.33.2/initial/resources-and-branding/0007-resolve-resources-and-branding.patch', 'patches/ports/signal-8.33.2/initial/resources-and-branding/0008-resolve-resources-and-branding.patch', 'patches/ports/signal-8.33.2/initial/resources-and-branding/0009-resolve-resources-and-branding.patch', 'patches/ports/signal-8.33.2/initial/resources-and-branding/0010-resolve-resources-and-branding.patch', 'patches/ports/signal-8.33.2/initial/resources-and-branding/0011-resolve-resources-and-branding.patch', 'patches/ports/signal-8.33.2/initial/resources-and-branding/0012-resolve-resources-and-branding.patch', 'patches/ports/signal-8.33.2/initial/resources-and-branding/0013-resolve-resources-and-branding.patch', 'patches/ports/signal-8.33.2/initial/resources-and-branding/0014-resolve-resources-and-branding.patch', 'patches/ports/signal-8.33.2/initial/resources-and-branding/series', 'patches/ports/signal-8.33.2/initial/security-and-locking/0001-resolve-security-and-locking.patch', 'patches/ports/signal-8.33.2/initial/security-and-locking/series', 'patches/ports/signal-8.33.2/provenance.json', 'patches/ports/signal-8.33.2/series', 'patches/ports/signal-8.33.2/report-metadata.json'])

# Exact AI-assisted compatibility conversion artifacts for existing release layers.
RELEASE_PATHS = RELEASE_PATHS.union({
    'patches/ports/signal-8.33.2/followups/cdn-transport-test-fixture/0001-cdn-transport-test-fixture-converted.patch',
    'patches/ports/signal-8.33.2/followups/integration-and-regression-tests/0001-integration-and-regression-tests-converted.patch',
    'patches/ports/signal-8.33.2/followups/integration-and-regression-tests/delete-files.json',
    'patches/ports/signal-8.33.2/followups/netcipher-sdk-dsl/0001-netcipher-sdk-dsl-converted.patch',
    'patches/ports/signal-8.33.2/followups/restore-preference-import/0001-restore-preference-import-converted.patch',
    'patches/ports/signal-8.33.2/initial/app-updates/0001-resolve-app-updates-converted.patch',
    'patches/ports/signal-8.33.2/initial/app-updates/delete-files.json',
    'patches/ports/signal-8.33.2/initial/appearance/0001-resolve-appearance-converted.patch',
    'patches/ports/signal-8.33.2/initial/backup-and-restore/0001-resolve-backup-and-restore-converted.patch',
    'patches/ports/signal-8.33.2/initial/backup-and-restore/delete-files.json',
    'patches/ports/signal-8.33.2/initial/build-and-release/0001-resolve-build-and-release-converted.patch',
    'patches/ports/signal-8.33.2/initial/build-and-release/delete-files.json',
    'patches/ports/signal-8.33.2/initial/build-and-release/generate-translations.json',
    'patches/ports/signal-8.33.2/initial/integration/0001-resolve-integration-converted.patch',
    'patches/ports/signal-8.33.2/initial/integration/0002-resolve-integration-converted.patch',
    'patches/ports/signal-8.33.2/initial/integration/0003-resolve-integration-converted.patch',
    'patches/ports/signal-8.33.2/initial/integration/delete-files.json',
    'patches/ports/signal-8.33.2/initial/maps/0001-resolve-maps-converted.patch',
    'patches/ports/signal-8.33.2/initial/module-pruning/delete-files.json',
    'patches/ports/signal-8.33.2/initial/networking-and-proxies/0001-resolve-networking-and-proxies-converted.patch',
    'patches/ports/signal-8.33.2/initial/networking-and-proxies/delete-files.json',
    'patches/ports/signal-8.33.2/initial/notifications-and-unifiedpush/0001-resolve-notifications-and-unifiedpush-converted.patch',
    'patches/ports/signal-8.33.2/initial/notifications-and-unifiedpush/delete-files.json',
    'patches/ports/signal-8.33.2/initial/payments-and-donations/0001-resolve-payments-and-donations-converted.patch',
    'patches/ports/signal-8.33.2/initial/payments-and-donations/delete-files.json',
    'patches/ports/signal-8.33.2/initial/privacy-controls/0001-resolve-privacy-controls-converted.patch',
    'patches/ports/signal-8.33.2/initial/registration-and-linking/0001-resolve-registration-and-linking-converted.patch',
    'patches/ports/signal-8.33.2/initial/registration-and-linking/0002-resolve-registration-and-linking-converted.patch',
    'patches/ports/signal-8.33.2/initial/registration-and-linking/delete-files.json',
    'patches/ports/signal-8.33.2/initial/resources-and-branding/0001-resolve-resources-and-branding-converted.patch',
    'patches/ports/signal-8.33.2/initial/resources-and-branding/0002-resolve-resources-and-branding-converted.patch',
    'patches/ports/signal-8.33.2/initial/resources-and-branding/0003-resolve-resources-and-branding-converted.patch',
    'patches/ports/signal-8.33.2/initial/resources-and-branding/0004-resolve-resources-and-branding-converted.patch',
    'patches/ports/signal-8.33.2/initial/resources-and-branding/0005-resolve-resources-and-branding-converted.patch',
    'patches/ports/signal-8.33.2/initial/resources-and-branding/0006-resolve-resources-and-branding-converted.patch',
    'patches/ports/signal-8.33.2/initial/resources-and-branding/0007-resolve-resources-and-branding-converted.patch',
    'patches/ports/signal-8.33.2/initial/resources-and-branding/0008-resolve-resources-and-branding-converted.patch',
    'patches/ports/signal-8.33.2/initial/resources-and-branding/0009-resolve-resources-and-branding-converted.patch',
    'patches/ports/signal-8.33.2/initial/resources-and-branding/0010-resolve-resources-and-branding-converted.patch',
    'patches/ports/signal-8.33.2/initial/resources-and-branding/0011-resolve-resources-and-branding-converted.patch',
    'patches/ports/signal-8.33.2/initial/resources-and-branding/0012-resolve-resources-and-branding-converted.patch',
    'patches/ports/signal-8.33.2/initial/resources-and-branding/0013-resolve-resources-and-branding-converted.patch',
    'patches/ports/signal-8.33.2/initial/resources-and-branding/delete-files.json',
    'patches/ports/signal-8.33.2/initial/security-and-locking/0001-resolve-security-and-locking-converted.patch',
    'patches/ports/signal-8.33.2/initial/security-and-locking/delete-files.json',
})
BEHAVIOR = {'reregistration-linked-devices-reminder', 'legacy-backup-precedence',
            'donation-completion', 'backup-notification-id-upgrade'}
ADDITIONS = {'registration-network-settings', 'donation-completion-copy'}
FEATURES = {'retention-controls', 'firebase-free-build'}


def addition_files(name):
    return ('series', '0001-' + name + '.patch', 'source-checkpoint.bundle')


def git(repo, *args, check=True):
    return subprocess.run(['git', '-C', str(repo), *args], stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, check=check).stdout


def metadata(path):
    return (path.startswith('tools/patches/') or path.startswith('patches/') or
            path in {'molly-build.json', 'docs/PATCH-PIPELINE.md', 'docs/PORT-REPORT.md'} or
            path in WORKFLOWS)


def report_evidence(path):
    prefix = 'patches/ports/signal-8.33.2/report-evidence/'
    if not path.startswith(prefix):
        return False
    name = path[len(prefix):]
    locale_evidence = {
        'translation-task-witness.tsv',
        'native-translation-byte-witness.tsv',
        'locale-revert-cohort.tsv', 'locale-revert-cohort.patch',
        'locale-revert-cohort.md', 'locale-revert-cohort.py',
        'locale-resource-probe.md', 'locale-resource-probe.py', 'locale-resource-probe.log',
        'locale-cohort-two-readme.md', 'locale-cohort-two-cohort.tsv',
        'locale-cohort-two-cohort.patch', 'locale-cohort-two-probe.py',
        'locale-cohort-two-resource-probe.py', 'locale-cohort-two-run.log',
        'locale-cohort-two-resource.log',
        'locale-remaining-readme.md', 'locale-remaining-cohort.tsv',
        'locale-remaining-cohort.patch', 'locale-remaining-resource.py',
        'locale-remaining-contracts.py', 'locale-remaining-resource.log',
        'locale-remaining-contracts.log',
        'post-format-README.md', 'post-format-classify.py',
        'post-format-cohort.patch', 'post-format-probe.py',
        'post-format-results.tsv', 'post-format-translation-xml-proof.py',
    }
    return name in {'receipts.md', 'cohort-receipts.md'} or name in locale_evidence or bool(re.fullmatch(r'(?:fmt-(?:0[1-9]|1[0-6])|lex-(?:0[1-9]|[1-6][0-9]|7[0-4])|(?:locale-format|hunk)-[0-9a-f]{20})\.patch', name))


def allowed(scope, path):
    if scope == 'pipeline':
        return path.startswith('tools/patches/') or path == 'docs/PATCH-PIPELINE.md' or path in WORKFLOWS
    if scope in ('switch', 'release'):
        if not metadata(path):
            return True  # exact source projection checked independently below
        if scope == 'switch':
            return path.startswith('patches/molly/') or path == 'molly-build.json'
        return report_evidence(path) or path in RELEASE_PATHS or path in {'molly-build.json', 'docs/PATCH-PIPELINE.md', 'docs/PORT-REPORT.md'}
    if scope in ('followups', 'additions', 'features'):
        if report_evidence(path):
            return True
        prefix = 'patches/ports/signal-8.33.2/'
        if path in {'molly-build.json', 'docs/PATCH-PIPELINE.md', 'docs/PORT-REPORT.md', prefix + 'series', prefix + 'provenance.json', prefix + 'report-metadata.json'}:
            return True
        if scope == 'followups':
            return any(path.startswith(prefix + 'followups/' + name + '/') for name in BEHAVIOR)
        return any(path in {prefix + 'followups/' + layer + '/' + name for name in addition_files(layer)}
                   for layer in (ADDITIONS if scope == 'additions' else FEATURES))
    return False


def check_scope(repo, scope, base, head='HEAD'):
    if scope not in ('pipeline', 'switch', 'release', 'followups', 'additions', 'features'):
        raise ValueError('unknown scope')
    if subprocess.run(['git', '-C', str(repo), 'merge-base', '--is-ancestor', base, head],
                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
        raise ValueError('base is not an ancestor of head')
    changed = git(repo, 'diff', '--no-renames', '--name-only', '-z', base, head).split(b'\0')
    for raw in filter(None, changed):
        path = raw.decode('utf-8', 'surrogateescape')
        if not allowed(scope, path):
            raise ValueError('path outside scope: ' + path)
    # These are the only workflow exceptions: one deletion and one regular file addition.
    def entry(ref, path):
        return git(repo, 'ls-tree', ref, '--', path).decode().strip()
    if entry(head, '.github/workflows/android.yml'):
        raise ValueError('Signal Android workflow must be absent')
    workflow = entry(head, '.github/workflows/molly-patch-release.yml')
    if not workflow.startswith('100644 blob '):
        raise ValueError('Molly workflow must be a regular 100644 file')
    if scope == 'pipeline':
        if entry(base, '.github/workflows/molly-patch-release.yml'):
            raise ValueError('Molly workflow must be added on pipeline branch')
    elif entry(base, '.github/workflows/molly-patch-release.yml') != workflow:
        raise ValueError('Molly workflow must be inherited unchanged')
    def exists(ref, path):
        return subprocess.run(['git', '-C', str(repo), 'cat-file', '-e', ref + ':' + path],
                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
    if scope == 'pipeline':
        if git(repo, 'rev-parse', base + '^{commit}').decode().strip() != MOLLY:
            raise ValueError('pipeline base must be the official Molly commit')
    else:
        lower_source = {'switch': MOLLY, 'release': OLD, 'followups': NEW, 'additions': NEW, 'features': NEW}[scope]
        for raw in filter(None, git(repo, 'diff', '--no-renames', '--name-only', '-z', lower_source, base).split(b'\0')):
            if not metadata(raw.decode('utf-8', 'surrogateescape')):
                raise ValueError('lower base has incorrect source projection')
        base_profile = git(repo, 'show', base + ':molly-build.json', check=False)
        if scope == 'switch':
            if base_profile or exists(base, 'patches/molly'):
                raise ValueError('switch base must be the pipeline phase')
        else:
            try:
                lower = json.loads(base_profile)
                if lower['signal']['commit'] != lower_source or (lower['port'] is None) != (scope == 'release'):
                    raise ValueError('incorrect lower base profile')
            except (KeyError, TypeError, json.JSONDecodeError) as exc:
                raise ValueError('invalid lower base profile') from exc
            if not exists(base, 'patches/molly') or git(repo, 'rev-parse', base + ':patches/molly').decode().strip() != FOREST:
                raise ValueError('incorrect lower base forest')
        if scope == 'followups' and any(exists(base, 'patches/ports/signal-8.33.2/followups/' + name) for name in BEHAVIOR):
            raise ValueError('followups base already contains behavior patches')
    if scope == 'release' and any(exists(head, 'patches/ports/signal-8.33.2/followups/' + name) for name in BEHAVIOR):
        raise ValueError('release phase contains behavior patches')
    if scope == 'followups' and not all(exists(head, 'patches/ports/signal-8.33.2/followups/' + name) for name in BEHAVIOR):
        raise ValueError('followups phase is incomplete')
    if scope == 'additions':
        for layer in ADDITIONS:
            addition_path = 'patches/ports/signal-8.33.2/followups/' + layer
            if exists(base, addition_path):
                raise ValueError('additions base already contains ' + layer)
            if not all(exists(head, addition_path + '/' + name) for name in addition_files(layer)):
                raise ValueError('additions phase is incomplete')
    if scope == 'features':
        for layer in FEATURES:
            feature_path = 'patches/ports/signal-8.33.2/followups/' + layer
            if exists(base, feature_path):
                raise ValueError('features base already contains ' + layer)
            if not all(exists(head, feature_path + '/' + name) for name in addition_files(layer)):
                raise ValueError('features phase is incomplete')
    # Verify lower phases from their own ancestors, so changes hidden in a base
    # cannot escape the adjacent diff. Each recursion moves to an earlier phase.
    if scope == 'switch':
        check_scope(repo, 'pipeline', MOLLY, base)
    elif scope in ('release', 'followups', 'additions', 'features'):
        lower_phase = {'release': 'switch', 'followups': 'release', 'additions': 'followups', 'features': 'additions'}[scope]
        candidates = git(repo, 'rev-list', '--ancestry-path', MOLLY + '..' + base).decode().splitlines()
        for candidate in candidates:
            try:
                check_scope(repo, lower_phase, candidate, base)
                break
            except (ValueError, subprocess.CalledProcessError):
                continue
        else:
            raise ValueError('lower base has no validated preceding stack phase')
    source = {'pipeline': MOLLY, 'switch': OLD, 'release': NEW, 'followups': NEW, 'additions': NEW, 'features': NEW}[scope]
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
    return {'scope': scope, 'profile_present': scope != 'pipeline', 'port_present': scope in ('release', 'followups', 'additions', 'features')}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--scope', required=True, choices=('pipeline', 'switch', 'release', 'followups', 'additions', 'features'))
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
