"""Independent fork checkpoints and strict product compositions."""
import copy
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import forest
import fork_profiles
import release
import release_profiles


class ForkProfilesTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='fork-profile-')
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name).resolve()
        self.git('init', '-q')
        self.git('config', 'user.name', 'Fork Author')
        self.git('config', 'user.email', 'author@example.invalid')
        (self.repo / 'app').write_text('base\n')
        self.git('add', 'app')
        self.git('commit', '-qm', 'base')
        base = self.git('rev-parse', 'HEAD')
        base_tree = self.git('rev-parse', 'HEAD^{tree}')
        (self.repo / 'app').write_text('original retention\n')
        self.git('add', 'app')
        self.git('commit', '-qm', 'original retention')
        upstream_retention = self.git('rev-parse', 'HEAD')
        (self.repo / 'app').write_text('original Firebase\n')
        self.git('add', 'app')
        self.git('commit', '-qm', 'original Firebase')
        upstream_head = self.git('rev-parse', 'HEAD')
        upstream_ref = 'refs/heads/upstream-fork/signal-foss-8.33.2.0-FOSS'
        self.git('update-ref', upstream_ref, upstream_head)
        self.git('checkout', '--detach', '-q', base)
        self.root = self.repo / release_profiles.FORK_ROOT
        self.root.mkdir(parents=True)
        upstream_bundle = self.root / 'upstream-source.bundle'
        self.git('bundle', 'create', str(upstream_bundle), upstream_ref, '^' + base)
        previous, before = base, base_tree
        layers = []
        for label in ('firebase-free-build', 'retention-controls'):
            directory = self.root / label
            directory.mkdir()
            (self.repo / 'app').write_text(label + '\n')
            self.git('add', 'app')
            self.git('commit', '-qm', label)
            commit = self.git('rev-parse', 'HEAD')
            tree = self.git('rev-parse', 'HEAD^{tree}')
            raw = subprocess.check_output(['git', '-C', str(self.repo), 'format-patch', '--stdout', '-1', commit])
            raw = raw.replace(b'\n---\n', b'\nSource-Commit: ' + commit.encode() + b'\n\n---\n', 1)
            mailbox = directory / 'change.patch'
            mailbox.write_bytes(raw)
            (directory / 'series').write_text('change.patch\n')
            ref = 'refs/heads/source-checkpoint/' + label
            self.git('update-ref', ref, commit)
            bundle = directory / 'checkpoint.bundle'
            self.git('bundle', 'create', str(bundle), ref, '^' + previous)
            layers.append({'id': label, 'series': label + '/series', 'mailbox': label + '/change.patch',
                'mailbox_sha256': hashlib.sha256(raw).hexdigest(), 'mailbox_bytes': len(raw),
                'source_commit': commit, 'before_tree': before, 'after_tree': tree,
                'source_bundle': label + '/checkpoint.bundle',
                'source_bundle_sha256': hashlib.sha256(bundle.read_bytes()).hexdigest(),
                'source_bundle_bytes': bundle.stat().st_size, 'source_bundle_prerequisite': previous,
                'source_bundle_ref': ref, 'upstream_commit': upstream_head if label == 'firebase-free-build' else upstream_retention,
                'upstream_author': 'Fork Author <author@example.invalid>'})
            previous, before = commit, tree
        self.data = {'schema': 1, 'source_url': 'https://github.com/tw-hx/Signal-Android',
                     'source_head': upstream_head, 'base_commit': base,
                     'base_tree': base_tree, 'layers': layers,
                     'upstream_bundle': upstream_bundle.name,
                     'upstream_bundle_sha256': hashlib.sha256(upstream_bundle.read_bytes()).hexdigest(),
                     'upstream_bundle_bytes': upstream_bundle.stat().st_size,
                     'upstream_bundle_prerequisite': base, 'upstream_bundle_ref': upstream_ref}
        self.selection = {'base_checkpoint': base, 'base_tree': base_tree,
                          'target_tree': layers[-1]['after_tree'],
                          'signal_commit': base, 'fork_source': {'url': self.data['source_url'], 'head': upstream_head},
                          'fork_series': [release_profiles.FIREBASE, release_profiles.RETENTION]}
        self.save()

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.repo), *args], text=True, stderr=subprocess.PIPE).strip()

    def save(self):
        (self.root / 'provenance.json').write_text(json.dumps(self.data))

    def test_real_bundles_mailboxes_and_each_replayed_tree(self):
        layers = fork_profiles.fork_layers(self.repo, self.selection)
        output = self.repo.parent / (self.repo.name + '-output')
        self.addCleanup(lambda: subprocess.run(['git', '-C', str(self.repo), 'worktree', 'remove', '--force', str(output)], capture_output=True))
        for i, (mailbox, metadata, expected) in enumerate(layers):
            tree = self.selection['base_tree'] if i == 0 else layers[i-1][2]
            release.apply_resolutions(self.repo, output, tree, [(mailbox, metadata)], existing=i > 0)
            actual = subprocess.check_output(['git', '-C', str(output), 'rev-parse', 'HEAD^{tree}'], text=True).strip()
            self.assertEqual(actual, expected)
        self.assertEqual(actual, self.selection['target_tree'])
        single = dict(self.selection, fork_series=[release_profiles.FIREBASE], target_tree=self.data['layers'][0]['after_tree'])
        self.assertEqual(len(fork_profiles.fork_layers(self.repo, single)), 1)

    def test_rejects_wrong_pins_order_bytes_and_prerequisites(self):
        original = copy.deepcopy(self.data)
        cases = [
            lambda d: d['layers'].reverse(),
            lambda d: d.__setitem__('upstream_bundle_sha256', '0' * 64),
            lambda d: d.__setitem__('upstream_bundle_prerequisite', '0' * 40),
            lambda d: d['layers'][1].__setitem__('upstream_commit', '0' * 40),
            lambda d: d['layers'][0].__setitem__('before_tree', '0' * 40),
            lambda d: d['layers'][0].__setitem__('source_bundle_prerequisite', '0' * 40),
            lambda d: d['layers'][0].__setitem__('mailbox_sha256', '0' * 64),
            lambda d: d['layers'][0].__setitem__('source_bundle_sha256', '0' * 64),
            lambda d: d['layers'][0].__setitem__('source_commit', '0' * 40),
            lambda d: d['layers'][0].__setitem__('upstream_author', 'wrong'),
            lambda d: d['layers'][0].__setitem__('mailbox', '../escape.patch'),
        ]
        for mutate in cases:
            with self.subTest(mutate=mutate):
                self.data = copy.deepcopy(original)
                mutate(self.data)
                self.save()
                with self.assertRaises((ValueError, RuntimeError, OSError)):
                    fork_profiles.fork_layers(self.repo, self.selection)
        self.data = original
        self.save()
        bundle = self.root / self.data['upstream_bundle']
        bundle_bytes = bundle.read_bytes()
        bundle.unlink()
        with self.assertRaises((OSError, ValueError)):
            fork_profiles.fork_layers(self.repo, self.selection)
        bundle.write_bytes(bundle_bytes)
        with self.assertRaisesRegex(forest.ForestError, 'final source tree'):

            fork_profiles.fork_layers(self.repo, dict(self.selection, target_tree='0' * 40))
        with self.assertRaisesRegex(forest.ForestError, 'base checkpoint'):
            fork_profiles.fork_layers(self.repo, dict(self.selection, base_checkpoint='0' * 40))

    def test_product_policy_rejects_missing_reordered_and_wrong_flag(self):
        for name, (_, expected, free) in release_profiles.POLICY.items():
            entry = {'base_series': release_profiles.PORT_SERIES, 'base_checkpoint': 'a'*40,
                     'base_tree': 'b'*40, 'fork_series': list(expected), 'target_tree': 'c'*40,
                     'firebase_free': free}
            config = {'profiles': {name: entry}, 'port': {}, 'android': {'tests': [':app:test']},
                      'signal': {'commit': 'd'*40},
                      'fork_source': {'url': 'https://github.com/tw-hx/Signal-Android', 'head': 'e'*40}}
            config['port'] = {'series': release_profiles.PORT_SERIES}
            with patch.object(release_profiles, 'derived_tests', return_value=[':app:test']) as derived:
                _, selected = release_profiles.select(config, name, self.repo)
            derived.assert_called_once_with(self.repo, [':app:test'], expected)
            self.assertEqual(selected['fork_series'], expected)
            entry['tests'] = [':app:test']
            with self.assertRaisesRegex(ValueError, 'exactly'):
                release_profiles.select(config, name, self.repo)
            del entry['tests']
            entry['firebase_free'] = not free
            with self.assertRaisesRegex(ValueError, 'firebase_free'):
                release_profiles.select(config, name)
            entry['firebase_free'] = free
            entry['fork_series'] = list(reversed(expected)) if len(expected) == 2 else ['wrong']
            with self.assertRaisesRegex(ValueError, 'fork_series'):
                release_profiles.select(config, name)

    def test_profile_tests_derive_from_fork_layer_test_sources(self):
        root = self.repo / release_profiles.FORK_ROOT
        (root / 'one').mkdir(parents=True)
        (root / 'two').mkdir(parents=True)
        def diff(path, deleted=False):
            mode = 'deleted file mode 100644\n' if deleted else 'new file mode 100644\n'
            return f'diff --git a/{path} b/{path}\n{mode}index 0000000..1111111\n--- a/{path}\n+++ b/{path}\n@@ -0,0 +1 @@\n+x\n'
        (root / 'one/0001.patch').write_text('From x\n\n---\n' + diff('app/src/main/java/A.kt')
            + diff('app/src/test/java/org/x/NewTest.kt') + diff('app/src/test/java/org/x/OldTest.kt', deleted=True)
            + diff('app/src/test/java/org/x/KeptTest.kt') + '-- \n2.0\n')
        (root / 'two/0001.patch').write_text(diff('feature/app-settings/src/test/java/org/s/ScreenTest.kt')
            + diff('lib/network/src/test/java/org/n/NetTest.kt') + diff('app/src/test/java/org/x/NewTest.kt'))
        (root / 'provenance.json').write_text(json.dumps({'layers': [
            {'series': 'one/series', 'mailbox': 'one/0001.patch'},
            {'series': 'two/series', 'mailbox': 'two/0001.patch'}]}))
        base = [':lib:network:test', ':app:testDebugUnitTest', '--tests', 'org.x.KeptTest',
                ':feature:app-settings:testDebugUnitTest', '--tests', 'org.s.Other']
        one, two = release_profiles.FORK_ROOT + '/one/series', release_profiles.FORK_ROOT + '/two/series'
        self.assertEqual(release_profiles.derived_tests(self.repo, base, []), base)
        self.assertEqual(release_profiles.derived_tests(self.repo, base, [one, two]),
                         [':lib:network:test', ':app:testDebugUnitTest', '--tests', 'org.x.KeptTest',
                          '--tests', 'org.x.NewTest', ':feature:app-settings:testDebugUnitTest',
                          '--tests', 'org.s.Other', '--tests', 'org.s.ScreenTest'])
        with self.assertRaisesRegex(ValueError, 'exactly one :feature:app-settings: test task'):
            release_profiles.derived_tests(self.repo, base[:4], [two])
        with self.assertRaisesRegex(ValueError, 'no single provenance layer'):
            release_profiles.derived_tests(self.repo, base, [release_profiles.FORK_ROOT + '/three/series'])
        (root / 'one/0001.patch').write_text(diff('app/src/androidTest/java/org/x/DeviceTest.kt'))
        with self.assertRaisesRegex(ValueError, 'no unit test task rule'):
            release_profiles.derived_tests(self.repo, base, [one])

    def test_port_prefix_requires_verified_order_and_exact_boundary(self):
        directory = self.repo / 'patches/ports/signal-8.33.2'
        directory.mkdir(parents=True)
        (directory / 'series').write_text('a.patch\nr.patch\nb.patch\n')
        layers = [{'id': 'followups/backup-notification-id-upgrade', 'source_commit': 'a'*40,
                   'after_tree': 'b'*40, 'patches': ['a.patch']},
                  {'id': 'followups/registration-network-settings', 'source_commit': 'e'*40,
                   'after_tree': 'f'*40, 'patches': ['r.patch']},
                  {'id': 'followups/donation-completion-copy', 'source_commit': 'c'*40,
                   'after_tree': 'd'*40, 'patches': ['b.patch']}]
        provenance = {'layers': layers, 'mailbox_sha256': {'a.patch': '1', 'r.patch': '3', 'b.patch': '2'}}
        (directory / 'provenance.json').write_text(json.dumps(provenance))
        records = [(directory / 'a.patch', {'sha256': '1'}), (directory / 'r.patch', {'sha256': '3'}),
                   (directory / 'b.patch', {'sha256': '2'})]
        selection = {'base_layer': layers[0]['id'], 'base_checkpoint': 'a'*40, 'base_tree': 'b'*40}
        with patch.object(fork_profiles.verify_source_bundles, 'verify') as verify:
            self.assertEqual(fork_profiles.port_prefix(self.repo, 'config.json', selection, records), records[:1])
            verify.assert_called_once_with(self.repo, 'config.json')
            with self.assertRaisesRegex(forest.ForestError, 'checkpoint or tree'):
                fork_profiles.port_prefix(self.repo, 'config.json', dict(selection, base_tree='d'*40), records)
            continued = {'base_layer': layers[2]['id'], 'base_checkpoint': 'c'*40, 'base_tree': 'd'*40}
            self.assertEqual(fork_profiles.port_prefix(self.repo, 'config.json', continued, records), records)
            missing = dict(provenance, layers=[layers[0], layers[2]])
            (directory / 'provenance.json').write_text(json.dumps(missing))
            (directory / 'series').write_text('a.patch\nb.patch\n')
            with self.assertRaisesRegex(forest.ForestError, 'missing, repeated, or misordered'):
                fork_profiles.port_prefix(self.repo, 'config.json', continued, [records[0], records[2]])
            for altered in ([layers[1], layers[0], layers[2]],
                            [layers[0], layers[1], layers[1], layers[2]]):
                (directory / 'provenance.json').write_text(json.dumps(dict(provenance, layers=altered)))
                flattened = [p for item in altered for p in item['patches']]
                if len(set(flattened)) != len(flattened):
                    continue  # series parser rejects duplicate mailbox entries before layer validation
                (directory / 'series').write_text('\n'.join(flattened) + '\n')
                by_path = {'a.patch': records[0], 'r.patch': records[1], 'b.patch': records[2]}
                with self.assertRaisesRegex(forest.ForestError, 'missing, repeated, or misordered'):
                    fork_profiles.port_prefix(self.repo, 'config.json', continued, [by_path[p] for p in flattened])
            (directory / 'provenance.json').write_text(json.dumps(provenance))
            (directory / 'series').write_text('b.patch\na.patch\nr.patch\n')
            with self.assertRaisesRegex(forest.ForestError, 'order'):
                fork_profiles.port_prefix(self.repo, 'config.json', selection, records)


if __name__ == '__main__':
    unittest.main()
