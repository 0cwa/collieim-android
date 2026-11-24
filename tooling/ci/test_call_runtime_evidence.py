"""The API 35 gate must reject incomplete or contradictory clean-process native-load proof."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('call_device_evidence', ROOT / '.github/scripts/check-call-permissions-results.py')
evidence = importlib.util.module_from_spec(spec)
spec.loader.exec_module(evidence)


class CallRuntimeEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.directory = self.root / 'device/call-runtime-proof'
        self.directory.mkdir(parents=True)
        for pid, (name, enabled, disabled, steps) in enumerate([
            ('on-demand-cold', True, False, evidence.PASSIVE_STEPS),
            ('all-calling-disabled', False, True, evidence.PASSIVE_STEPS + ['denied-explicit-acquisition']),
            ('authorized-acquisition', True, False, ['before-authorized-acquisition', 'authorized-then-revoked']),
            ('pending-call-ui', True, False, evidence.PENDING_UI_STEPS),
        ], start=100):
            checkpoints = [({'step': step, 'nativeMapped': True, 'dispatched': 2} if step == 'authorized-then-revoked' else
                            {'step': step, 'nativeMapped': False, 'initialized': False}) for step in steps]
            for row in checkpoints:
                if row['step'] == 'starting-ui-survived-grace':
                    row['waitedMillis'] = 1250
                elif row['step'] == 'starting-ui-survived-rotation':
                    row['recreated'] = True
                elif row['step'] == 'back-cancelled-before-initialization':
                    row.update(pending=False, runtimeState='COLD')
            proof = {'schema': 1, 'pid': pid, 'onDemandAtStart': enabled, 'allDisabledAtStart': disabled,
                     'checkpoints': checkpoints, 'realCallTimingSamples': 0}
            (self.directory / (name + '.json')).write_text(json.dumps(proof))

    def mutate(self, name, change):
        path = self.directory / (name + '.json')
        proof = json.loads(path.read_text())
        change(proof)
        path.write_text(json.dumps(proof))

    def rejected(self):
        with self.assertRaises(ValueError):
            evidence.check_runtime_probes(self.root)

    def test_four_complete_distinct_process_probes_pass(self):
        evidence.check_runtime_probes(self.root)

    def test_missing_and_duplicate_probe_are_rejected(self):
        original = self.directory / 'on-demand-cold.json'
        saved = original.read_text()
        original.unlink()
        self.rejected()
        original.write_text(saved)
        duplicate = self.root / 'other/call-runtime-proof'
        duplicate.mkdir(parents=True)
        (duplicate / original.name).write_text(saved)
        self.rejected()

    def test_a_native_mapping_or_initialization_breaks_the_cold_guarantee(self):
        self.mutate('on-demand-cold', lambda proof: proof['checkpoints'][0].update(nativeMapped=True))
        self.rejected()
        self.mutate('on-demand-cold', lambda proof: proof['checkpoints'][0].update(nativeMapped=False, initialized=True))
        self.rejected()

    def test_omitting_a_passive_route_is_rejected(self):
        self.mutate('all-calling-disabled', lambda proof: proof['checkpoints'].pop(3))
        self.rejected()

    def test_same_process_or_changed_startup_choice_is_rejected(self):
        self.mutate('all-calling-disabled', lambda proof: proof.update(pid=100))
        self.rejected()
        self.mutate('all-calling-disabled', lambda proof: proof.update(pid=101, onDemandAtStart=True))
        self.rejected()

    def test_failed_positive_control_or_invented_real_call_samples_are_rejected(self):
        self.mutate('authorized-acquisition', lambda proof: proof['checkpoints'][-1].update(nativeMapped=False))
        self.rejected()
        self.mutate('authorized-acquisition', lambda proof: proof['checkpoints'][-1].update(nativeMapped=True))
        self.mutate('authorized-acquisition', lambda proof: proof.update(realCallTimingSamples=1))
        self.rejected()

    def test_missing_grace_rotation_or_pending_cancellation_is_rejected(self):
        for index, field, invalid, valid in [
            (1, 'waitedMillis', 999, 1250),
            (2, 'recreated', False, True),
            (3, 'pending', True, False),
            (3, 'runtimeState', 'READY', 'COLD'),
        ]:
            with self.subTest(field=field):
                self.mutate('pending-call-ui', lambda proof: proof['checkpoints'][index].update({field: invalid}))
                self.rejected()
                self.mutate('pending-call-ui', lambda proof: proof['checkpoints'][index].update({field: valid}))


if __name__ == '__main__':
    unittest.main()
