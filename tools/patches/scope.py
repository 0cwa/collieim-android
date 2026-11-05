#!/usr/bin/env python3
"""Check a stack boundary against its explicit base and immutable source trees."""
import argparse
import copy
import json
import subprocess
import re
import sys

MOLLY = '0e202dda90785771ff8c87526eaaf30c655f54f3'
OLD = 'c47a74ba0961903eeaeda6d22347a990056c3e83'
NEW = '6e9dddc01f1b26e608ac683807c51e139c141ed2'
FOREST = '7a280ac23b61cef2c09009a6dbed02773fff3dfa'
REVIEWED_PROFILES = '89bd7d6368a509470c4612d079746f66dfac8b55'
ICON_OVERLAY = 'patches/forks/signal-foss/collie-icon/series'
WORKFLOWS = {'.github/workflows/android.yml', '.github/workflows/diffuse.yml', '.github/workflows/molly-patch-release.yml', '.github/workflows/molly-unsigned-release.yml'}
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
            'donation-completion', 'backup-notification-id-upgrade',
            'passphrase-timeout-fallback', 'backup-revision-walk', 'restore-badges-log-theme',
            'network-initialization-cycle', 'enable-linked-history-transfer',
            'android-instrumentation-compatibility'}
ADDITIONS = {'registration-network-settings', 'donation-completion-copy', 'unifiedpush-registration-retry'}
FEATURES = ('retention-controls', 'firebase-free-build')


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
    return name in {'receipts.md', 'cohort-receipts.md', 'action-start-trees.bundle'} or name in locale_evidence or bool(re.fullmatch(r'(?:fmt-(?:0[1-9]|1[0-6])|lex-(?:0[1-9]|[1-6][0-9]|7[0-4])|(?:locale-format|hunk)-[0-9a-f]{20})\.patch', name))


def allowed(scope, path):
    if scope == 'pipeline':
        return path.startswith('tools/patches/') or path == 'docs/PATCH-PIPELINE.md' or path in WORKFLOWS
    if scope in ('switch', 'release'):
        if not metadata(path):
            return True  # exact source projection checked independently below
        if scope == 'switch':
            return path.startswith('patches/molly/') or path in {'molly-build.json', '.github/workflows/diffuse.yml'}
        return report_evidence(path) or path in RELEASE_PATHS or path in {'molly-build.json', 'docs/PATCH-PIPELINE.md', 'docs/PORT-REPORT.md'}
    if scope == 'forks':
        prefix = 'patches/forks/signal-foss/'
        return (path in {'molly-build.json', 'docs/PORT-REPORT.md', 'docs/PATCH-PIPELINE.md',
                         'tools/patches/build.py', 'tools/patches/release_profiles.py',
                         'tools/patches/release_artifacts.py', 'tools/patches/test_build.py',
                         'tools/patches/test_fork_profiles.py', 'tools/patches/test_release_artifacts.py'} or
                path in {prefix + 'provenance.json', prefix + 'upstream-source.bundle'} or
                any(path.startswith(prefix + layer + '/')
                    for layer in ('firebase-free-build', 'retention-controls', 'retention-controls-fixes', 'collie-icon', 'collie-branding')))
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


def check_feature_chain(repo, base, head):
    prefix = 'patches/ports/signal-8.33.2/'
    def read(ref, path):
        return git(repo, 'show', ref + ':' + path)
    try:
        base_series = read(base, prefix + 'series').splitlines()
        head_series = read(head, prefix + 'series').splitlines()
        patches = ['followups/' + name + '/0001-' + name + '.patch' for name in FEATURES]
        if head_series != base_series + [path.encode() for path in patches]:
            raise ValueError('features series is not the exact ordered extension')
        before = json.loads(read(base, prefix + 'provenance.json'))
        after = json.loads(read(head, prefix + 'provenance.json'))
        base_profile = json.loads(read(base, 'molly-build.json'))
        profile = json.loads(read(head, 'molly-build.json'))
        old_layers, layers = before['layers'], after['layers']
        if layers[:-2] != old_layers or len(layers) != len(old_layers) + 2:
            raise ValueError('features provenance changed prior layers')
        if before['target_tree'] != base_profile['target_tree'] or old_layers[-1]['after_tree'] != before['target_tree']:
            raise ValueError('features base checkpoint differs')
        previous_commit, previous_tree = old_layers[-1]['source_commit'], before['target_tree']
        for layer, name, patch in zip(layers[-2:], FEATURES, patches):
            if (layer['id'] != 'followups/' + name or layer['patches'] != [patch] or
                layer['source_bundle'] != 'followups/' + name + '/source-checkpoint.bundle' or
                layer['source_bundle_prerequisite'] != previous_commit or layer['before_tree'] != previous_tree or
                patch not in after['mailbox_sha256']):
                raise ValueError('features provenance chain differs')
            previous_commit, previous_tree = layer['source_commit'], layer['after_tree']
        if after['target_tree'] != previous_tree or profile['target_tree'] != previous_tree:
            raise ValueError('features final checkpoint differs')
        if any(after['mailbox_sha256'].get(k) != v for k, v in before['mailbox_sha256'].items()):
            raise ValueError('features provenance changed prior mailbox pins')
    except (KeyError, IndexError, TypeError, json.JSONDecodeError, subprocess.CalledProcessError) as exc:
        raise ValueError('invalid features chain metadata') from exc


def check_release_profiles(reviewed, current):
    """Constrain release composition to the reviewed products and icon opt-in."""
    expected = copy.deepcopy(reviewed)
    actual = copy.deepcopy(current)
    for name in ('molly-continued', 'molly-foss-continued'):
        if actual['profiles'][name].pop('icon_overlay', None) != ICON_OVERLAY:
            raise ValueError('Continued profiles must select the icon overlay')
    for name in ('molly-continued', 'molly-foss-continued', 'collie', 'collie-foss'):
        if not re.fullmatch(r'[0-9a-f]{40}', actual['profiles'][name].get('target_tree', '')):
            raise ValueError('invalid release profile target tree')
        actual['profiles'][name]['target_tree'] = expected['profiles'][name]['target_tree']
    if actual != expected:
        raise ValueError('release profiles differ from reviewed composition')


# Exact legacy-main files deliberately retained when the release-base is integrated.
# No other source projection difference is accepted for the main-based fork PR.
MAIN_INTEGRATION_LOWER = '7f0eb01a0ab472deeef3b528e7f508425044fa6a'

MAIN_PROJECTION_EXCEPTIONS = frozenset({
    '.github/FUNDING.yml', '.github/workflows/sync.yml',
    '.github/workflows/tagrelease.yml', 'README.md', 'SECURITY.md',
    'assets/badge_obtainium.png', 'assets/collie-foreground.svg',
    'assets/collie-icon.svg', 'pkcs11.config',
})



# Exact audited main-preservation and portability entries; includes absence and mode.
MAIN_CLEAN_ENTRIES = {'.github/FUNDING.yml': '100644 blob 11753b3922973457294af7b56b15e331ba0727c0', '.github/workflows/sync.yml': '100644 blob bdf031c73da6958ebdc9b0c6bebaab544f17044e', '.github/workflows/tagrelease.yml': '100644 blob 1ac9505d768d2d735f99f3617ce7895ff6c1779d', 'README.md': '100644 blob 8683baec492bb93941f1d79e1d35ebf5fde1cae5', 'SECURITY.md': '100644 blob b6cf6842d3bfad6e695f0a557e52ce0f9ccbfa11', 'assets/badge_obtainium.png': '100644 blob 6972dfce0c53c771613eac3b39f117c6286e86ff', 'assets/collie-foreground.svg': '100644 blob 84a52ead814cd088baca5872220965b3c2dce829', 'assets/collie-icon.svg': '100644 blob e25657dfeea8d446b8e72de14235241367841cbb', 'docs/PORT-REPORT.md': '100644 blob 747b2ee92f0ff3d30d892a3fb94933ad7dfd08bd', 'patches/ports/signal-8.33.2/report-evidence/locale-cohort-two-probe.py': '100644 blob 38663e381bc82a783e43f1cd7728d9d76e370aa2', 'patches/ports/signal-8.33.2/report-evidence/locale-cohort-two-readme.md': '100644 blob 7931f5846e361e26f8017d56c7a13a8f3b196be1', 'patches/ports/signal-8.33.2/report-evidence/locale-cohort-two-resource-probe.py': '100644 blob b2f37a8d1076b1d0e983d413b55384ddad4b19dd', 'patches/ports/signal-8.33.2/report-evidence/locale-cohort-two-resource.log': '100644 blob d8658aed6c8a3f48464a832c71151a036446c6bf', 'patches/ports/signal-8.33.2/report-evidence/locale-remaining-contracts.log': '100644 blob 629b5a9eaadfedb948784f88b10db7585f79a561', 'patches/ports/signal-8.33.2/report-evidence/locale-remaining-readme.md': '100644 blob 55ca274dd01aeeabd646fed123c57c788e4a643f', 'patches/ports/signal-8.33.2/report-evidence/locale-remaining-resource.log': '100644 blob 1274898e32c64371cfcde44ed8283b6d0e5e4bd9', 'patches/ports/signal-8.33.2/report-evidence/locale-resource-probe.log': '100644 blob 860ee298a3464da794e6df2acd15433f458ddea6', 'patches/ports/signal-8.33.2/report-evidence/locale-resource-probe.md': '100644 blob 02022af680729f809d25e1ae21659b813c91755e', 'patches/ports/signal-8.33.2/report-evidence/locale-resource-probe.py': '100644 blob 85f65dc1892f65466dd960155f5ba41121a383d6', 'patches/ports/signal-8.33.2/report-evidence/locale-revert-cohort.md': '100644 blob 864ca61436f0af9dcc22dd16321df68632696a93', 'patches/ports/signal-8.33.2/report-evidence/locale-revert-cohort.py': '100644 blob 47526bd3ad22424a71eb2f689223dad33b8a3f5d', 'patches/ports/signal-8.33.2/report-evidence/post-format-README.md': '100644 blob e21f6a282e1207360bc18afc9862b04a1abc1ddc', 'patches/ports/signal-8.33.2/report-evidence/post-format-classify.py': '100644 blob b1215bb6d68071faeeeaf2e0d67d97ce32da7a63', 'patches/ports/signal-8.33.2/report-evidence/post-format-probe.py': '100644 blob a9e04fd79dc7ee9b3ef3517003c706b058a634e6', 'patches/ports/signal-8.33.2/report-evidence/post-format-translation-xml-proof.py': '100644 blob 17b0840733244e57310f3b3f9e3dc65ea41642ab', 'patches/ports/signal-8.33.2/report-evidence/receipts.md': '100644 blob 89e2fd5b7a6e527f2d802dceafe14fbaa82c59dd', 'patches/ports/signal-8.33.2/report-metadata.json': '100644 blob 5bab057aa66fecb766102cc3d5613c9034b06318', 'pkcs11.config': ''}
MAIN_SCOPE_FILES = frozenset(['.github/workflows/molly-patch-release.yml', 'tools/patches/preflight.sh', 'tools/patches/release.py', 'tools/patches/scope.py', 'tools/patches/test_ci_scope_mapping.py', 'tools/patches/test_preflight.py', 'tools/patches/test_prepare_port_report.py', 'tools/patches/test_release.py', 'tools/patches/test_stack_scope.py'])
INITIAL_MAIN = '2d537903ec2b3f9f24d40208d164651c989c159d'

def check_trusted_integration_lower(repo, integration_lower):
    actual = git(repo, 'rev-parse', '--verify', integration_lower + '^{commit}').decode().strip()
    if actual != MAIN_INTEGRATION_LOWER:
        raise ValueError('unexpected trusted release-base commit')


def check_clean_projection(repo, candidate, lower):
    # The audited tree, not historical ancestry, establishes the clean base.
    changed = git(repo, 'diff', '--no-renames', '--name-only', '-z', lower, candidate).split(b'\0')
    for raw in filter(None, changed):
        path = raw.decode('utf-8', 'surrogateescape')
        if path not in MAIN_CLEAN_ENTRIES and path not in MAIN_SCOPE_FILES:
            raise ValueError('unexpected change to release base: ' + path)
    for path, expected in MAIN_CLEAN_ENTRIES.items():
        entry = git(repo, 'ls-tree', candidate, '--', path).decode().strip().split('\t')[0]
        if entry != expected:
            raise ValueError('audited integration entry differs: ' + path)


def check_main_transition(repo, base, head, integration_lower):
    check_trusted_integration_lower(repo, integration_lower)
    actual_base = git(repo, 'rev-parse', '--verify', base + '^{commit}').decode().strip()
    if actual_base != INITIAL_MAIN:
        raise ValueError('unexpected transition base')
    if subprocess.run(['git', '-C', str(repo), 'merge-base', '--is-ancestor', base, head],
                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
        raise ValueError('main base is not an ancestor of transition head')
    check_clean_projection(repo, head, integration_lower)
    # Verify the whole historic stack against its pinned source checkpoints.
    candidates = git(repo, 'rev-list', '--ancestry-path', MOLLY + '..' + integration_lower).decode().splitlines()
    for candidate in candidates:
        try:
            check_scope(repo, 'additions', candidate, integration_lower)
            break
        except (ValueError, subprocess.CalledProcessError):
            continue
    else:
        raise ValueError('pinned release base has no validated additions phase')
    return {'scope': 'main-transition', 'profile_present': True, 'port_present': True}


def check_main_docs(repo, base, head):
    if subprocess.run(['git', '-C', str(repo), 'merge-base', '--is-ancestor', base, head],
                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
        raise ValueError('main base is not an ancestor of head')
    for raw in filter(None, git(repo, 'diff', '--no-renames', '--name-only', '-z', base, head).split(b'\0')):
        path = raw.decode('utf-8', 'surrogateescape')
        if not (path in {'README.md', 'SECURITY.md', '.github/README.md'} or path.startswith(('docs/', 'assets/'))):
            raise ValueError('main documentation scope excludes: ' + path)
    return {'scope': 'main-docs', 'profile_present': False, 'port_present': False}


def check_main_integration(repo, base, head, integration_lower):
    check_clean_projection(repo, base, integration_lower)
    # Compare the complete tree entries, including mode and absence.
    for path in MAIN_PROJECTION_EXCEPTIONS:
        if git(repo, 'ls-tree', base, '--', path) != git(repo, 'ls-tree', head, '--', path):
            raise ValueError('main projection changed: ' + path)


def check_scope(repo, scope, base, head='HEAD', integration_lower=None):
    if scope == 'main-transition':
        if integration_lower is None:
            raise ValueError('main transition requires pinned release base')
        return check_main_transition(repo, base, head, integration_lower)
    if scope == 'main-docs':
        if integration_lower is not None:
            raise ValueError('main documentation must not use release base')
        return check_main_docs(repo, base, head)
    if integration_lower is not None:
        if scope != 'forks':
            raise ValueError('main integration is supported only for forks scope')
        check_trusted_integration_lower(repo, integration_lower)
        check_main_integration(repo, base, head, integration_lower)
    if scope not in ('pipeline', 'switch', 'release', 'followups', 'additions', 'features', 'forks'):
        raise ValueError('unknown scope')
    if subprocess.run(['git', '-C', str(repo), 'merge-base', '--is-ancestor', base, head],
                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
        raise ValueError('base is not an ancestor of head')
    changed = git(repo, 'diff', '--no-renames', '--name-only', '-z', base, head).split(b'\0')
    for raw in filter(None, changed):
        path = raw.decode('utf-8', 'surrogateescape')
        if not allowed(scope, path):
            raise ValueError('path outside scope: ' + path)
    # These are the only workflow exceptions: two deletions and two regular file additions. Signal's APK diff
    # workflow needs a runner that only Signal's organization has, so it would stay queued forever.
    def entry(ref, path):
        return git(repo, 'ls-tree', ref, '--', path).decode().strip()
    if entry(head, '.github/workflows/android.yml'):
        raise ValueError('Signal Android workflow must be absent')
    if scope != 'pipeline' and entry(head, '.github/workflows/diffuse.yml'):
        raise ValueError('Signal APK diff workflow must be absent')
    for path in ('.github/workflows/molly-patch-release.yml', '.github/workflows/molly-unsigned-release.yml'):
        workflow = entry(head, path)
        if not workflow.startswith('100644 blob '):
            raise ValueError('Molly workflow must be a regular 100644 file: ' + path)
        if scope == 'pipeline':
            if entry(base, path):
                raise ValueError('Molly workflow must be added on pipeline branch: ' + path)
        elif entry(base, path) != workflow:
            raise ValueError('Molly workflow must be inherited unchanged: ' + path)
    def exists(ref, path):
        return subprocess.run(['git', '-C', str(repo), 'cat-file', '-e', ref + ':' + path],
                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
    if scope == 'pipeline':
        if git(repo, 'rev-parse', base + '^{commit}').decode().strip() != MOLLY:
            raise ValueError('pipeline base must be the official Molly commit')
    else:
        lower_source = {'switch': MOLLY, 'release': OLD, 'followups': NEW, 'additions': NEW, 'features': NEW, 'forks': NEW}[scope]
        for raw in filter(None, git(repo, 'diff', '--no-renames', '--name-only', '-z', lower_source, base).split(b'\0')):
            path = raw.decode('utf-8', 'surrogateescape')
            if not metadata(path) and not (integration_lower is not None and path in MAIN_PROJECTION_EXCEPTIONS):
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
    if scope == 'forks':
        for path in ('tools/patches/scope.py', 'tools/patches/fork_profiles.py',
                     'tools/patches/line_origin.py', 'tools/patches/port_report.py',
                     'tools/patches/release.py'):
            if git(repo, 'rev-parse', base + ':' + path).strip() != git(repo, 'rev-parse', head + ':' + path).strip():
                raise ValueError('release tool differs from trusted base: ' + path)
        # PR17 introduced application identities; all other selector code must
        # remain exactly the trusted lower implementation.
        path = 'tools/patches/release_profiles.py'
        selector = git(repo, 'show', base + ':' + path).decode()
        identity = git(repo, 'show', REVIEWED_PROFILES + ':' + path).decode()
        start = identity.index('# User-visible app title')
        end = identity.index('POLICY =', start)
        selector = selector.replace('POLICY =', identity[start:end] + 'POLICY =', 1)
        old = "'fork_series': fork, 'firebase_free': entry['firebase_free']}"
        new = "'fork_series': fork, 'firebase_free': entry['firebase_free'],\n                            'app': dict(zip(('title', 'file_name', 'package_id'), APP[name]))}"
        if selector.count(old) != 1 or selector.replace(old, new, 1) != git(repo, 'show', head + ':' + path).decode():
            raise ValueError('release profile selector differs from trusted code and reviewed identity')
        if git(repo, 'rev-parse', base + ':patches/ports').strip() != git(repo, 'rev-parse', head + ':patches/ports').strip():
            raise ValueError('fork scope must preserve the base port series')
        try:
            prior = json.loads(git(repo, 'show', base + ':molly-build.json'))
            current = json.loads(git(repo, 'show', head + ':molly-build.json'))
            reviewed = json.loads(git(repo, 'show', REVIEWED_PROFILES + ':molly-build.json'))
            check_release_profiles({'profiles': reviewed['profiles'], 'fork_source': reviewed['fork_source']},
                                   {'profiles': current['profiles'], 'fork_source': current['fork_source']})
            current.pop('profiles', None)
            current.pop('fork_source', None)
            if current != prior:
                raise ValueError('fork scope may only add named profiles to the base configuration')
        except (KeyError, TypeError, json.JSONDecodeError, subprocess.CalledProcessError) as exc:
            raise ValueError('invalid fork profile configuration') from exc
    if scope == 'features':
        for layer in FEATURES:
            feature_path = 'patches/ports/signal-8.33.2/followups/' + layer
            if exists(base, feature_path):
                raise ValueError('features base already contains ' + layer)
            if not all(exists(head, feature_path + '/' + name) for name in addition_files(layer)):
                raise ValueError('features phase is incomplete')
        check_feature_chain(repo, base, head)
    # Verify lower phases from their own ancestors, so changes hidden in a base
    # cannot escape the adjacent diff. Each recursion moves to an earlier phase.
    if scope == 'switch':
        check_scope(repo, 'pipeline', MOLLY, base)
    elif scope in ('release', 'followups', 'additions', 'features', 'forks'):
        lower_phase = {'release': 'switch', 'followups': 'release', 'additions': 'followups', 'features': 'additions', 'forks': 'additions'}[scope]
        history_base = integration_lower if integration_lower is not None else base
        candidates = git(repo, 'rev-list', '--ancestry-path', MOLLY + '..' + history_base).decode().splitlines()
        for candidate in candidates:
            try:
                check_scope(repo, lower_phase, candidate, history_base)
                break
            except (ValueError, subprocess.CalledProcessError):
                continue
        else:
            raise ValueError('lower base has no validated preceding stack phase')
    source = {'pipeline': MOLLY, 'switch': OLD, 'release': NEW, 'followups': NEW, 'additions': NEW, 'features': NEW, 'forks': NEW}[scope]
    paths = git(repo, 'diff', '--no-renames', '--name-only', '-z', source, head).split(b'\0')
    for raw in filter(None, paths):
        path = raw.decode('utf-8', 'surrogateescape')
        if not metadata(path) and not (integration_lower is not None and path in MAIN_PROJECTION_EXCEPTIONS):
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
    return {'scope': scope, 'profile_present': scope != 'pipeline', 'port_present': scope in ('release', 'followups', 'additions', 'features', 'forks')}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--scope', required=True, choices=('pipeline', 'switch', 'release', 'followups', 'additions', 'features', 'forks', 'main-transition', 'main-docs'))
    parser.add_argument('--base', required=True)
    parser.add_argument('--head', default='HEAD')
    parser.add_argument('--repo', default='.')
    parser.add_argument('--integration-lower', help='trusted historical release-base for a main-based forks PR')
    args = parser.parse_args()
    try:
        print(json.dumps(check_scope(args.repo, args.scope, args.base, args.head, args.integration_lower), sort_keys=True))
    except (ValueError, subprocess.CalledProcessError) as exc:
        print('scope check failed: ' + str(exc), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
