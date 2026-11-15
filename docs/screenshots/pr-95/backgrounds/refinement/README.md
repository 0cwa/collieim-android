# Registered curve refinement

Current production source: signed `888d13834d37f1fb557535a313dd5dca968c8503`. [Interactive comparison](registered-comparison.html) separates authoring registration from production placement. All15 reference/vector/contour assets were decoded successfully in html_preview. Use region + original/refined selectors, shared pan/zoom, native1:1, contour, opacity, wipe or650ms blink. Blue markers are sampled raster boundaries; magenta markers show SVG offsets. No image reconstruction or recoloring.

## What the stronger comparison found

The original crop registration was correct; fixed45%/60% composites obscured shape errors. The upper hero lichen band was too narrow through the middle and too broad at the right. Its copper under-band was missing. The feature arch entered too late and its right sweep had the wrong width. The Collie crest/neck sat too high/left; the lower body sweep and tail also needed handle adjustments.

| Selected exposed boundaries | Before mean / max absolute offset | Refined mean / max | Intersections before → after |
| --- | --- | --- | --- |
| A hero |4.67 /23px|1.03 /3px|30 →30|
| A feature arch/right sweep |37.27 /88px|1.06 /3px|22 →34|
| C crest/muzzle/body/paw/tail |3.09 /12px|1.46 /4px|27 →28|

These are **local sparse samples, not whole-image/global accuracy scores**. Missing original intersections are recorded rather than silently treated as zero. The reference has shaded/tapered fills; the intended SVG uses flat bands and consistent line weight. Text, cards, phones, tonal shading and the orange dog mane are excluded. The manifest lists every x/window/edge correspondence, signed offset and transform; no global alignment was inferred.

Registration: A hero crop `(0,0,1024,516)`; A feature crop `(0,500,1024,580)`; lower C crop `(0,1000,800,360)`. Crop pixels equal authoring viewBox units1:1. Production adaptation remains separate: hero lower-left retains its24px clearance offset; feature arch ribbons translate `(80,370)` and right sweep `(0,370)` into the1024×3072 page background. The80px horizontal placement keeps the real wider heading clear without modifying the fitted authoring curve. Responsive background scaling and mural CSS placement remain unchanged. The production mural uses the same path coordinates with an800×300 viewBox; the authoring fit includes60px extra lower reference space.

## Reproduce and verify

From repository root run `python3 docs/screenshots/pr-95/backgrounds/refinement/compare-registered.py`, then `python3 docs/screenshots/pr-95/backgrounds/refinement/build-registration-board.py`. Existing ffmpeg/rsvg-convert and Python standard library suffice. The analyzer derives the original feature authoring paths from immutable670b216a using the explicitly chosen inverse placement; no foreground raster registration is attempted. Thresholds/windows are in the script/manifest. Generated raster analysis intermediates are disposable.

Production counts: A24 paths/65 cubic segments/116 anchor endpoints across four SVGs; C3 paths/25 cubic segments/36 endpoints plus an eye circle. Compared with the earlier version, one necessary copper band adds a path and the tail’s observed bend adds one cubic segment; feature arch ribbons use four purposeful curves each. No autotracing or dense node generation.

Fourteen site-generation tests, `git diff --check`, and parse/rasterization of all five production SVGs passed after final changes. All33 foreground rectangles at320/390/768/1280 exactly match the original baseline:132 unchanged comparisons, no overflow. HTML, CSS, approved copy, phone images, symmetric divider and responsive layout were not changed. `foreground-final.json` preserves measured rectangles/readiness. The source-render method and unavailable live-tab limitation remain as documented in the parent evidence. Fonts/images loaded; actual iframe widths equal usable CSS widths with DPR1/overlay scrollbars.

Current rendered files here: hero desktop1280×1100, phone390×1100, narrow320×1100, tablet768×1250; features desktop1280×1100, tablet768×1300, phone390×1200; community desktop1280×1050 light/dark and phone390×1400 light. They are actual built-source browser renders, not the selected AI references. The existing8879 live server serves the updated build. No main/native changes or new dependencies.
