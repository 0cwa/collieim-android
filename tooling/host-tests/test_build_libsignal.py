"""Offline tests for pinned source preparation and host-native validation."""

import hashlib
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import build_libsignal as builder


class HostLibraryTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.library = self.root / "testing.so"
        header = bytearray(64)
        header[:6] = b"\x7fELF\x02\x01"
        header[16:18] = (3).to_bytes(2, "little")
        header[18:20] = (62).to_bytes(2, "little")
        self.debug_symbols = b"debug symbols"
        self.library.write_bytes(header + self.debug_symbols)
        self.symbols = "\n".join([
            "0001 T Java_org_signal_libsignal_internal_Native_initializeLibrary",
            "0002 T Java_org_signal_libsignal_internal_NativeTesting_Testing_1Ping",
        ])

    def run_native_tool(self, command, **kwargs):
        if command[0] == "ldd":
            return "libc.so => /lib/libc.so"
        if command[0] == "nm":
            return self.symbols
        self.assertEqual(command[:2], ["strip", "--strip-debug"])
        temporary = Path(command[2])
        self.assertEqual(temporary.name, builder.RESOURCE + ".tmp")
        content = temporary.read_bytes()
        self.assertTrue(content.endswith(self.debug_symbols))
        temporary.write_bytes(content.removesuffix(self.debug_symbols))

    def test_missing_library_is_rejected(self):
        with self.assertRaisesRegex(builder.Failure, "missing"):
            builder.validate_library(self.root / "absent.so")

    def test_android_or_wrong_architecture_library_is_rejected(self):
        header = bytearray(self.library.read_bytes())
        header[18:20] = (183).to_bytes(2, "little")
        self.library.write_bytes(header)
        with self.assertRaisesRegex(builder.Failure, "x86-64 Linux"):
            builder.validate_library(self.library)

    def test_unresolved_dynamic_dependency_is_rejected(self):
        with patch.object(builder, "run", return_value="libmissing.so => not found"):
            with self.assertRaisesRegex(builder.Failure, "unresolved"):
                builder.validate_library(self.library)

    def test_missing_production_exports_are_rejected(self):
        with patch.object(builder, "run", side_effect=["libc.so => /lib/libc.so", "0001 T JNI_OnLoad"]):
            with self.assertRaisesRegex(builder.Failure, "production JNI exports"):
                builder.validate_library(self.library)

    def test_missing_testing_exports_are_rejected(self):
        with patch.object(builder, "run", side_effect=["libc.so => /lib/libc.so", self.symbols.splitlines()[0]]):
            with self.assertRaisesRegex(builder.Failure, "testing JNI exports"):
                builder.validate_library(self.library)

    def test_staging_strips_only_the_copy_and_records_its_hash(self):
        work = self.root / "work"
        inputs = {"source_revision": "1" * 40}
        original = self.library.read_bytes()
        with patch.object(builder, "run", side_effect=self.run_native_tool):
            resources = builder.stage_library(self.library, work, inputs)
        self.assertEqual([path.name for path in resources.iterdir()], [builder.RESOURCE])
        self.assertEqual(self.library.read_bytes(), original)
        self.assertEqual((resources / builder.RESOURCE).read_bytes(), original.removesuffix(self.debug_symbols))
        receipt = json.loads((work / "receipt.json").read_text())
        self.assertEqual(receipt["sha256"], builder.digest(resources / builder.RESOURCE))
        self.assertNotEqual(receipt["sha256"], builder.digest(self.library))
        self.assertTrue(receipt["test_only"])
        self.assertTrue(receipt["strip_debug"])
        self.assertEqual(receipt["source_revision"], inputs["source_revision"])
        with patch.object(builder, "run", side_effect=["libc.so => /lib/libc.so", self.symbols]):
            self.assertEqual(builder.verify_staged(work, inputs), resources)

    def test_invalid_stripped_library_does_not_replace_existing_resource(self):
        work = self.root / "work"
        resources = work / "resources"
        resources.mkdir(parents=True)
        staged = resources / builder.RESOURCE
        staged.write_bytes(b"previous library")
        original = self.library.read_bytes()

        def invalid_stripped_exports(command, **kwargs):
            if command[0] == "nm" and Path(command[-1]).suffix == ".tmp":
                return "0001 T JNI_OnLoad"
            return self.run_native_tool(command, **kwargs)

        with patch.object(builder, "run", side_effect=invalid_stripped_exports):
            with self.assertRaisesRegex(builder.Failure, "production JNI exports"):
                builder.stage_library(self.library, work, {})
        self.assertEqual(staged.read_bytes(), b"previous library")
        self.assertEqual(self.library.read_bytes(), original)
        self.assertFalse((resources / (builder.RESOURCE + ".tmp")).exists())
        self.assertFalse((work / "receipt.json").exists())

    def test_cached_resource_checksum_and_source_pins_are_checked(self):
        work = self.root / "work"
        inputs = {"source_revision": "1" * 40}
        with patch.object(builder, "run", side_effect=self.run_native_tool):
            resources = builder.stage_library(self.library, work, inputs)
        with patch.object(builder, "run", side_effect=["libc.so => /lib/libc.so", self.symbols]):
            with self.assertRaisesRegex(builder.Failure, "receipt or checksum differs"):
                builder.verify_staged(work, {"source_revision": "2" * 40})
        with (resources / builder.RESOURCE).open("ab") as stream:
            stream.write(b"changed")
        with patch.object(builder, "run", side_effect=["libc.so => /lib/libc.so", self.symbols]):
            with self.assertRaisesRegex(builder.Failure, "receipt or checksum differs"):
                builder.verify_staged(work, inputs)

    def test_staging_rejects_a_competing_library(self):
        work = self.root / "work"
        (work / "resources").mkdir(parents=True)
        (work / "resources/libsignal_jni.so").write_bytes(b"competing JNI")
        with patch.object(builder, "run", side_effect=["libc.so => /lib/libc.so", self.symbols]):
            with self.assertRaisesRegex(builder.Failure, "Unexpected host JNI resources"):
                builder.stage_library(self.library, work, {})


class SourceTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.upstream = self.root / "upstream"
        self.upstream.mkdir()
        self.git("init", "--quiet")
        (self.upstream / "Cargo.toml").write_text('[workspace.package]\nversion = "0.106.1"\n')
        (self.upstream / "rust-toolchain").write_text("1.98.1\n")
        (self.upstream / "proxy.rs").write_text("direct_fallback\n")
        self.git("add", ".")
        self.git("-c", "user.name=Host JNI fixture", "-c", "user.email=fixture@example.invalid",
                 "-c", "commit.gpgsign=false", "commit", "--quiet", "-m", "Fixture base")
        revision = self.git("rev-parse", "HEAD")
        (self.upstream / "proxy.rs").write_text("proxy_only\n")
        self.git("add", ".")
        patch_bytes = self.git("diff", "--cached").encode() + b"\n"
        self.inputs = {"source_revision": revision,
                       "prepared_tree": self.git("write-tree"),
                       "rust_version": "1.98.1", "client_version": "0.106.1",
                       "patch_sha256": hashlib.sha256(patch_bytes).hexdigest(),
                       "patch_url": "https://example.invalid/pinned.patch"}
        self.work = self.root / "work"
        self.work.mkdir()
        (self.work / "libsignal.mbox").write_bytes(patch_bytes)

    def git(self, *arguments):
        return subprocess.check_output(["git", *arguments], cwd=self.upstream, text=True).strip()

    def test_source_is_patched_without_commits_and_verified_on_reuse(self):
        source = builder.prepare_source(self.work, self.inputs, str(self.upstream))
        self.assertEqual((source / "proxy.rs").read_text(), "proxy_only\n")
        self.assertEqual(subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=source, text=True).strip(),
                         self.inputs["source_revision"])
        self.assertEqual(builder.prepare_source(self.work, self.inputs, str(self.upstream)), source)

    def test_tampered_patch_fails_before_fetching_source(self):
        (self.work / "libsignal.mbox").write_text("tampered")
        with patch.object(builder, "run") as run:
            with self.assertRaisesRegex(builder.Failure, "SHA-256 differs"):
                builder.prepare_source(self.work, self.inputs, str(self.upstream))
        run.assert_not_called()

    def test_bad_download_is_not_saved(self):
        target = self.root / "downloaded.patch"
        with patch("urllib.request.urlopen", return_value=io.BytesIO(b"wrong patch")):
            with self.assertRaisesRegex(builder.Failure, "SHA-256 differs"):
                builder.fetch_patch(target, self.inputs)
        self.assertFalse(target.exists())

    def test_source_tree_mismatch_is_rejected(self):
        self.inputs["prepared_tree"] = "0" * 40
        with self.assertRaisesRegex(builder.Failure, "source tree differs"):
            builder.prepare_source(self.work, self.inputs, str(self.upstream))

    def test_changed_cached_source_is_rejected(self):
        source = builder.prepare_source(self.work, self.inputs, str(self.upstream))
        (source / "proxy.rs").write_text("unexpected_fallback\n")
        with self.assertRaises(subprocess.CalledProcessError):
            builder.verify_source(source, self.inputs)


if __name__ == "__main__":
    unittest.main()
