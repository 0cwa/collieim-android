"""Source checkpoints stay required while unsupported native phases are explicit."""
import json
from pathlib import Path
import re
import unittest
import hosted_eligibility
from test_optional_instrumentation_workflow import expression,job
ROOT=Path(__file__).resolve().parents[2]

class HostedPhaseWorkflowTests(unittest.TestCase):
    def setUp(self):self.text=(ROOT/'.github/workflows/molly-patch-release.yml').read_text()
    def test_full_source_proof_and_historical_matrix_are_not_gated_on_native_builds(self):
        source=job(self.text,'source-proof');self.assertIn("if: needs.scope.outputs.profile == 'true'",source)
        self.assertIn('python3 tools/patches/release.py --repo . --config molly-build.json',source)
        self.assertIn('--report "$RUNNER_TEMP/reports/source.json"',source)
        self.assertIn('"$RUNNER_TEMP/reports/hosted-eligibility.json"',source)
        self.assertIn('profiles=["molly-minimal","molly-continued","molly-foss-continued","collie","collie-foss"]',self.text)
        self.assertIn('hosted_android: ${{ steps.hosted.outputs.android }}',self.text)
        self.assertIn('hosted_eligibility.py --repo . --github-output "$GITHUB_OUTPUT"',job(self.text,'scope'))
    def test_native_android_and_instrumentation_obey_actual_declared_integration(self):
        android=re.search(r'^    if: (.+)$',job(self.text,'android'),re.M).group(1)
        native=re.search(r'Prove the upstream native cherry-pick when configured\n        if: (.+)',self.text).group(1)
        instrument=job(self.text,'instrumentation');gate=re.search(r'^    if: >-\n((?:      .*\n)+)',instrument,re.M).group(1)
        current=hosted_eligibility.eligibility(ROOT,json.loads((ROOT/'molly-build.json').read_text()))
        self.assertFalse(current['android']);self.assertEqual(current['status'],'NOT RUN')
        for scope in ('main-maintenance','forks','main-snapshot','next-port'):
            for condition in (android,native,' '.join(gate.split())):
                self.assertFalse(expression(condition,event='workflow_dispatch',choice='both',scope=scope,hosted='false'))
        for condition in (android,native):
            self.assertTrue(expression(condition,event='pull_request',choice='off',scope='forks',hosted='true'))
            self.assertFalse(expression(condition,event='pull_request',choice='off',scope='next-port',hosted='true'))
        self.assertIn('python3 tools/patches/build.py native-prepare',self.text)
        unsigned=(ROOT/'.github/workflows/molly-unsigned-release.yml').read_text()
        self.assertEqual(unsigned.count('Signal 8.35.1 release blocked: native integration and port review incomplete'),2)
