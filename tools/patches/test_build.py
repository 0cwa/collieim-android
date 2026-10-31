"""Release builds must consume the proved source, even after Gradle has run."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import build


class BuildSourceTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='molly-build-source-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.repo = self.root / 'pipeline'
        self.app = self.root / 'application'
        self.reports = self.root / 'reports'
        self.repo.mkdir()
        self.app.mkdir()
        self.reports.mkdir()
        (self.app / 'gradle').mkdir()
        (self.app / 'module').mkdir()
        (self.app / 'application.kt').write_bytes(b'original\n')
        (self.app / 'build.gradle.kts').write_text('// source fixture\n')
        (self.app / 'module/build.gradle.kts').write_text('// module fixture\n')
        (self.app / 'gradle/verification-metadata.xml').write_bytes(b'original verification\n')
        self.git('init', '-q')
        self.git('config', 'user.name', 'Source Fixture')
        self.git('config', 'user.email', 'source-fixture@example.invalid')
        self.git('add', '.')
        self.git('commit', '-qm', 'Source fixture')
        self.config = {'schema': 1, 'signal': {'commit': 'a' * 40},
                       'target_tree': self.git('rev-parse', 'HEAD^{tree}'), 'android': {}}
        self.proof = {'status': 'PASS', 'signal': self.config['signal']['commit'],
                      'tree': self.config['target_tree'], 'worktree': str(self.app), 'native': None}
        self.save()
        self.args = argparse.Namespace(repo=self.repo, config='molly-build.json',
                                       worktree=self.app, report_dir=self.reports,
                                       native_source_dir=None, maven_dir=None, heap='5g')

    def git(self, *args):
        env = {key: value for key, value in os.environ.items() if not key.startswith('GIT_')}
        env.update(GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL=os.devnull)
        return subprocess.check_output(['git', '-C', str(self.app), *args],
                                       text=True, stderr=subprocess.PIPE, env=env).strip()

    def save(self):
        (self.repo / 'molly-build.json').write_text(json.dumps(self.config))
        (self.reports / 'source.json').write_text(json.dumps(self.proof))

    def test_clean_source_and_generated_outputs_are_allowed(self):
        for path in ('module/build/generated/Generated.kt', 'build/outputs/app.apk',
                     '.gradle/cache/entry', '.kotlin/sessions/entry', 'module/.cxx/object.o'):
            file = self.app / path
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_bytes(b'generated output\n')
        self.assertEqual(build.Build(self.args).source_proof(), self.proof)

    def test_untracked_source_and_fake_build_package_are_rejected(self):
        for name in ('module/Added.kt', 'module/src/main/java/build/Added.java'):
            with self.subTest(name=name):
                path = self.app / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('unproved source\n')
                with self.assertRaisesRegex(build.BuildError, 'untracked source'):
                    build.Build(self.args).source_proof()
                path.unlink()

    def test_changed_working_bytes_with_same_size_and_timestamp_are_rejected(self):
        path = self.app / 'application.kt'
        before = path.stat()
        self.git('update-index', '--refresh')
        path.write_bytes(b'tampered\n')
        os.utime(path, ns=(before.st_atime_ns, before.st_mtime_ns))
        with self.assertRaisesRegex(build.BuildError, 'working source differs'):
            build.Build(self.args).source_proof()

    def test_staged_edit_is_rejected_even_if_working_bytes_match_head(self):
        path = self.app / 'application.kt'
        path.write_bytes(b'staged change\n')
        self.git('add', 'application.kt')
        path.write_bytes(b'original\n')
        with self.assertRaisesRegex(build.BuildError, 'staged source differs'):
            build.Build(self.args).source_proof()

    def test_new_staged_source_path_is_rejected(self):
        (self.app / 'new.kt').write_text('new source\n')
        self.git('add', 'new.kt')
        with self.assertRaisesRegex(build.BuildError, 'adds or removes tracked paths'):
            build.Build(self.args).source_proof()

    def test_skip_worktree_and_assume_unchanged_are_rejected_even_when_clean(self):
        for flag, undo in (('--skip-worktree', '--no-skip-worktree'),
                           ('--assume-unchanged', '--no-assume-unchanged')):
            with self.subTest(flag=flag):
                self.git('update-index', flag, 'application.kt')
                with self.assertRaisesRegex(build.BuildError, 'skip-worktree, assume-unchanged'):
                    build.Build(self.args).source_proof()
                self.git('update-index', undo, 'application.kt')

    def test_sparse_checkout_setting_is_rejected_without_skip_flags(self):
        self.git('config', 'core.sparseCheckout', 'true')
        with self.assertRaisesRegex(build.BuildError, 'sparse source checkouts'):
            build.Build(self.args).source_proof()

    def test_executable_mode_change_is_rejected_even_if_git_ignores_it(self):
        self.git('config', 'core.filemode', 'false')
        (self.app / 'application.kt').chmod(0o755)
        with self.assertRaisesRegex(build.BuildError, 'executable mode changed'):
            build.Build(self.args).source_proof()

    def test_exact_native_metadata_is_allowed_but_any_extra_change_is_rejected(self):
        # Native verification independently proves receipts, artifact hashes,
        # XML rendering and the generated init script. Here its verified bytes
        # are the fixture input to the application's source-boundary check.
        native = b'original verification\nexact native coordinate and checksums\n'
        self.config['native'] = 'native-ringrtc.json'
        self.proof['native'] = 'native-ringrtc.json'
        self.save()
        runner = build.Build(self.args)
        metadata = self.app / 'gradle/verification-metadata.xml'
        with patch.object(runner, 'native_verification_bytes', return_value=native):
            metadata.write_bytes(native)
            self.assertEqual(runner.source_proof(), self.proof)
            self.git('add', 'gradle/verification-metadata.xml')
            self.assertEqual(runner.source_proof(), self.proof)
            metadata.write_bytes(native + b'extra trust rule\n')
            with self.assertRaisesRegex(build.BuildError, 'working source differs'):
                runner.source_proof()
            metadata.write_bytes(native)
            (self.app / 'application.kt').write_bytes(b'tampered\n')
            with self.assertRaisesRegex(build.BuildError, 'working source differs'):
                runner.source_proof()

    def test_verification_metadata_without_native_proof_has_no_exception(self):
        (self.app / 'gradle/verification-metadata.xml').write_bytes(b'changed verification\n')
        with self.assertRaisesRegex(build.BuildError, 'working source differs'):
            build.Build(self.args).source_proof()


if __name__ == '__main__':
    unittest.main()
