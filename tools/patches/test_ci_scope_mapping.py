import os
import json
import shutil
from pathlib import Path
import subprocess
import tempfile
import unittest

WORKFLOW = Path(__file__).resolve().parents[2] / '.github/workflows/molly-patch-release.yml'


class ScopeMappingTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        self.repo = root / 'repo'
        self.repo.mkdir()
        self.git('init', '-q')
        self.git('config', 'user.name', 'Scope test')
        self.git('config', 'user.email', 'scope-test@example.invalid')
        config = {'profiles': {'collie': {'target_tree': 'old'},
                               'collie-foss': {'target_tree': 'old'},
                               'molly-minimal': {'target_tree': 'old'}}}
        (self.repo / 'molly-build.json').write_text(json.dumps(config))
        self.git('add', 'molly-build.json')
        self.git('commit', '-qm', 'release base')
        self.lower = self.git('rev-parse', 'HEAD').strip()
        self.git('branch', 'private/release-base-5')
        self.git('commit', '--allow-empty', '-qm', 'variants')
        self.variants = self.git('rev-parse', 'HEAD').strip()
        self.git('branch', 'private/release-variants')
        self.git('commit', '--allow-empty', '-qm', 'theme')
        self.theme = self.git('rev-parse', 'HEAD').strip()
        self.git('branch', 'feature/collie-theme-pr17-126352')
        self.git('commit', '--allow-empty', '-qm', 'donation')
        self.head = self.git('rev-parse', 'HEAD').strip()
        self.git('checkout', '-q', '--orphan', 'unrelated')
        self.git('commit', '--allow-empty', '-qm', 'unrelated')
        self.unrelated = self.git('rev-parse', 'HEAD').strip()
        self.git('checkout', '-q', '--detach', self.head)
        wrapper = root / 'bin'
        wrapper.mkdir()
        git = wrapper / 'git'
        git.write_text('#!/bin/bash\nif [[ "$1" == fetch && "$3" == https://github.example/trusted/repo.git ]]; then\n  exec /usr/bin/git fetch "$2" "$TEST_TRUSTED_REPO" "$4"\nfi\nexec /usr/bin/git "$@"\n')
        git.chmod(0o755)
        tools = self.repo / 'tools/patches'
        tools.mkdir(parents=True)
        shutil.copyfile(WORKFLOW.parents[2] / 'tools/patches/check_collie_scope.py', tools / 'check_collie_scope.py')
        self.env = dict(os.environ, PATH=str(wrapper) + ':' + os.environ['PATH'],
                        TEST_TRUSTED_REPO=str(self.repo), GITHUB_SERVER_URL='https://github.example',
                        GITHUB_REPOSITORY='trusted/repo')
        workflow = WORKFLOW.read_text()
        start = workflow.index('          # Resolve stacked Collie checks')
        end = workflow.index('          if [[ "$scope" == release ', start)
        self.mapping = '\n'.join(line[10:] for line in workflow[start:end].splitlines())

    def tearDown(self):
        self.tmp.cleanup()

    def git(self, *args):
        return subprocess.check_output(['/usr/bin/git', '-C', str(self.repo), *args], text=True, stderr=subprocess.PIPE)

    def run_mapping(self, event, base_ref='', base_sha='', head_ref=''):
        env = dict(self.env, EVENT_NAME=event, BASE_REF=base_ref, BASE_SHA=base_sha, HEAD_REF=head_ref)
        return subprocess.run(['bash', '-e', '-c', self.mapping + '\nprintf "%s %s\\n" "$scope" "$base"'],
                              cwd=self.repo, env=env, text=True, capture_output=True)

    def test_pr_base_mappings_and_ancestry(self):
        for ref, sha in (('private/release-variants', self.variants),
                         ('feature/collie-theme-pr17-126352', self.theme)):
            with self.subTest(ref=ref):
                result = self.run_mapping('pull_request', ref, sha)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout.strip(), 'forks ' + self.lower)
        for sha in ('bad', self.unrelated, self.lower):
            with self.subTest(invalid=sha):
                if sha == self.lower:
                    self.git('checkout', '-q', '--detach', self.unrelated)
                result = self.run_mapping('pull_request', 'private/release-variants', sha)
                self.assertNotEqual(result.returncode, 0)
                self.git('checkout', '-q', '--detach', self.head)
        self.assertNotEqual(self.run_mapping('pull_request', 'private/release-variants-extra', self.variants).returncode, 0)

    def test_collie_immediate_diff_rejects_unrelated_paths_and_profile_changes(self):
        branding = self.repo / 'patches/forks/signal-foss/collie-branding'
        branding.mkdir(parents=True)
        (branding / 'example.patch').write_text('example')
        self.git('add', 'patches')
        self.git('commit', '-qm', 'branding')
        self.assertEqual(self.run_mapping('pull_request', 'feature/collie-theme-pr17-126352', self.theme).returncode, 0)
        (self.repo / 'unrelated.txt').write_text('unexpected')
        self.git('add', 'unrelated.txt')
        self.git('commit', '-qm', 'unrelated change')
        result = self.run_mapping('pull_request', 'feature/collie-theme-pr17-126352', self.theme)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('path outside immediate Collie scope', result.stderr)
        self.git('reset', '--hard', '-q', self.head)
        config = json.loads((self.repo / 'molly-build.json').read_text())
        config['profiles']['molly-minimal']['target_tree'] = 'unexpected'
        (self.repo / 'molly-build.json').write_text(json.dumps(config))
        self.git('add', 'molly-build.json')
        self.git('commit', '-qm', 'unrelated profile change')
        result = self.run_mapping('pull_request', 'feature/collie-theme-pr17-126352', self.theme)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('changed other profile configuration', result.stderr)

    def test_invalid_or_unrelated_config_changes_fail_closed(self):
        original = (self.repo / 'molly-build.json').read_text()
        for case in ('malformed', 'missing_profile', 'unknown_key'):
            with self.subTest(case=case):
                self.git('reset', '--hard', '-q', self.head)
                config = json.loads(original)
                if case == 'malformed':
                    content = '{'
                else:
                    if case == 'missing_profile':
                        del config['profiles']['collie']
                    else:
                        config['profiles']['collie']['unexpected'] = True
                    content = json.dumps(config)
                (self.repo / 'molly-build.json').write_text(content)
                self.git('add', 'molly-build.json')
                self.git('commit', '-qm', case)
                result = self.run_mapping('pull_request', 'feature/collie-theme-pr17-126352', self.theme)
                self.assertNotEqual(result.returncode, 0, case)
                self.assertIn('Collie scope check failed:', result.stderr)

    def test_main_mapping_uses_actual_main_and_trusted_lower(self):
        self.mapping = self.mapping.replace('7f0eb01a0ab472deeef3b528e7f508425044fa6a', self.lower)
        result = self.run_mapping('pull_request', 'main', self.variants)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), 'forks ' + self.variants)
        for sha in ('bad', self.unrelated):
            self.assertNotEqual(self.run_mapping('pull_request', 'main', sha).returncode, 0)
        self.git('checkout', '-q', '--detach', self.unrelated)
        self.assertNotEqual(self.run_mapping('pull_request', 'main', self.variants).returncode, 0)

    def test_dispatch_mappings(self):
        for ref in ('feature/collie-theme-pr17-126352', 'feature/collie-donation-pr17-126352'):
            with self.subTest(ref=ref):
                result = self.run_mapping('workflow_dispatch', head_ref=ref)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout.strip(), 'forks ' + self.lower)


if __name__ == '__main__':
    unittest.main()
