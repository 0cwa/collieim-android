import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import compatibility_surface as surface


class ChoiceTests(unittest.TestCase):
    def test_choice_and_independent_edit(self):
        mechanical = b'head\n<<<<<<< MOLLY\nmolly\n=======\nsignal\n>>>>>>> SIGNAL\ntail\nold\n'
        final = b'head\nmolly\ntail\nnew\n'
        status, result, choices = surface.baseline(mechanical, final)
        self.assertEqual(status, 'verified_conflict')
        self.assertEqual(result, b'head\nmolly\ntail\nold\n')
        self.assertEqual(choices[0]['side'], 'MOLLY')

    def test_repeated_mixed_missing_and_overlap(self):
        conflict = b'head\n<<<<<<< MOLLY\none\ntwo\n=======\nthree\nfour\n>>>>>>> SIGNAL\ntail\n'
        self.assertEqual(surface.baseline(conflict, b'head\none\ntwo\ntail\nhead\none\ntwo\ntail\n')[0], 'ambiguous')
        self.assertEqual(surface.baseline(conflict, b'head\none\nfour\ntail\n')[0], 'unmatched')
        self.assertEqual(surface.baseline(conflict, b'head\nother\ntail\n')[0], 'unmatched')
        overlap = (b'head\n<<<<<<< MOLLY\na\n=======\nx\n>>>>>>> SIGNAL\nmid\n'
                   b'<<<<<<< MOLLY\nb\n=======\ny\n>>>>>>> SIGNAL\ntail\n')
        self.assertEqual(surface.baseline(overlap, b'head\na\nmid\nb\ntail\n')[0], 'ambiguous')

    def test_changed_context_and_empty_choice_are_not_guessed(self):
        conflict = b'head\n<<<<<<< MOLLY\none\n=======\n>>>>>>> SIGNAL\ntail\n'
        self.assertEqual(surface.baseline(conflict, b'changed\none\ntail\n')[0], 'unmatched')
        self.assertEqual(surface.baseline(conflict, b'head\ntail\n')[0], 'unmatched')


class InventoryTests(unittest.TestCase):
    def test_inventory_and_pinned_trees(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def run(*args):
                return subprocess.check_output(['git', *args], cwd=root).decode().strip()
            run('init', '-q')
            (root / 'conflict').write_bytes(b'head\n<<<<<<< MOLLY\na\n=======\nb\n>>>>>>> SIGNAL\ntail\nold\n')
            (root / 'deleted').write_text('old\n')
            (root / 'binary').write_bytes(b'\0old')
            (root / 'mode').write_text('mode\n')
            (root / 'generated').write_text('old\n')
            run('add', '.'); old = run('write-tree')
            (root / 'conflict').write_bytes(b'head\na\ntail\nnew\n')
            (root / 'deleted').unlink()
            (root / 'binary').write_bytes(b'\0new')
            (root / 'mode').chmod(0o755)
            (root / 'generated').write_text('new\n')
            run('add', '-A'); new = run('write-tree')
            original = os.getcwd()
            try:
                os.chdir(root)
                report = surface.assess(old, new, directory, ['generated'])
                self.assertEqual(report['coverage_counts'], {'binary': 1, 'declared_generated_unverified': 1, 'deleted': 1, 'mode_or_nonblob': 1, 'verified_conflict': 1})
                self.assertEqual(report['inventory_count'], 5)
                row = next(x for x in report['paths'] if x['path'] == 'conflict')
                self.assertEqual(row['residual_add_delete'], [1, 1])
                with self.assertRaises(ValueError): surface.assess(old, new, directory, ['missing'])
                with self.assertRaises(ValueError): surface.assess(old, new, directory, ['generated', 'generated'])
                with self.assertRaises(subprocess.CalledProcessError): surface.assess('0' * 40, new, directory)
            finally:
                os.chdir(original)


if __name__ == '__main__':
    unittest.main()
