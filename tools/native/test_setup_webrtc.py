import json
from pathlib import Path
import subprocess
from unittest.mock import patch

import prepare_sources
import setup_webrtc
import test_prepare_sources
import webrtc_deps

class WebRtcSetupTest(test_prepare_sources.SourcePreparationTest):
    def setUp(self):
        super().setUp()
        self.prepared = self.root / 'setup-prepared'
        prepare_sources.prepare(self.lock_file, self.sources, self.prepared)
        self.depot = self.root / 'depot'
        self.depot.mkdir()
        (self.depot / 'gclient').write_text('#!/bin/sh\n')
        (self.depot / 'gclient').chmod(0o755)
        self.output = self.root / 'workspace'

    def runner(self, args, cwd, env):
        self.assertEqual('0', env['DEPOT_TOOLS_UPDATE'])
        self.assertEqual('1', env['GIT_CONFIG_NOSYSTEM'])
        if args[1] == 'sync':
            subprocess.run(['git', 'clone', '--quiet', str(self.sources['webrtc']), str(cwd / 'src')], check=True)
            subprocess.run(['git', '-C', str(cwd / 'src'), 'checkout', '--quiet', self.lock['source_provenance']['webrtc']], check=True)
        else:
            self.assertNotIn(webrtc_deps.TEST_MEDIA_HOOK, (cwd / 'src/DEPS').read_bytes())

    def test_setup_produces_only_partial_receipt_and_exact_tree(self):
        with patch.object(setup_webrtc, 'validate_depot', return_value=self.depot):
            result = setup_webrtc.setup(self.lock_file, self.prepared, self.depot, self.output, self.runner)
        self.assertEqual('PARTIAL_WEBRTC_SETUP', result['status'])
        self.assertFalse(result['release_allowed'])
        self.assertEqual(result, json.loads((self.output / 'setup-receipt.json').read_text()))
        self.assertIn(webrtc_deps.TEST_MEDIA_HOOK, (self.output / 'src/DEPS').read_bytes())

    def test_hook_failure_restores_deps_without_receipt(self):
        def failed(args, cwd, env):
            if args[1] == 'runhooks':
                raise setup_webrtc.Failure('hook failed')
            self.runner(args, cwd, env)
        with patch.object(setup_webrtc, 'validate_depot', return_value=self.depot):
            with self.assertRaises(setup_webrtc.Failure):
                setup_webrtc.setup(self.lock_file, self.prepared, self.depot, self.output, failed)
        self.assertIn(webrtc_deps.TEST_MEDIA_HOOK, (self.output / 'src/DEPS').read_bytes())
        self.assertFalse((self.output / 'setup-receipt.json').exists())
