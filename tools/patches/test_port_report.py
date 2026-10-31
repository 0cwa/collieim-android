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
            with patch.object(port_report, 'git', side_effect=invoke), patch.object(port_report, 'OLD', old), patch.object(port_report, 'MOLLY', new):
                preview = port_report.render(config, {'hunks': {}, 'baseline_mechanical_tree': old, 'baseline_target_tree': new})
                self.assertIn('unmeasured', preview)
                self.assertIn('| mode | 100644 | 100755 |', preview)
                self.assertIn('| binary | 000000 | 100644 |', preview)
                with self.assertRaisesRegex(ValueError, 'missing evidence'):
                    port_report.render(config, {'hunks': {}, 'baseline_mechanical_tree': old, 'baseline_target_tree': new}, ready=True)
