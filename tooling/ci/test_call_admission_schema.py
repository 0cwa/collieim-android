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

    def test_managed_call_link_metadata_codec_tracks_native_source(self):
        lock = json.loads((ROOT / "build-inputs/native-lock.json").read_text())
        codec = (ROOT / "app/src/main/java/org/thoughtcrime/securesms/calls/permissions/CallLinkKeyCodec.kt").read_text()
        selected = re.search(r"pinned to\s+\* ([a-f0-9]{40})", codec)
        self.assertIsNotNone(selected, "The managed key codec needs an exact RingRTC source pin")
        self.assertEqual(
            lock["source_provenance"]["ringrtc"],
            selected.group(1),
            "Review key versions, validation, formatting, and room-ID derivation before a native upgrade",
        )

    def test_managed_call_era_id_tracks_native_source(self):
        lock = json.loads((ROOT / "build-inputs/native-lock.json").read_text())
        codec = (ROOT / "app/src/main/java/org/thoughtcrime/securesms/calls/permissions/CallEraId.kt").read_text()
        selected = re.search(r"Pinned source: signalapp/ringrtc ([a-f0-9]{40})", codec)
        self.assertIsNotNone(selected, "The managed era-ID conversion needs an exact RingRTC source pin")
        self.assertEqual(
            lock["source_provenance"]["ringrtc"],
            selected.group(1),
            "Review era-ID parsing, reserved values, and fallback hashing before a native upgrade",
        )


if __name__ == "__main__":
    unittest.main()
