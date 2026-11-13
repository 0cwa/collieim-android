# Final A landscape refinement

Signed source `50baa15da767612557c63293b7f6548215510c02` changes only the forest lower return in landscape-page.svg. [Selected A raster](../background-a-flowing.png). The fitted hero/arch and all deliberate production placements are retained.

## Same-frame comparison

[Before contour](features-before-boundary-overlay.png) · [refined contour](features-boundary-overlay.png) · [native 4× exposed exit before/after](exposed-exit-before-after.png) · [complete production forest/arch](forest-isolated.png). Magenta is the vector boundary; raster foreground is not reconstructed or scored.

The feature reference crop is(0,480,1024,650), authoring viewBox(0,-20,1024,650): sourceY=authoringY+500. Production translations(80,370) for the arch and(0,370) for the green sweep remain separate. The hero-upper proof used identity coordinates in crop(0,0,1024,516); it revealed no material deficiency, so those paths were preserved. [Registration](registration.json).

The old exposed forest lower exit was 80–95px early at the right and 24–61px too low at selected left columns. The refined boundary tracks the exposed source-core rows within approximately 3px. These are local colour-core samples, not a whole-image geometric accuracy claim. The phone-covered bridge remains inferred.

## Complete motif inventory and limits

| Source motif | Production disposition |
|---|---|
| Upper hero lichen/copper/orange layers and negative gaps | Existing registered contours retained |
| Lower hero forest/lichen/copper/orange sweep | Retained; deliberate lower-left clearance adaptation unchanged |
| Feature lichen arch, cream gap and orange companion | Retained; fitted contour/placement unchanged |
| Rising lichen/forest sweep | Upper edge retained; verified lower-return correction only |
| Lower warm sweep/return and spacious cream reading islands | Existing sparse gold/pale page-edge and gap ribbons retained as layout adaptation; no claim of tracing the AI inset-driven lower-page arrangement literally |
| Phone landscape | Existing varied mobile field retained; separate responsive adaptation, not a stretched desktop sheet |

The closed forest contour now has 5 cubics and 7 distinct endpoint anchors, compared with 4 cubics before. Its lower return has 3 cubics / 4 anchors. Joins at(720,615) and(310,555) are G1 (direction-continuous), not parametric C1; measured direction differences are below 0.000001 degree. The inferred bridge has no new kink, seam, wedge or cropped stub.

## Actual production proof

Every image is an exact-built-source browser render at the stated CSS width ×1200px, with embedded original fonts/images and overlay scrollbars. ON/OFF pairs share scroll coordinates. Hidden mode removes foreground including reading surfaces. These are not live-tab screenshots or a reconstructed page.

| Width | Light ON/OFF | Dark ON/OFF |
|---|---|---|
| 1280 | [ON](production-1280-on-light.png) / [OFF](production-1280-off-light.png) | [ON](production-1280-on-dark.png) / [OFF](production-1280-off-dark.png) |
| 768 | [ON](production-768-on-light.png) / [OFF](production-768-off-light.png) | [ON](production-768-on-dark.png) / [OFF](production-768-off-dark.png) |
| 390 | [ON](production-390-on-light.png) / [OFF](production-390-off-light.png) | [ON](production-390-on-dark.png) / [OFF](production-390-off-dark.png) |
| 320 | [ON](production-320-on-light.png) / [OFF](production-320-off-light.png) | [ON](production-320-on-dark.png) / [OFF](production-320-off-dark.png) |

## Scoped verification

[Checks and actual pixel samples](checks.json): 132 foreground rectangles identical; fonts/visible images ready; no overflow at all four widths. Phone images before/after have zero differing RGB channels in both themes, because the corrected desktop SVG is inactive below 600px. Actual text/background minima in the 768/1280 feature views: body 8.186:1 light/8.855:1 dark; links 7.885:1 light; heading 7.937:1 dark. Opaque card reading surfaces remain intact; the moved band does not compromise text. This is not a full-site contrast audit.

External scratch build, all 7 SVG parses, changed-SVG rasterization and diffcheck pass. The prior 27 site/output-safety tests remain applicable and were not rerun for this path-only change. HTML/CSS and the exact symmetric divider are byte-unchanged. The canonical mural SHA remains f5f041c9feb0d6275fd3d0ade6f4a383da52e256850ec8713bf0bdcf00e6c159. No app/native/main change.
