"""Protect the JNI analysis build's real-toolchain and target requirements."""

import json
from pathlib import Path
import tempfile
import unittest

import build_jni


class JniBuildTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.repo = self.root / "source"
        (self.repo / "gradle").mkdir(parents=True)
        (self.repo / "app/jni").mkdir(parents=True)
        (self.repo / "gradle/libs.versions.toml").write_text(
            '[versions]\nndk = "28.0.13004108"\nminSdk = "23"\n', encoding="utf-8")
        (self.repo / "molly-build.json").write_text(
            json.dumps({"android": {"ndk": "28.0.13004108"}}), encoding="utf-8")
        (self.repo / "app/jni/Application.mk").write_text(
            "APP_ABI := armeabi-v7a x86 arm64-v8a x86_64\nAPP_PLATFORM := android-19\n", encoding="utf-8")

    def test_target_uses_current_minimum_and_all_declared_abis(self):
        revision, minimum, abis = build_jni.configuration(self.repo)
        self.assertEqual(revision, "28.0.13004108")
        self.assertEqual(minimum, 23)
        self.assertEqual(abis, ["armeabi-v7a", "x86", "arm64-v8a", "x86_64"])

    def test_mismatched_release_toolchain_pin_is_not_used(self):
        (self.repo / "molly-build.json").write_text(
            json.dumps({"android": {"ndk": "27.3.13750724"}}), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "pins differ"):
            build_jni.configuration(self.repo)

    def test_unresolved_or_unknown_abis_do_not_silently_reduce_build(self):
        for declaration in ("$(RELEASE_ABIS)", "armeabi-v7a mips", "arm64-v8a arm64-v8a"):
            with self.subTest(abis=declaration):
                (self.repo / "app/jni/Application.mk").write_text(
                    f"APP_ABI := {declaration}\n", encoding="utf-8")
                with self.assertRaisesRegex(ValueError, "ABI list"):
                    build_jni.configuration(self.repo)

    def test_wrong_ndk_and_missing_headers_fail_before_build_output(self):
        ndk = self.root / "ndk"
        ndk.mkdir()
        output = self.root / "objects"
        (ndk / "source.properties").write_text("Pkg.Revision = 27.3.13750724\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "different revision"):
            build_jni.build(self.repo, ndk, output)
        (ndk / "source.properties").write_text("Pkg.Revision = 28.0.13004108\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "including its JNI headers"):
            build_jni.build(self.repo, ndk, output)
        self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
