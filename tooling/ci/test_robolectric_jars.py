"""Check that CI prefetches the SDKs selected by Robolectric host tests."""

from pathlib import Path
import re
import subprocess
import tomllib
import unittest


ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "tooling/ci/robolectric-jars.txt"
# android-all-instrumented coordinates from Robolectric 4.16 DefaultSdkProvider.
JAR_BY_SDK = {
    23: "6.0.1_r3-robolectric-r1-i7",
    24: "7.0.0_r1-robolectric-r1-i7",
    25: "7.1.0_r7-robolectric-r1-i7",
    26: "8.0.0_r4-robolectric-r1-i7",
    27: "8.1.0-robolectric-4611349-i7",
    28: "9-robolectric-4913185-2-i7",
    29: "10-robolectric-5803371-i7",
    30: "11-robolectric-6757853-i7",
    31: "12-robolectric-7732740-i7",
    32: "12.1-robolectric-8229987-i7",
    33: "13-robolectric-9030017-i7",
    34: "14-robolectric-10818077-i7",
    35: "15-robolectric-13954326-i7",
    36: "16-robolectric-13921718-i7",
}
CONFIG = re.compile(r"@(?:org\.robolectric\.annotation\.)?Config\s*\(([^)]*)\)")
SDK = re.compile(r"\bsdk\s*=\s*(\[[^\]]*\]|\{[^}]*\}|[^,]+)")
HOST_TEST_SOURCE = re.compile(r"(?:^|/)src/test[^/]*/")


class RobolectricJarsTest(unittest.TestCase):
    def test_prefetch_covers_explicit_and_default_sdks(self):
        jars = MANIFEST.read_text().splitlines()
        known_sdks = set(JAR_BY_SDK.values())
        self.assertEqual(set(jars) - known_sdks, set(), "Unknown Robolectric jar versions")
        self.assertEqual(len(jars), len(set(jars)), "Duplicate Robolectric jar versions")

        versions = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text())["versions"]
        required = {int(versions["targetSdk"])}
        # Read tracked Java and Kotlin sources, including flavored host test source sets.
        paths = subprocess.check_output(
            ["git", "ls-files", "-z", "--", "*.java", "*.kt", "*robolectric.properties"], cwd=ROOT,
        ).decode().split("\0")
        for name in paths:
            if not HOST_TEST_SOURCE.search(name):
                continue
            path = Path(name)
            source = (ROOT / path).read_text()
            if path.name == "robolectric.properties":
                values = re.findall(r"^\s*sdk\s*=\s*(.+)$", source, re.MULTILINE)
            elif "org.robolectric.annotation" in source:
                values = [value for config in CONFIG.findall(source) for value in SDK.findall(config)]
            else:
                continue
            for value in values:
                entries = value.strip().strip("[]{}").strip().rstrip(",").split(",")
                for entry in entries:
                    self.assertRegex(entry.strip(), r"^\d+$", f"Unrecognized Robolectric SDK in {path}")
                    required.add(int(entry))

        missing = sorted(sdk for sdk in required if JAR_BY_SDK.get(sdk) not in jars)
        self.assertEqual(missing, [], f"Add Robolectric jars for SDKs {missing} to {MANIFEST.name}")
