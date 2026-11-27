"""Exercise the relay instrumentation gate with raw AndroidJUnitRunner output."""
from pathlib import Path
from runpy import run_path
import unittest


GATE = run_path(str(Path(__file__).resolve().parents[2] / '.github/scripts/check-relay-disclosure-results.py'))
CLASS = GATE['CLASS']
METHOD = 'addReceiverConsentShowsFourRowsAndExactDeviceWording'


def status(code, method=METHOD):
    return (
        f'INSTRUMENTATION_STATUS: class={CLASS}\n'
        f'INSTRUMENTATION_STATUS: test={method}\n'
        f'INSTRUMENTATION_STATUS_CODE: {code}\n'
    )


class RelayDisclosureResultsTest(unittest.TestCase):
    def test_one_pass_with_successful_instrumentation(self):
        raw = status(1) + status(0) + 'INSTRUMENTATION_CODE: -1\n'
        GATE['require_instrumentation_pass'](raw, METHOD)

    def test_rejects_missing_duplicate_or_wrong_tests(self):
        for cases in ('', status(1), (status(1) + status(0)) * 2, status(1) + status(0, 'otherMethod')):
            with self.subTest(cases=cases), self.assertRaises(ValueError):
                GATE['require_instrumentation_pass'](cases + 'INSTRUMENTATION_CODE: -1\n', METHOD)

    def test_rejects_failed_or_skipped_test_and_failed_instrumentation(self):
        for code in (-1, -2, -3, -4):
            with self.subTest(status_code=code), self.assertRaises(ValueError):
                GATE['require_instrumentation_pass'](status(1) + status(code) + 'INSTRUMENTATION_CODE: -1\n', METHOD)
        for result in ('', 'INSTRUMENTATION_CODE: 0\n', 'INSTRUMENTATION_RESULT: shortMsg=Process crashed\nINSTRUMENTATION_CODE: -1\n'):
            with self.subTest(result=result), self.assertRaises(ValueError):
                GATE['require_instrumentation_pass'](status(1) + status(0) + result, METHOD)
