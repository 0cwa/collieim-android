#!/usr/bin/env python3
"""Checks for generate.py that do not need the Material AAR: spec validity, pins and rendering."""

from pathlib import Path
import sys
import unittest
import xml.etree.ElementTree as ET

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

        roles = generate.render_role_xml(palettes)
        app = generate.render_app_xml(palettes)
        self.assertEqual(roles, generate.render_role_xml(palettes))
        self.assertEqual(app, generate.render_app_xml(palettes))

        for brightness in ("Light", "Dark"):
            suffix = f'{palette["style_name"]}.{brightness}'
            role_style_name = f"ThemeOverlay.Collie.PaletteRoles.{suffix}"
            role_style = ET.fromstring(roles).find(f"./style[@name='{role_style_name}']")
            app_style = ET.fromstring(app).find(f"./style[@name='ThemeOverlay.Collie.Palette.{suffix}']")
            self.assertEqual("", role_style.get("parent"))
            self.assertEqual(f"@style/{role_style_name}", app_style.get("parent"))
            role_items = [item.get("name") for item in role_style.findall("item")]
            app_items = [item.get("name") for item in app_style.findall("item")]
            self.assertEqual(["collie_palette"] + [a for _, attrs, _ in generate.ROLES for a in attrs], role_items)
            self.assertEqual([a for a, _, _ in generate.APP_BINDINGS], app_items)
            # Roles only: window, inset and system bar policy stays with each Activity's style.
            self.assertFalse([i for i in role_items + app_items if i.startswith("android:window") or i.endswith("BarColor")])

        compose = generate.render_compose(palettes)
        self.assertEqual(compose, generate.render_compose(palettes))
        mapped = [c for _, _, c in generate.ROLES if c]
        self.assertEqual(2 * len(mapped), sum(compose.count(f"      {c} = ") for c in set(mapped)))
        self.assertIn(f'"{palette["id"]}" ->', generate.render_overlays(palettes))

    def test_every_palette_has_a_matching_translatable_name(self):
        palettes = generate.load_spec()["palettes"]
        self.assertEqual(["blue_tonal_spot", "teal_vibrant", "plum_expressive", "slate_neutral"], [p["id"] for p in palettes])
        self.assertEqual(generate.SCHEMES, tuple(p["scheme"] for p in palettes))
        generate.check_names(palettes)
        renamed = [dict(palettes[0], name="Something else")]
        with self.assertRaises(SystemExit):
            generate.check_names(renamed)
        compose = generate.render_compose([(palettes[0], {"light": fake_roles(), "dark": fake_roles()})])
        self.assertIn("nameRes = R.string.user_palette_name_blue_tonal_spot,", compose)

    def test_pure_black_needs_readable_dark_foregrounds(self):
        palette = generate.load_spec()["palettes"][0]
        # Backgrounds black and foregrounds white, except the accents that are themselves backgrounds of a foreground.
        roles = {role: "#FFFFFFFF" if role.startswith(("on", "inverseOn", "outline")) else "#FF000000" for role, _, _ in generate.ROLES}
        roles.update({"primary": "#FFFFFFFF", "onPrimary": "#FF000000", "inversePrimary": "#FFFFFFFF"})
        generate.check_contrast(palette, "dark", roles)
        roles["outline"] = "#FF222222"
        with self.assertRaises(SystemExit):
            generate.check_contrast(palette, "dark", roles)
        generate.check_contrast(palette, "light", roles)

    def test_contrast_ratio(self):
        self.assertAlmostEqual(21.0, generate.contrast_ratio("#FF000000", "#FFFFFFFF"), places=3)
        self.assertAlmostEqual(1.0, generate.contrast_ratio("#FF777777", "#FF777777"), places=3)


if __name__ == "__main__":
    unittest.main()
