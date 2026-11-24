"""A native upgrade must explicitly review the managed admission schema too."""

import json
from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]


class CallAdmissionSchemaPinTest(unittest.TestCase):
    def test_managed_schema_tracks_the_selected_native_source(self):
        lock = json.loads((ROOT / "build-inputs/native-lock.json").read_text())
        schema = (ROOT / "app/src/main/protowire/CollieCalling.proto").read_text()
        selected = re.search(r"pinned by build-inputs/native-lock\.json to ([a-f0-9]{40})", schema)
        self.assertIsNotNone(selected, "The managed admission schema needs an exact RingRTC source pin")
        self.assertEqual(
            lock["source_provenance"]["ringrtc"],
            selected.group(1),
            "Review the managed opaque decoder against the newly selected RingRTC schema before upgrading",
        )


if __name__ == "__main__":
    unittest.main()
