"""Exercise the current-main security boundary with real Git trees."""
import copy
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

import scope


class SecurityMaintenanceTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.repo = Path(temporary.name)
        self.git_env = dict(os.environ, GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL=os.devnull)
        self.git('init', '-q')
        self.git('config', 'user.name', 'Test')
        self.git('config', 'user.email', 'test@example.invalid')
        self.sources = sorted(scope.SECURITY_SOURCE_PATHS)
        self.config = {
            'signal': {'commit': 'a' * 40, 'version': '8.35.1'},
            'molly': {'target': 'b' * 40, 'tree': 'c' * 40},
            'native': 'tools/native/native-lock.json',
            'port': {'mechanical_tree': 'd' * 40, 'series': 'patches/ports/series'},
            'profiles': {'collie': {'target_tree': 'e' * 40}},
            'target_tree': 'f' * 40,
            'fork_source': {'head': '1' * 40, 'url': 'https://example.invalid/fork'},
        }
        files = {
            'app.txt': 'unchanged application source\n',
            'molly-build.json': json.dumps(self.config),
            'patches/molly/fixture': 'official historical forest\n',
            'patches/ports/fixture': 'reviewed port\n',
            'patches/forks/fixture': 'reviewed profiles\n',
            'tools/native/native-lock.json': '{"immutable": true}\n',
            'tools/patches/pins.py': '# executable pin tool\n',
            'tools/patches/scope.py': '# reviewed validator\n',
            'docs/PATCH-PIPELINE.md': 'reviewed pipeline\n',
            '.github/workflows/molly-patch-release.yml': 'name: Patch proof\n',
            '.github/workflows/molly-unsigned-release.yml': 'name: Unsigned release\n',
            '.github/workflows/build-artifacts.yml': 'name: Build artifacts\n',
            **{path: 'original viewer asset ' + str(index) + '\n'
               for index, path in enumerate(self.sources)},
        }
        for path, content in files.items():
            self.write(path, content)
        (self.repo / 'tools/patches/pins.py').chmod(0o700)
        self.anchor = self.commit()
        for name in ('SECURITY_MAINTENANCE_BASE', 'MAIN_INTEGRATION_LOWER'):
            patcher = mock.patch.object(scope, name, self.anchor)
            patcher.start()
            self.addCleanup(patcher.stop)
        records = []
        for index, path in enumerate(self.sources):
            before = self.git('rev-parse', self.anchor + ':' + path)
            self.write(path, 'reviewed viewer fix ' + str(index) + '\n')
            records.append({'path': path, 'mode': '100644', 'before_blob': before,
                            'after_blob': self.git('hash-object', path)})
        self.manifest = {
            'schema': 1, 'base_commit': self.anchor,
            'scope': 'tracked-spinner-debug-viewer', 'release_reconstruction': 'unchanged',
            'files': records,
        }
        raw = self.write_manifest(self.manifest)
        patcher = mock.patch.object(scope, 'SECURITY_SOURCE_OVERLAY_SHA256', hashlib.sha256(raw).hexdigest())
        patcher.start()
        self.addCleanup(patcher.stop)
        self.secured = self.commit()

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.repo), *args],
                                       env=self.git_env, stderr=subprocess.STDOUT, text=True).strip()

    def write(self, name, content):
        path = self.repo / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content if isinstance(content, bytes) else content.encode())

    def write_manifest(self, data):
        raw = (json.dumps(data, indent=2) + '\n').encode()
        self.write(scope.SECURITY_SOURCE_OVERLAY, raw)
        return raw

    def commit(self):
        self.git('add', '-A')
        self.git('commit', '-qm', 'fixture change')
        return self.git('rev-parse', 'HEAD')

    def reset(self):
        self.git('reset', '--hard', '-q', self.secured)

    def check(self, base=None, head='HEAD'):
        return scope.check_scope(self.repo, 'main-maintenance', base or self.anchor,
                                 head, integration_lower=self.anchor)

    def test_anchor_and_reviewed_overlay_report_source_only(self):
        expected = {'scope': 'main-maintenance', 'profile_present': True,
                    'port_present': True, 'source_only': True}
        self.assertEqual(self.check(), expected)
        self.assertEqual(self.check(base='HEAD'), expected)
        self.assertEqual(self.check(head=self.anchor), expected)

    def test_explicit_security_tools_and_existing_executable_edits_pass(self):
        for path in (
                '.github/workflows/codeql.yml', '.github/codeql/codeql-config.yml',
                '.github/codeql/reviewed-findings.json', '.github/dependabot.yml',
                '.github/scripts/screenshot_artifacts.py', '.github/scripts/test_screenshot_artifacts.py',
                'tools/codeql/alert_inventory.py', 'tools/codeql/check_sarif.py',
                'tools/codeql/test_alert_inventory.py', 'tools/codeql/test_check_sarif.py',
                'tools/codeql/test_spinner.cjs', 'tools/patches/test_security_scope.py',
                'tools/patches/test_generated_stages.py', 'tools/patches/test_build.py',
                'tools/patches/scope.py', 'tools/patches/pins.py',
                'docs/PATCH-PIPELINE.md', 'docs/security/CODEQL.md'):
            self.write(path, 'reviewed security maintenance\n')
        self.commit()
        self.assertTrue(self.check()['source_only'])
        self.assertTrue(self.git('ls-tree', 'HEAD', '--', 'tools/patches/pins.py').startswith('100755 blob '))

    def test_new_source_and_nearby_unlisted_paths_fail(self):
        for path in ('app.txt', 'lib/spinner/src/main/assets/extra.js',
                     'tools/codeql/unreviewed.py', 'tools/patches/unreviewed.py',
                     '.github/scripts/unreviewed.py', '.github/workflows/unreviewed.yml',
                     '.github/codeql/unreviewed.yml', 'docs/security/OTHER.md'):
            with self.subTest(path=path):
                self.reset()
                self.write(path, 'unreviewed\n')
                self.commit()
                with self.assertRaisesRegex(ValueError, 'excludes unreviewed change'):
                    self.check()
                with self.assertRaisesRegex(ValueError, 'excludes unreviewed change'):
                    self.check(base='HEAD')

    def test_prior_source_edit_cannot_hide_behind_latest_tool_diff(self):
        self.write('app.txt', 'previously landed unreviewed edit\n')
        bad_base = self.commit()
        self.write('tools/patches/scope.py', '# new tool maintenance\n')
        self.commit()
        with self.assertRaisesRegex(ValueError, 'excludes unreviewed change: app.txt'):
            self.check(base=bad_base)

    def test_complete_release_configuration_and_input_trees_are_immutable(self):
        for field in ('signal', 'molly', 'native', 'port', 'profiles', 'target_tree', 'fork_source'):
            with self.subTest(field=field):
                self.reset()
                config = copy.deepcopy(self.config)
                config[field] = {'changed': True}
                self.write('molly-build.json', json.dumps(config))
                self.commit()
                with self.assertRaisesRegex(ValueError, 'cannot change pinned release entry: molly-build.json'):
                    self.check()
        for path in ('patches/molly/fixture', 'patches/ports/fixture', 'patches/forks/fixture',
                     'patches/new-source.patch', 'tools/native/native-lock.json', 'tools/native/extra.py'):
            with self.subTest(path=path):
                self.reset()
                self.write(path, 'changed source input\n')
                self.commit()
                with self.assertRaisesRegex(ValueError, 'cannot change pinned release entry'):
                    self.check()

    def test_overlay_replacements_require_exact_blobs_and_regular_modes(self):
        for name in self.sources:
            for operation in ('content', 'delete', 'executable', 'symlink', 'rename', 'revert'):
                with self.subTest(path=name, operation=operation):
                    self.reset()
                    path = self.repo / name
                    if operation == 'content':
                        path.write_text('unreviewed replacement\n')
                    elif operation == 'delete':
                        path.unlink()
                    elif operation == 'executable':
                        path.chmod(0o700)
                    elif operation == 'symlink':
                        path.unlink()
                        path.symlink_to('unreviewed-target')
                    elif operation == 'rename':
                        self.git('mv', name, name + '.renamed')
                    else:
                        path.write_text(self.git('show', self.anchor + ':' + name) + '\n')
                    self.commit()
                    with self.assertRaisesRegex(ValueError, 'overlay replacement differs'):
                        self.check()

    def test_manifest_bytes_are_pinned_even_when_json_meaning_is_unchanged(self):
        self.write(scope.SECURITY_SOURCE_OVERLAY, json.dumps(self.manifest) + '\n')
        self.commit()
        with self.assertRaisesRegex(ValueError, 'manifest differs from pin'):
            self.check()

    def test_overlay_cannot_be_rolled_back_after_adoption(self):
        (self.repo / scope.SECURITY_SOURCE_OVERLAY).unlink()
        for name in self.sources:
            self.write(name, self.git('show', self.anchor + ':' + name) + '\n')
        self.commit()
        with self.assertRaisesRegex(ValueError, 'overlay cannot be removed'):
            self.check(base=self.secured)

    def test_source_changes_without_manifest_fail(self):
        (self.repo / scope.SECURITY_SOURCE_OVERLAY).unlink()
        self.commit()
        with self.assertRaisesRegex(ValueError, 'excludes unreviewed change'):
            self.check()

    def test_manifest_schema_and_preimages_fail_closed_even_with_recomputed_hash(self):
        cases = []
        for field, value in (('schema', True), ('schema', 2), ('base_commit', '0' * 40),
                             ('scope', 'release-layer'), ('release_reconstruction', 'changed'),
                             ('files', {}), ('unexpected', True)):
            changed = copy.deepcopy(self.manifest)
            changed[field] = value
            cases.append((field, changed, 'schema or boundary differs'))
        for field, value in (('path', '../app.txt'), ('path', None), ('mode', '100755'),
                             ('before_blob', '0' * 40), ('after_blob', 'not-an-object-id'),
                             ('unexpected', True)):
            changed = copy.deepcopy(self.manifest)
            changed['files'][0][field] = value
            cases.append((field, changed, 'preimage differs' if field == 'before_blob' else 'invalid or duplicate file'))
        changed = copy.deepcopy(self.manifest)
        changed['files'].append(copy.deepcopy(changed['files'][0]))
        cases.append(('duplicate path', changed, 'invalid or duplicate file'))
        changed = copy.deepcopy(self.manifest)
        changed['files'].pop()
        cases.append(('missing path', changed, 'exactly the reviewed files'))
        changed = copy.deepcopy(self.manifest)
        changed['files'].append({**changed['files'][0], 'path': 'app.txt'})
        cases.append(('extra source path', changed, 'invalid or duplicate file'))
        cases.append(('wrong JSON root', [], 'schema or boundary differs'))
        for label, manifest, message in cases:
            with self.subTest(case=label):
                self.reset()
                raw = self.write_manifest(manifest)
                self.commit()
                with mock.patch.object(scope, 'SECURITY_SOURCE_OVERLAY_SHA256', hashlib.sha256(raw).hexdigest()):
                    with self.assertRaisesRegex(ValueError, message):
                        self.check()

    def test_duplicate_keys_and_malformed_json_fail_even_with_recomputed_hash(self):
        raw = (json.dumps(self.manifest, indent=2) + '\n').encode()
        cases = (
            (raw.replace(b'"schema": 1,', b'"schema": 1, "schema": 1,', 1), 'duplicate JSON keys'),
            (raw.replace(b'"mode": "100644",', b'"mode": "100644", "mode": "100644",', 1), 'duplicate JSON keys'),
            (b'{', 'not valid JSON'),
            (b'\xff', 'not valid JSON'),
        )
        for content, message in cases:
            with self.subTest(content=content):
                self.reset()
                self.write(scope.SECURITY_SOURCE_OVERLAY, content)
                self.commit()
                with mock.patch.object(scope, 'SECURITY_SOURCE_OVERLAY_SHA256', hashlib.sha256(content).hexdigest()):
                    with self.assertRaisesRegex(ValueError, message):
                        self.check()

    def test_metadata_modes_deletions_and_symlinks_fail(self):
        for name in ('tools/patches/pins.py', 'tools/patches/scope.py',
                     'docs/PATCH-PIPELINE.md', scope.SECURITY_SOURCE_OVERLAY):
            for operation in ('mode', 'delete', 'symlink'):
                with self.subTest(path=name, operation=operation):
                    self.reset()
                    path = self.repo / name
                    if operation == 'mode':
                        path.chmod(0o600 if name.endswith('pins.py') else 0o700)
                    elif operation == 'delete':
                        path.unlink()
                    else:
                        path.unlink()
                        path.symlink_to('unreviewed-target')
                    self.commit()
                    with self.assertRaises(ValueError):
                        self.check()

    def test_newly_adopted_security_tools_cannot_disappear(self):
        name = '.github/workflows/codeql.yml'
        self.write(name, 'name: CodeQL\n')
        adopted = self.commit()
        (self.repo / name).unlink()
        self.commit()
        with self.assertRaisesRegex(ValueError, 'preserve regular file mode 100644'):
            self.check(base=adopted)

    def test_disabled_signal_workflows_and_required_release_workflows_stay_guarded(self):
        for name in ('.github/workflows/android.yml', '.github/workflows/diffuse.yml'):
            with self.subTest(path=name):
                self.reset()
                self.write(name, 'name: Restored Signal workflow\n')
                self.commit()
                with self.assertRaisesRegex(ValueError, 'Signal workflow must be absent'):
                    self.check()
        for name in ('.github/workflows/molly-patch-release.yml', '.github/workflows/molly-unsigned-release.yml'):
            for operation in ('delete', 'symlink'):
                with self.subTest(path=name, operation=operation):
                    self.reset()
                    path = self.repo / name
                    path.unlink()
                    if operation == 'symlink':
                        path.symlink_to('unreviewed-target')
                    self.commit()
                    with self.assertRaisesRegex(ValueError, 'Molly workflow must be a regular 100644 file'):
                        self.check()

    def test_ancestry_and_trusted_integration_base_remain_required(self):
        tree = self.git('rev-parse', 'HEAD^{tree}')
        orphan = self.git('commit-tree', tree, '-m', 'unrelated history')
        for base, head in ((orphan, self.secured), (self.anchor, orphan)):
            with self.subTest(base=base, head=head):
                with self.assertRaisesRegex(ValueError, 'pinned main base'):
                    scope.check_main_security_maintenance(self.repo, base, head)
        with self.assertRaisesRegex(ValueError, 'main base is not an ancestor'):
            self.check(base=self.secured, head=self.anchor)
        with self.assertRaisesRegex(ValueError, 'requires pinned release base'):
            scope.check_scope(self.repo, 'main-maintenance', self.anchor)
        with self.assertRaisesRegex(ValueError, 'unexpected trusted release-base commit'):
            scope.check_scope(self.repo, 'main-maintenance', self.anchor, integration_lower=self.secured)


class CheckedInSecurityOverlayTests(unittest.TestCase):
    def test_manifest_pin_and_replacement_blobs_match_checked_in_files(self):
        root = Path(__file__).resolve().parents[2]
        raw = (root / scope.SECURITY_SOURCE_OVERLAY).read_bytes()
        self.assertEqual(hashlib.sha256(raw).hexdigest(), scope.SECURITY_SOURCE_OVERLAY_SHA256)
        manifest = json.loads(raw)
        self.assertEqual(manifest['base_commit'], scope.SECURITY_MAINTENANCE_BASE)
        self.assertEqual({item['path'] for item in manifest['files']}, scope.SECURITY_SOURCE_PATHS)
        for item in manifest['files']:
            with self.subTest(path=item['path']):
                actual = subprocess.check_output(['git', '-C', str(root), 'hash-object', '--', item['path']], text=True).strip()
                self.assertEqual(actual, item['after_blob'])


if __name__ == '__main__':
    unittest.main()
