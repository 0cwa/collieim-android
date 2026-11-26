#!/usr/bin/env python3
"""Checks for generate.py that do not need the Material AAR: spec validity, pins and rendering."""

from pathlib import Path
import re
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))

import generate  # noqa: E402


def fake_roles():
    return {role: f"#FF{index:06X}" for index, (role, _, _) in enumerate(generate.ROLES)}


class GenerateTest(unittest.TestCase):

    def test_spec_is_valid_and_matches_the_app_pin(self):
        spec = generate.load_spec()
        version = generate.pinned_material_version()
        self.assertEqual(f"com.google.android.material:material:{version}", spec["material"])
        self.assertRegex(generate.pinned_aar_sha256(version), r"^[0-9a-f]{64}$")

    def test_rendering_is_deterministic_and_covers_every_role(self):
        palette = generate.load_spec()["palettes"][0]
        palettes = [(palette, {"light": fake_roles(), "dark": fake_roles()})]

        xml = generate.render_xml(palettes)
        self.assertEqual(xml, generate.render_xml(palettes))
        for brightness in ("Light", "Dark"):
            style = re.search(
                rf'<style name="ThemeOverlay\.Collie\.Palette\.{palette["style_name"]}\.{brightness}" parent="">(.*?)</style>',
                xml,
                re.S,
            ).group(1)
            items = re.findall(r'<item name="([^"]+)">', style)
            expected = ["collie_palette"] + [a for _, attrs, _ in generate.ROLES for a in attrs] + [a for a, _, _ in generate.APP_BINDINGS]
            self.assertEqual(expected, items)
            # Roles only: window, inset and system bar policy stays with each Activity's style.
            self.assertFalse([i for i in items if i.startswith("android:window") or i.endswith("BarColor")])

        compose = generate.render_compose(palettes)
        self.assertEqual(compose, generate.render_compose(palettes))
        mapped = [c for _, _, c in generate.ROLES if c]
        self.assertEqual(2 * len(mapped), sum(compose.count(f"      {c} = ") for c in set(mapped)))
        self.assertIn(f'"{palette["id"]}" ->', generate.render_overlays(palettes))

    def test_contrast_ratio(self):
        self.assertAlmostEqual(21.0, generate.contrast_ratio("#FF000000", "#FFFFFFFF"), places=3)
        self.assertAlmostEqual(1.0, generate.contrast_ratio("#FF777777", "#FF777777"), places=3)


if __name__ == "__main__":
    unittest.main()
