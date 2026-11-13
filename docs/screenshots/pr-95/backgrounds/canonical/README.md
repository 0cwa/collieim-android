# One faithful independent Collie mural

Current source: `e8e9f8b05dc889785a325011c37c43d736db2b5f`. This replaces the earlier compact resting redraws and block reading surfaces. The only production Collie asset is [collie-mural.svg](../../../../site/assets/art/collie-mural.svg), SHA256 `f5f041c9feb0d6275fd3d0ade6f4a383da52e256850ec8713bf0bdcf00e6c159`. Its native viewBox is `0 1008 770 341`; no paths, proportions or colours vary across viewports.

The selected raster is [full C](../background-c-collie-mural.png). [Source](registered-source.png), [50% overlay](registered-overlay.png) and [side-by-side](registered-pair.png) share a 770×350 frame at source origin(0,1000). The standalone SVG is770×341 at origin(0,1008), displayed independently, never stretched into the comparison frame. Artwork hidden by the original phone is not invented.

## Native integration and uncovered art

Each file below is a 900px-high browser render of the exact built HTML/CSS with original embedded fonts/images and native overlay scrollbars; not a live-tab capture. CSS viewport and usable width are identical. Native foreground ON/OFF files share scroll coordinates; OFF hides real foreground and its surfaces. The complete proportional image painting box remains, including contours naturally behind Get. Actual viewport-edge cropping is intentional.

| Width | Light | Dark | Uncovered light | Uncovered dark |
|---|---|---|---|---|
| 320 | [ON](320-on-light.png) | [ON](320-on-dark.png) | [OFF](320-off-light.png) | [OFF](320-off-dark.png) |
| 390 | [ON](390-on-light.png) | [ON](390-on-dark.png) | [OFF](390-off-light.png) | [OFF](390-off-dark.png) |
| 768 | [ON](768-on-light.png) | [ON](768-on-dark.png) | [OFF](768-off-light.png) | [OFF](768-off-dark.png) |
| 1280 | [ON](1280-on-light.png) | [ON](1280-on-dark.png) | [OFF](1280-off-light.png) | [OFF](1280-off-dark.png) |

Raw artwork is source-matched and full-strength. Website presentation uses 0.65 opacity in light mode and full-opacity soft-light over the painted bark backdrop in dark mode. There are no glyph halos or rectangular reading mats. Foreground text/fonts/layout, phone imagery and symmetric hero divider remain unchanged. The recognizable face is exposed; natural lower-body occlusion is not claimed to be complete semantic-feature visibility. The old 32-region visibility metric is superseded.

## Underfill topology

[Full bark](standalone-bark.png) · [checker](standalone-checker.png) · [native-aspect paper](standalone-paper.png).

Original source-supported cream fields were repaired inside an enclosure derived from the accepted ink, not redrawn freehand. The 44,888px basin constrains the fill;23 small enclosed holes are repaired and fills overlap 2 source pixels into ink. Accepted olive/orange/cheek path strings and eye XML remain byte-identical. Source gradient stops are unchanged. The underfills use four path elements containing 158 cubic segments:92 head, 26 chest, 40 lower peach (plus 14 line segments).

The intentional 13,579px opening is retained semantically: 13,472 pixels remain exactly alpha 0, and 107 boundary pixels acquire antialias coverage 1–252, all within one pixel of its old boundary. A single alpha 0 corner pixel belonging to that original boundary becomes disconnected; it is not a newly punched cream hole. Native bark/checker inspection found no material crack, pinhole or exterior fringe. This is not a claim of exact whole-image pixel equality.

[Construction](underfill-construction.json) · [boundary/node checks](underfill-boundary.json) · [source observations and landmarks](source-fit-notes.md). To reproduce in task scratch, copy repair-underfill.py.txt to repair-underfill.py together with underfill-baseline.svg.txt, then set POTRACE to official Potrace 1.16 and run python3 repair-underfill.py. The tracer archive was the official potrace-1.16.linux-x86_64.tar.gz, SHA256 158ef1cb6bde9f057510c7aa8b555117be10fa5318cd875f6a26a5811dadc2d8. No tracer binary is committed.

## Verification

27 site/output-safety tests passed before the final image-only replacement; no source/test behavior changed afterward. The external scratch build passed. All seven current art SVGs parse; diffcheck passes. [132 foreground rectangles](foreground-geometry.json) are identical at 320/390/768/1280; fonts/visible images ready and no overflow. Actual HTTP production/study SVG responses match the canonical hash.

[Sampled painted text/background RGB evidence](local-text-contrast.json): forum body/link minima 4.972/4.972 light and5.448/5.454 dark; donation body 4.749 light / 7.904 dark; Help heading 10.117/13.168. These are actual core-glyph pixels at the listed overlap coordinates, not a full-site AA audit or an antialiased-edge contrast guarantee. Existing native app/Obtainium operational proof is unchanged.
