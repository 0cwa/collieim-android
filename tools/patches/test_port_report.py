import unittest
import port_report


class PortReportTests(unittest.TestCase):
    def test_header_like_changed_lines_are_hunk_content(self):
        patch = b'diff --git a/f b/f\n--- a/f\n+++ b/f\n@@ -1,2 +1,2 @@\n--- old\n+++ new\n context\n'
        hunks = port_report.parse_hunks(patch)
        self.assertEqual([(h[0], h[1]) for h in hunks], [(1, 1)])

    def test_truncated_hunk_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'truncated'):
            port_report.parse_hunks(b'@@ -1,2 +1,2 @@\n-old\n+new\n')

    def test_numstat_preserves_tabs_and_binary_paths(self):
        raw = b'2\t3\tfile\tname\0-\t-\tbinary\0'
        from unittest.mock import patch
        with patch.object(port_report, 'git', return_value=raw):
            self.assertEqual(port_report.numstat('a', 'b'), {b'file\tname': (2, 3), b'binary': None})

    def test_real_git_diff_needs_evidence_before_ready(self):
        import pathlib
        import subprocess
        import tempfile
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory)
            def run(*args):
                return subprocess.check_output(['git', '-C', directory, *args]).decode().strip()
            run('init', '-q')
            run('config', 'user.name', 'Test')
            run('config', 'user.email', 'test@example.invalid')
            (path / 'file').write_text('-- old\n')
            run('add', '.')
            run('commit', '-qm', 'old')
            old_commit = run('rev-parse', 'HEAD')
            old = run('rev-parse', 'HEAD^{tree}')
            (path / 'file').write_text('++ new\n')
            (path / 'file').chmod(0o755)
            (path / 'binary').write_bytes(b'\x00\xff')
            run('add', '.')
            run('commit', '-qm', 'new')
            new = run('rev-parse', 'HEAD^{tree}')
            def invoke(*args):
                return subprocess.check_output(['git', '-C', directory, *args])
            config = {'signal': {'commit': old_commit}, 'port': {'mechanical_tree': old}, 'target_tree': new}
            with patch.object(port_report, 'git', side_effect=invoke), patch.object(port_report, 'OLD', old), patch.object(port_report, 'MOLLY', new), patch.object(port_report, 'clean_contribution', return_value=((0, 0, 0), [])):
                preview = port_report.render(config, {'hunks': {}, 'baseline_mechanical_tree': old, 'baseline_target_tree': new})
                self.assertIn('unmeasured', preview)
                self.assertIn('| mode | 100644 | 100755 |', preview)
                self.assertIn('| binary | 000000 | 100644 |', preview)
                with self.assertRaisesRegex(ValueError, 'missing evidence'):
                    port_report.render(config, {'hunks': {}, 'baseline_mechanical_tree': old, 'baseline_target_tree': new}, ready=True)

    def test_evidence_survives_unrelated_change_and_duplicate_hunks_are_distinct(self):
        import pathlib
        import re
        import subprocess
        import tempfile
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory)
            def run(*args):
                return subprocess.check_output(['git', '-C', directory, *args]).decode().strip()
            run('init', '-q')
            run('config', 'user.name', 'Test')
            run('config', 'user.email', 'test@example.invalid')
            original = 'old\n' + 'context\n' * 10 + 'old\n'
            (path / 'duplicate').write_text(original)
            (path / 'other').write_text('old\n')
            (path / 'binary').write_bytes(b'\x00old')
            (path / 'mode').write_text('mode\n')
            run('add', '.')
            run('commit', '-qm', 'base')
            commit = run('rev-parse', 'HEAD')
            before = run('rev-parse', 'HEAD^{tree}')
            (path / 'duplicate').write_text(original.replace('old', 'new'))
            (path / 'other').write_text('new\n')
            (path / 'binary').write_bytes(b'\x00new')
            (path / 'mode').chmod(0o755)
            run('add', '.')
            first = run('write-tree')
            config = {'signal': {'commit': commit}, 'port': {'mechanical_tree': before}, 'target_tree': first}
            evidence = {'hunks': {}, 'baseline_mechanical_tree': before, 'baseline_target_tree': first}
            def invoke(*args):
                return subprocess.check_output(['git', '-C', directory, *args])
            def ids(report, name):
                return re.findall(r'^\| ([0-9a-f]{20}) \| ' + name + r' \|', report, re.M)
            with patch.object(port_report, 'git', side_effect=invoke), patch.object(port_report, 'OLD', before), patch.object(port_report, 'MOLLY', first), patch.object(port_report, 'clean_contribution', return_value=((0, 0, 0), [])):
                report = port_report.render(config, evidence)
                duplicates = ids(report, 'duplicate')
                self.assertEqual(len(set(duplicates)), 2)
                preserved = duplicates + ids(report, 'binary') + ids(report, 'mode')
                evidence['hunks'] = {key: {'reason': 'recorded evidence'} for key in preserved}
                (path / 'other').write_text('different\n')
                run('add', '.')
                config['target_tree'] = run('write-tree')
                updated = port_report.render(config, evidence)
                self.assertEqual(ids(updated, 'duplicate'), duplicates)
                self.assertEqual(ids(updated, 'binary') + ids(updated, 'mode'), preserved[2:])
                self.assertEqual(updated.count('recorded evidence'), 4)
                self.assertNotEqual(ids(report, 'other'), ids(updated, 'other'))
                (path / 'duplicate').write_text(original.replace('old', 'new', 1))
                run('add', '.')
                config['target_tree'] = run('write-tree')
                with self.assertRaisesRegex(ValueError, 'stale or overlapping metadata'):
                    port_report.render(config, evidence)


class EvidenceModelTests(unittest.TestCase):
    def test_groups_require_explicit_nonoverlapping_ids_and_cannot_claim_gates(self):
        group = {'hunks': ['a'], 'description': {'reason': 'shared'}}
        self.assertEqual(port_report.expand_groups({'groups': {'one': group}, 'hunks': {'a': {'outcome': 'fail'}}})['a'], {'reason': 'shared', 'outcome': 'fail'})
        with self.assertRaisesRegex(ValueError, 'overlapping'):
            port_report.expand_groups({'groups': {'one': group, 'two': group}})
        with self.assertRaisesRegex(ValueError, 'invalid group'):
            port_report.expand_groups({'groups': {'one': {'hunks': ['a'], 'description': {'revert_gate': 'pass'}}}})

    def test_action_replay_rejects_stale_input(self):
        import os
        import pathlib
        import subprocess
        import tempfile
        with tempfile.TemporaryDirectory() as directory:
            old_cwd = os.getcwd()
            try:
                os.chdir(directory)
                def run(*args): return subprocess.check_output(['git', *args]).decode().strip()
                run('init', '-q')
                pathlib.Path('source').write_text('old\n')
                run('add', 'source')
                before = run('write-tree')
                blob = run('rev-parse', ':source')
                pathlib.Path('source').write_text('new\n')
                patch = subprocess.check_output(['git', 'diff', '--', 'source'])
                pathlib.Path('action.patch').write_bytes(patch)
                run('add', 'action.patch')
                run('add', 'source')
                after = run('rev-parse', ':source')
                action = {'path': 'source', 'input_blob': blob, 'output_blob': after, 'patch': 'action.patch', 'gate_input': 'tested source state', 'gate': 'compile', 'outcome': 'pass', 'evidence': 'log'}
                data = {'action_chains': [{'start_tree': before, 'actions': [action]}]}
                self.assertEqual(len(port_report.action_history(data)), 1)
                action['input_blob'] = after
                with self.assertRaisesRegex(ValueError, 'stale action input'):
                    port_report.action_history(data)
            finally:
                os.chdir(old_cwd)

    def test_actual_merge_conflicts_excluded_from_clean_count(self):
        import os
        import pathlib
        import subprocess
        import tempfile
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as directory:
            original = os.getcwd()
            try:
                os.chdir(directory)
                def run(*args): return subprocess.check_output(['git', *args]).decode().strip()
                run('init', '-q'); run('config', 'user.name', 'Test'); run('config', 'user.email', 'test@example.invalid')
                pathlib.Path('conflict').write_text('base\n')
                pathlib.Path('deleted').write_text('base\n')
                pathlib.Path('binary').write_bytes(b'\0base')
                run('add', '.'); run('commit', '-qm', 'base'); base = run('rev-parse', 'HEAD')
                pathlib.Path('conflict').write_text('molly\n'); pathlib.Path('deleted').unlink()
                pathlib.Path('binary').write_bytes(b'\0molly')
                pathlib.Path('clean').write_text('clean\n')
                run('add', '-A'); run('commit', '-qm', 'molly'); molly = run('rev-parse', 'HEAD')
                run('checkout', '-q', '--detach', base)
                pathlib.Path('conflict').write_text('signal\n'); pathlib.Path('deleted').write_text('signal\n')
                pathlib.Path('binary').write_bytes(b'\0signal')
                run('add', '-A'); run('commit', '-qm', 'signal'); signal = run('rev-parse', 'HEAD')
                tree, _, _ = port_report.release.mechanical_merge('.', base, molly, signal)
                with patch.object(port_report, 'OLD', base), patch.object(port_report, 'MOLLY', molly):
                    counts, conflicts = port_report.clean_contribution(signal, tree)
                self.assertEqual(set(conflicts), {'conflict', 'deleted', 'binary'})
                self.assertEqual(counts, (1, 0, 0))
            finally:
                os.chdir(original)
