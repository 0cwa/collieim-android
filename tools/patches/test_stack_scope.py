import pathlib
import copy
import json
import subprocess
import tempfile
import unittest
from unittest import mock
import scope


class ScopeTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.repo = pathlib.Path(self.tmp.name)
        self.git('init', '-q')
        self.git('config', 'user.email', 'test@example.invalid')
        self.git('config', 'user.name', 'Test')
        (self.repo / 'app.txt').write_text('original\n')
        manifest = self.repo / 'tools/patches/main-clean-entries.json'
        manifest.parent.mkdir(parents=True)
        manifest.write_text('{}\n')
        self.git('add', '.')
        self.git('commit', '-qm', 'base')
        pin = self.git('rev-parse', 'HEAD:tools/patches/main-clean-entries.json').strip()
        patcher = mock.patch.object(scope, 'MAIN_CLEAN_ENTRIES_BLOB', pin)
        patcher.start()
        self.addCleanup(patcher.stop)
        self.base = self.git('rev-parse', 'HEAD').strip()

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.repo), *args], text=True)

    def commit(self, name, content):
        path = self.repo / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        workflow = self.repo / '.github/workflows/molly-patch-release.yml'
        if not workflow.exists():
            workflow.parent.mkdir(parents=True, exist_ok=True)
            workflow.write_text('name: Molly\n')
        release_workflow = self.repo / '.github/workflows/molly-unsigned-release.yml'
        if not release_workflow.exists():
            release_workflow.write_text('name: Unsigned release\n')
        self.git('add', '.')
        self.git('commit', '-qm', 'change')

    def check(self):
        with mock.patch.object(scope, 'MOLLY', self.base):
            return scope.check_scope(self.repo, 'pipeline', self.base)

    def test_main_integration_lower_is_pinned_to_exact_commit(self):
        with mock.patch.object(scope, 'MAIN_INTEGRATION_LOWER', self.base):
            scope.check_trusted_integration_lower(self.repo, self.base)
            self.commit('unrelated.txt', 'different')
            with self.assertRaisesRegex(ValueError, 'unexpected trusted release-base'):
                scope.check_trusted_integration_lower(self.repo, 'HEAD')

    def test_main_forks_scope_cannot_admit_local_evidence_changes(self):
        for path in ('patches/ports/signal-8.33.2/report-evidence/receipts.md',
                     'patches/ports/signal-8.33.2/report-metadata.json',
                     'patches/ports/signal-8.33.2/report-evidence/locale-resource-probe.py'):
            self.assertFalse(scope.allowed('forks', path), path)

    def test_main_integration_validates_clean_tree_without_lower_ancestry(self):
        self.git('checkout', '-q', '--orphan', 'clean-main')
        self.git('rm', '-q', '-rf', '.')
        (self.repo / 'README.md').write_text('main readme')
        manifest = self.repo / 'tools/patches/main-clean-entries.json'
        manifest.parent.mkdir(parents=True, exist_ok=True)
        manifest.write_text('{}\n')
        self.git('add', '.')
        self.git('commit', '-qm', 'clean main')
        main = self.git('rev-parse', 'HEAD').strip()
        entry = self.git('ls-tree', main, '--', 'README.md').strip().split('\t')[0]
        self.commit('patches/forks/signal-foss/collie-branding/change', 'brand')
        head = self.git('rev-parse', 'HEAD').strip()
        with mock.patch.object(scope, 'MAIN_CLEAN_ENTRIES', {'README.md': entry}), \
             mock.patch.object(scope, 'MAIN_SCOPE_FILES', {'.github/workflows/molly-patch-release.yml',
                                                          '.github/workflows/molly-unsigned-release.yml',
                                                          'app.txt'}):
            scope.check_main_integration(self.repo, main, head, self.base)
            self.commit('README.md', 'changed readme')
            with self.assertRaisesRegex(ValueError, 'main projection changed'):
                scope.check_main_integration(self.repo, main, 'HEAD', self.base)
            self.git('reset', '--hard', '-q', head)
            self.commit('pkcs11.config', 'unexpected')
            with self.assertRaisesRegex(ValueError, 'main projection changed'):
                scope.check_main_integration(self.repo, main, 'HEAD', self.base)
            with self.assertRaisesRegex(ValueError, 'unexpected change to release base'):
                scope.check_main_integration(self.repo, head, head, self.base)

    def test_clean_projection_rejects_manifest_and_app_tampering(self):
        self.commit('tools/patches/main-clean-entries.json', '{"app.txt":"fake"}')
        self.commit('app.txt', 'malicious')
        with self.assertRaisesRegex(ValueError, 'audited integration manifest differs'):
            scope.check_clean_projection(self.repo, 'HEAD', self.base)

    def test_clean_projection_rejects_changed_and_extra_files(self):
        self.commit('README.md', 'audited')
        head = self.git('rev-parse', 'HEAD').strip()
        entry = self.git('ls-tree', head, '--', 'README.md').strip().split('\t')[0]
        scope_files = {'.github/workflows/molly-patch-release.yml', '.github/workflows/molly-unsigned-release.yml'}
        with mock.patch.object(scope, 'MAIN_CLEAN_ENTRIES', {'README.md': entry}), \
             mock.patch.object(scope, 'MAIN_SCOPE_FILES', scope_files):
            scope.check_clean_projection(self.repo, head, self.base)
            self.commit('README.md', 'tampered')
            with self.assertRaisesRegex(ValueError, 'audited integration entry differs'):
                scope.check_clean_projection(self.repo, 'HEAD', self.base)
            self.git('reset', '--hard', '-q', head)
            self.commit('tools/patches/hidden-local.py', 'unexpected')
            with self.assertRaisesRegex(ValueError, 'unexpected change to release base'):
                scope.check_clean_projection(self.repo, 'HEAD', self.base)

    def test_main_docs_scope_is_narrow_and_checks_ancestry(self):
        self.commit('README.md', 'initial documentation')
        base = self.git('rev-parse', 'HEAD').strip()
        self.commit('README.md', 'safe documentation')
        self.assertEqual(scope.check_scope(self.repo, 'main-docs', base)['scope'], 'main-docs')
        self.commit('app.txt', 'unexpected production change')
        with self.assertRaisesRegex(ValueError, 'main documentation scope excludes'):
            scope.check_scope(self.repo, 'main-docs', base)

    def test_forks_allows_only_explicit_identity_tool_changes(self):
        for path in ('docs/PATCH-PIPELINE.md', 'tools/patches/build.py',
                     'tools/patches/release_profiles.py', 'tools/patches/release_artifacts.py',
                     'tools/patches/test_build.py', 'tools/patches/test_fork_profiles.py',
                     'tools/patches/test_release_artifacts.py'):
            self.assertTrue(scope.allowed('forks', path), path)
        for path in ('tools/patches/scope.py', 'tools/patches/test_stack_scope.py',
                     'tools/patches/release_sign.py', 'tools/patches/other.py',
                     'docs/OTHER.md', 'tools/patches/build.py.extra'):
            self.assertFalse(scope.allowed('forks', path), path)

    def test_release_profiles_allow_only_reviewed_changes(self):
        names = ('molly-minimal', 'molly-continued', 'molly-foss-continued', 'collie', 'collie-foss')
        reviewed = {'fork_source': {'url': 'source', 'head': 'a' * 40},
                    'profiles': {name: {'target_tree': 'b' * 40, 'firebase_free': False} for name in names}}
        current = copy.deepcopy(reviewed)
        for name in ('molly-continued', 'molly-foss-continued'):
            current['profiles'][name]['icon_overlay'] = scope.ICON_OVERLAY
        for name in names[1:]:
            current['profiles'][name]['target_tree'] = 'c' * 40
        scope.check_release_profiles(reviewed, current)
        for change in ('minimal', 'field', 'source', 'extra', 'overlay', 'target'):
            candidate = copy.deepcopy(current)
            if change == 'minimal': candidate['profiles']['molly-minimal']['target_tree'] = 'c' * 40
            if change == 'field': candidate['profiles']['collie']['firebase_free'] = True
            if change == 'source': candidate['fork_source']['head'] = 'd' * 40
            if change == 'extra': candidate['profiles']['extra'] = {}
            if change == 'overlay': candidate['profiles']['molly-continued']['icon_overlay'] = 'wrong'
            if change == 'target': candidate['profiles']['collie']['target_tree'] = 'invalid'
            with self.subTest(change=change), self.assertRaises(ValueError):
                scope.check_release_profiles(reviewed, candidate)

    def test_locale_evidence_allowlist_does_not_admit_other_files(self):
        prefix = 'patches/ports/signal-8.33.2/report-evidence/'
        for scope_name in ('release', 'followups'):
            for name in ('locale-revert-cohort.tsv', 'locale-cohort-two-resource.log'):
                self.assertTrue(scope.allowed(scope_name, prefix + name))
            for name in ('locale-extra.py', '../initial/hidden.patch', 'locale-revert-cohort.tsv.bak'):
                self.assertFalse(scope.allowed(scope_name, prefix + name))

    def test_pipeline_accepts_tooling_and_rejects_app_changes(self):
        self.commit('tools/patches/example.py', 'print(1)\n')
        self.assertFalse(self.check()['profile_present'])
        self.commit('app.txt', 'unexpected\n')
        with self.assertRaisesRegex(ValueError, 'outside scope'):
            self.check()

    def test_pipeline_rejects_unapproved_workflow(self):
        self.commit('.github/workflows/other.yml', 'name: other\n')
        with self.assertRaisesRegex(ValueError, 'outside scope'):
            self.check()

    def test_base_must_be_ancestor(self):
        with mock.patch.object(scope, 'MOLLY', self.base):
            with self.assertRaisesRegex(ValueError, 'base is not an ancestor'):
                scope.check_scope(self.repo, 'pipeline', 'missing-base')

    def test_switch_accepts_unrelated_signal_tree_but_rejects_wrong_base_and_mutation(self):
        import json
        self.commit('tools/patches/tool.py', 'tool\n')
        lower = self.git('rev-parse', 'HEAD').strip()
        (self.repo / 'app.txt').write_text('signal version\n')
        self.git('add', 'app.txt')
        signal_tree = self.git('write-tree').strip()
        signal = subprocess.check_output(['git', '-C', str(self.repo), 'commit-tree', signal_tree], input=b'Signal\n').decode().strip()
        forest = self.repo / 'patches/molly/fixture'
        forest.parent.mkdir(parents=True)
        forest.write_text('immutable fixture\n')
        self.git('add', 'patches/molly')
        forest_tree = self.git('write-tree').strip()
        forest_id = self.git('rev-parse', forest_tree + ':patches/molly').strip()
        config = {'signal': {'commit': signal}, 'molly': {'target': self.base, 'tree': '3cd0bb01cf840e4b702187085432020d163d80ce'}, 'port': None, 'native': None}
        (self.repo / 'molly-build.json').write_text(json.dumps(config))
        self.git('add', '.')
        self.git('commit', '-qm', 'switch')
        with mock.patch.object(scope, 'MOLLY', self.base), mock.patch.object(scope, 'OLD', signal), mock.patch.object(scope, 'FOREST', forest_id):
            self.assertTrue(scope.check_scope(self.repo, 'switch', lower)['profile_present'])
            switch_head = self.git('rev-parse', 'HEAD').strip()
            with self.assertRaisesRegex(ValueError, 'lower base'):
                scope.check_scope(self.repo, 'switch', 'HEAD')
            with self.assertRaisesRegex(ValueError, 'outside scope'):
                scope.check_scope(self.repo, 'switch', self.base)
            with self.assertRaisesRegex(ValueError, 'base is not an ancestor'):
                scope.check_scope(self.repo, 'switch', signal)
            self.commit('app.txt', 'unexpected source mutation\n')
            with self.assertRaisesRegex(ValueError, 'source projection differs'):
                scope.check_scope(self.repo, 'switch', lower)

            self.git('checkout', '-q', '--detach', lower)
            self.commit('patches/ports/hidden', 'unreviewed port\n')
            hidden_base = self.git('rev-parse', 'HEAD').strip()
            self.git('checkout', switch_head, '--', 'app.txt', 'molly-build.json', 'patches/molly')
            self.git('add', '.')
            self.git('commit', '-qm', 'switch with hidden base port')
            with self.assertRaisesRegex(ValueError, 'outside scope'):
                scope.check_scope(self.repo, 'switch', hidden_base)
            self.git('checkout', '-q', '--detach', switch_head)
            self.commit('tools/patches/hidden.py', 'hidden tool change\n')
            hidden_tool_base = self.git('rev-parse', 'HEAD').strip()
            config['port'] = {'mechanical_tree': 'unused'}
            (self.repo / 'molly-build.json').write_text(json.dumps(config))
            port_series = self.repo / 'patches/ports/signal-8.33.2/series'
            port_series.parent.mkdir(parents=True)
            port_series.write_text('initial\n')
            self.git('add', '.')
            self.git('commit', '-qm', 'release')
            with mock.patch.object(scope, 'NEW', signal):
                with self.assertRaisesRegex(ValueError, 'no validated preceding stack phase'):
                    scope.check_scope(self.repo, 'release', hidden_tool_base)

    def test_additions_accepts_only_new_layer_and_shared_metadata(self):
        prefix = 'patches/ports/signal-8.33.2/'
        for layer in scope.ADDITIONS:
            for name in scope.addition_files(layer):
                self.assertTrue(scope.allowed('additions', prefix + 'followups/' + layer + '/' + name))
        for name in ('series', 'provenance.json', 'report-metadata.json', 'report-evidence/receipts.md'):
            self.assertTrue(scope.allowed('additions', prefix + name))
        for path in ('app/src/main/AndroidManifest.xml', 'patches/molly/series',
                     prefix + 'followups/registration-network-settings/other.patch',
                     prefix + 'followups/donation-completion-copy/other.patch',
                     prefix + 'followups/unknown/series',
                     prefix + 'followups/donation-completion/series',
                     prefix + 'initial/integration/series', 'tools/patches/scope.py'):
            self.assertFalse(scope.allowed('additions', path), path)

    def test_additions_requires_existing_followups_and_a_new_layer(self):
        import json
        self.commit('tools/patches/tool.py', 'tool\n')
        signal = self.base
        forest = self.repo / 'patches/molly/fixture'
        forest.parent.mkdir(parents=True)
        forest.write_text('immutable fixture\n')
        self.git('add', 'patches/molly')
        forest_tree = self.git('write-tree').strip()
        forest_id = self.git('rev-parse', forest_tree + ':patches/molly').strip()
        config = {'signal': {'commit': signal}, 'molly': {'target': self.base, 'tree': '3cd0bb01cf840e4b702187085432020d163d80ce'}, 'port': None, 'native': None}
        (self.repo / 'molly-build.json').write_text(json.dumps(config))
        self.git('add', '.')
        self.git('commit', '-qm', 'switch')
        config['port'] = {'mechanical_tree': 'unused'}
        self.commit('molly-build.json', json.dumps(config))
        release = self.git('rev-parse', 'HEAD').strip()
        prefix = 'patches/ports/signal-8.33.2/followups/'
        for name in sorted(scope.BEHAVIOR):
            self.commit(prefix + name + '/series', 'fixture\n')
        followups = self.git('rev-parse', 'HEAD').strip()
        with mock.patch.object(scope, 'MOLLY', self.base), mock.patch.object(scope, 'OLD', signal), mock.patch.object(scope, 'NEW', signal), mock.patch.object(scope, 'FOREST', forest_id):
            with self.assertRaisesRegex(ValueError, 'additions phase is incomplete'):
                scope.check_scope(self.repo, 'additions', followups)
            self.commit(prefix + 'registration-network-settings/series', 'fixture\n')
            with self.assertRaisesRegex(ValueError, 'additions phase is incomplete'):
                scope.check_scope(self.repo, 'additions', followups)
            for layer in sorted(scope.ADDITIONS):
                for name in scope.addition_files(layer):
                    if layer != 'registration-network-settings' or name != 'series':
                        self.commit(prefix + layer + '/' + name, 'fixture\n')
            self.assertTrue(scope.check_scope(self.repo, 'additions', followups)['port_present'])
            additions = self.git('rev-parse', 'HEAD').strip()
            with self.assertRaisesRegex(ValueError, 'features phase is incomplete'):
                scope.check_scope(self.repo, 'features', additions)
            for layer in sorted(scope.FEATURES):
                for name in scope.addition_files(layer):
                    self.commit(prefix + layer + '/' + name, 'fixture\n')
            with mock.patch.object(scope, 'check_feature_chain'):
                self.assertTrue(scope.check_scope(self.repo, 'features', additions)['port_present'])
            with self.assertRaisesRegex(ValueError, 'already contains'):
                scope.check_scope(self.repo, 'features', 'HEAD')
            with self.assertRaisesRegex(ValueError, 'outside scope'):
                scope.check_scope(self.repo, 'features', followups)
            self.commit(prefix + 'initial/unrelated/series', 'fixture\n')
            with self.assertRaisesRegex(ValueError, 'outside scope'):
                scope.check_scope(self.repo, 'features', additions)
            self.git('reset', '--hard', '-q', additions)
            with self.assertRaisesRegex(ValueError, 'outside scope'):
                scope.check_scope(self.repo, 'additions', release)
            with self.assertRaisesRegex(ValueError, 'already contains'):
                scope.check_scope(self.repo, 'additions', 'HEAD')
            self.commit(prefix + 'donation-completion/series', 'modified\n')
            with self.assertRaisesRegex(ValueError, 'outside scope'):
                scope.check_scope(self.repo, 'additions', followups)
            self.git('checkout', '-q', '--detach', release)
            for layer in sorted(scope.ADDITIONS):
                for name in scope.addition_files(layer):
                    self.commit(prefix + layer + '/' + name, 'fixture\n')
            with self.assertRaisesRegex(ValueError, 'no validated preceding stack phase'):
                scope.check_scope(self.repo, 'additions', release)

    def test_features_chain_rejects_unused_reordered_and_changed_metadata(self):
        paths = ['followups/' + name + '/0001-' + name + '.patch' for name in scope.FEATURES]
        old = {'id': 'followups/old', 'source_commit': 'old-commit', 'after_tree': 'old-tree'}
        first = {'id': 'followups/' + scope.FEATURES[0], 'patches': [paths[0]],
                 'source_bundle': 'followups/' + scope.FEATURES[0] + '/source-checkpoint.bundle',
                 'source_bundle_prerequisite': 'old-commit', 'before_tree': 'old-tree',
                 'source_commit': 'first-commit', 'after_tree': 'first-tree'}
        second = {'id': 'followups/' + scope.FEATURES[1], 'patches': [paths[1]],
                  'source_bundle': 'followups/' + scope.FEATURES[1] + '/source-checkpoint.bundle',
                  'source_bundle_prerequisite': 'first-commit', 'before_tree': 'first-tree',
                  'source_commit': 'second-commit', 'after_tree': 'second-tree'}
        base = {'layers': [old], 'target_tree': 'old-tree', 'mailbox_sha256': {'old': 'hash'}}
        head = {'layers': [old, first, second], 'target_tree': 'second-tree',
                'mailbox_sha256': {'old': 'hash', paths[0]: 'one', paths[1]: 'two'}}
        profile = {'target_tree': 'second-tree'}
        series = ['old', *paths]
        def check(current_series, current_head, current_profile):
            def read(repo, command, spec):
                ref, path = spec.split(':', 1)
                if path.endswith('/series'):
                    return ('\n'.join(['old'] if ref == 'base' else current_series) + '\n').encode()
                value = (base if ref == 'base' else current_head) if path.endswith('provenance.json') else ({'target_tree': 'old-tree'} if ref == 'base' else current_profile)
                return json.dumps(value).encode()
            with mock.patch.object(scope, 'git', side_effect=read):
                scope.check_feature_chain(self.repo, 'base', 'head')
        check(series, head, profile)
        for bad_series in (['old'], ['old', paths[0]], ['old', *reversed(paths)], [*paths, 'old']):
            with self.subTest(series=bad_series), self.assertRaises(ValueError):
                check(bad_series, head, profile)
        for change in ('prior', 'omit', 'reorder', 'prerequisite', 'final', 'hash'):
            bad = copy.deepcopy(head)
            if change == 'prior': bad['layers'][0]['after_tree'] = 'changed'
            if change == 'omit': bad['layers'].pop()
            if change == 'reorder': bad['layers'][1:] = reversed(bad['layers'][1:])
            if change == 'prerequisite': bad['layers'][2]['source_bundle_prerequisite'] = 'old-commit'
            if change == 'final': bad['target_tree'] = 'old-tree'
            if change == 'hash': bad['mailbox_sha256']['old'] = 'changed'
            with self.subTest(change=change), self.assertRaises(ValueError):
                check(series, bad, profile)
        with self.assertRaises(ValueError):
            check(series, head, {'target_tree': 'old-tree'})

    def test_features_allow_only_complete_named_layer_paths_and_metadata(self):
        prefix = 'patches/ports/signal-8.33.2/'
        for layer in scope.FEATURES:
            for name in scope.addition_files(layer):
                self.assertTrue(scope.allowed('features', prefix + 'followups/' + layer + '/' + name))
        for path in ('series', 'provenance.json', 'report-metadata.json', 'report-evidence/receipts.md'):
            self.assertTrue(scope.allowed('features', prefix + path))
        for path in ('patches/molly/series', prefix + 'initial/integration/series',
                     prefix + 'followups/retention-controls/other.patch',
                     prefix + 'followups/registration-network-settings/series', 'tools/patches/scope.py'):
            self.assertFalse(scope.allowed('features', path), path)

    def test_followups_allow_only_named_adjacent_report_metadata(self):
        self.assertTrue(scope.allowed('followups', 'patches/ports/signal-8.33.2/followups/android-instrumentation-compatibility/series'))
        self.assertTrue(scope.allowed('followups', 'patches/ports/signal-8.33.2/report-metadata.json'))
        self.assertFalse(scope.allowed('followups', 'patches/ports/signal-8.33.2/unexpected.json'))

    def test_followups_allow_pipeline_pin_document_but_not_other_docs_or_tools(self):
        self.assertTrue(scope.allowed('followups', 'docs/PATCH-PIPELINE.md'))
        self.assertFalse(scope.allowed('followups', 'docs/OTHER.md'))
        self.assertFalse(scope.allowed('followups', 'docs/PATCH-PIPELINE.md.extra'))
        self.assertFalse(scope.allowed('followups', 'tools/patches/scope.py'))


class BundleScopeTests(unittest.TestCase):
    def test_converted_release_artifacts_are_exactly_scoped(self):
        prefix = 'patches/ports/signal-8.33.2/'
        accepted = (
            'initial/app-updates/0001-resolve-app-updates-converted.patch',
            'initial/app-updates/delete-files.json',
            'initial/build-and-release/generate-translations.json',
            'followups/integration-and-regression-tests/delete-files.json',
            'followups/netcipher-sdk-dsl/0001-netcipher-sdk-dsl-converted.patch',
        )
        for path in accepted:
            self.assertTrue(scope.allowed('release', prefix + path), path)
            for phase in ('pipeline', 'switch', 'followups', 'additions'):
                self.assertFalse(scope.allowed(phase, prefix + path), (phase, path))
        for path in ('initial/app-updates/0002-resolve-app-updates-converted.patch',
                     'initial/app-updates/other.json',
                     'followups/netcipher-sdk-dsl/other-converted.patch',
                     'followups/donation-completion/0001-donation-completion.patch',
                     'followups/registration-network-settings/series',
                     'followups/donation-completion-copy/series'):
            self.assertFalse(scope.allowed('release', prefix + path), path)
        self.assertFalse(scope.allowed('release',
            'patches/ports/signal-8.19.2/' + accepted[0]))
        for layer in scope.BEHAVIOR | scope.ADDITIONS:
            self.assertFalse(scope.allowed('release', prefix + 'followups/' + layer + '/series'))

    def test_current_locale_evidence_names_are_bounded(self):
        prefix = 'patches/ports/signal-8.33.2/report-evidence/'
        for phase in ('release', 'followups', 'additions'):
            for name in ('translation-task-witness.tsv', 'native-translation-byte-witness.tsv',
                         'locale-format-011d0759474555f6b943.patch',
                         'post-format-README.md', 'post-format-classify.py',
                         'post-format-cohort.patch', 'post-format-probe.py',
                         'post-format-results.tsv', 'post-format-translation-xml-proof.py'):
                self.assertTrue(scope.allowed(phase, prefix + name), name)
            for name in ('translation-task-witness.tsv.bak', '../translation-task-witness.tsv',
                         'native-translation-byte-witness.tsv.bak',
                         '../native-translation-byte-witness.tsv',
                         'locale-format-011d0759474555f6b94.patch',
                         'locale-format-011d0759474555f6b9430.patch',
                         'locale-format-011d0759474555f6b94G.patch',
                         'locale-format-011d0759474555f6b943.patch.bak',
                         'post-format-unrelated.py', '../post-format-results.tsv'):
                self.assertFalse(scope.allowed(phase, prefix + name), name)
        self.assertFalse(scope.report_evidence(
            'patches/ports/signal-8.19.2/report-evidence/native-translation-byte-witness.tsv'))

    def test_release_allows_only_pipeline_document(self):
        self.assertTrue(scope.allowed('release', 'docs/PATCH-PIPELINE.md'))
        self.assertFalse(scope.metadata('docs/PATCH-PIPELINE.md.extra'))
        self.assertFalse(scope.metadata('docs/OTHER.md'))

    def test_only_exact_release_bundle_path_is_allowed(self):
        self.assertTrue(scope.allowed('release', 'patches/ports/signal-8.33.2/source-checkpoint.bundle'))
        self.assertFalse(scope.allowed('release', 'patches/ports/signal-8.33.2/unrelated.bundle'))


class ReportEvidenceScopeTests(unittest.TestCase):
    def test_hunk_evidence_name_requires_exact_lowercase_id(self):
        prefix = 'patches/ports/signal-8.33.2/report-evidence/'
        for phase in ('release', 'followups'):
            self.assertTrue(scope.allowed(phase, prefix + 'hunk-0123456789abcdefabcd.patch'))
            for name in ('hunk-0123456789abcdefabc.patch',
                         'hunk-0123456789abcdefabcde.patch',
                         'hunk-0123456789abcdefabcD.patch',
                         'hunk-0123456789abcdefabcd.patch.bak',
                         '../hunk-0123456789abcdefabcd.patch'):
                self.assertFalse(scope.allowed(phase, prefix + name), name)

    def test_only_bounded_report_evidence_paths_are_allowed(self):
        prefix = 'patches/ports/signal-8.33.2/report-evidence/'
        for phase in ('release', 'followups'):
            for name in ('receipts.md', 'cohort-receipts.md', 'fmt-01.patch', 'fmt-16.patch', 'lex-01.patch', 'lex-74.patch', 'locale-remaining-cohort.tsv', 'locale-remaining-contracts.py'):
                self.assertTrue(scope.allowed(phase, prefix + name))
            for name in ('other.patch', 'fmt-17.patch', 'lex-75.patch', '../series', 'receipts.md.extra', 'cohort-receipts.md.extra', 'cohort-receipt.md', 'other-cohort-receipts.md', 'locale-remaining-unrelated.py'):
                self.assertFalse(scope.allowed(phase, prefix + name))
