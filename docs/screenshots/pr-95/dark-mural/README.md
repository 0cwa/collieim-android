# Monochrome dark-site mural

Signed source `899b95afd57cfec89d8b38458737c896351c5652` implements the user's request to remove colour fields from the dark-site mural. [Standalone on bark](standalone-bark.png) · [desktop](community-1280-dark.png) · [phone](community-390-dark.png) · [reusable dark SVG](../../../site/assets/art/collie-mural-dark.svg).

This is an explicitly labelled theme adaptation, not a correction or replacement of the source-faithful full-colour canonical SVG. Its exact three accepted ink paths and eye circle are preserved, including cheek geometry, transforms and native viewBox 0 1008 770 341. The semantic head/chest/peach underfill groups and gradients are absent. Existing olive/orange contours and eye/cheek all use one flat #B7C597 fill; interiors/exterior are transparent. No redraw, retrace or layout/copy/native change.

The standalone asset is full strength. Website dark presentation uses normal compositing at 0.3 opacity for readable background linework; the previous soft-light rule is removed. Automatic prefers-color-scheme selects the new URL. There is no explicit theme-toggle UI to test. Computed browser [theme selection](theme-selection.json) confirms dark adaptation/.3/normal versus light canonical/.65/normal.

[Geometry/hashes](geometry.json): canonical remains f5f041c9feb0d6275fd3d0ade6f4a383da52e256850ec8713bf0bdcf00e6c159; dark adaptation is a2491f5582668952b0270692f47739427fec644fd8e53befd144e1dc780e47fa. The light canonical file, light CSS paint and positions are unchanged. Both 1280/390 light Community [before/after comparisons](light-comparison.json) have zero differing RGB channels.

Actual body/link core-text/background pixels in the 1280 dark mural footprint have minimum 4.821:1 across 2472 pixels; [sample coordinates/RGBs](contrast.json) state the limited scope, not a full-site audit. Parent inspected/accepted standalone and both dark website views.

Focused validation: real external site build, one existing site-build smoke test (1 collected / 1 passed), all 8 art SVG parses, changed-SVG rasterization and diffcheck pass. Rendered fonts/images are ready and no overflow at 1280/390. Actual HTTP canonical/dark SVG bytes match their source hashes. The prior 27-test suite was not repeated for this colour-only adaptation. Existing live 8879 server retained.

Images are actual exact-built-source browser renders using original fonts/images and native overlay scrollbars; standalone is rsvg-convert on #24130D. No recolouring of proof PNGs.
