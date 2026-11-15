# Source observations before underfill repair

These source colour/landmark measurements describe the accepted ink and semantic regions. The old underfill segment counts below are superseded by README.md and underfill-boundary.json; the accepted ink geometry and gradients were retained unchanged.

# Collie mural asset handoff

## Canonical asset

- `collie-mural.svg` — standalone vector, transparent, source-coordinate paths, tight padded `viewBox="0 1008 770 341"`.
- `collie-mural.png` — transparent native-size render, 770×341 px.
- `collie-mural-paper.png` — same viewBox on flat `#F8F9F0` paper for inspection.
- `collie-mural-registered.svg` / `collie-mural-registered.png` — same paths in the comparison frame `viewBox="0 1000 770 350"`; the registered PNG is 770×350.

The contours register to absolute coordinates from the selected 1024×1536 source raster. Each Potrace group converts its 10× bottom-origin path coordinates into native source pixels with `translate(0 1350) scale(0.1 -0.1)`; the asset itself is not rescaled. The canonical frame has 2 px of empty vertical padding above the rounded crown and below the orange lobe. Its horizontal limits are intentional: `x=0` is the source canvas edge and `x=770` is the phone foreground boundary. The asset includes visible mural contours and measured interior fills, but no screenshot background, text, UI, phone, or phone-screen artwork. It makes no continuation behind the phone.

## Reference and proof

- `source-lower-1024x536.png` — exact source crop at `(0,1000)`, unscaled.
- `foreground-exclusion-mask.png` — 1024×536 mask for page foreground/UI excluded from the mural fit.
- `source-olive-mask.png`, `source-orange-mask.png` — selected source-color masks, registered to the 770×350 comparison frame.
- `mural-landmark-map.png` / `mural-landmark-map.svg` and `landmarks.md` — source-registered crosshair map and endpoint inventory. Every one of the 36 listed landmarks has a cross and ID at its tabulated source coordinate.
- `mural-potrace-035-paper.png` — full registered 770×350 vector render on paper.
- `mural-potrace-035-registered-overlay.png` — source/vector alpha blend.
- `mural-potrace-035-boundary-overlay.png` — high-contrast source/vector boundary overlay.
- `mural-potrace-035-mask-fit-overlay.png` — source/vector opaque mask comparison.
- `mural-potrace-035-nose-zoom.png`, `mural-potrace-035-paw-zoom.png`, `mural-potrace-035-locks-zoom.png`, `mural-potrace-035-orange-zoom.png` — native source on the left and final tolerance-0.35 vector on the right, magnified for blunt caps and sharp tips.
- `mural-potrace-worst-union-zoom.png` — local source/vector view at the maximum union-mask residual.
- `collie-mural-source-vector-pair.png` — final native source/vector pair.
- `collie-mural-v5-body-tone-seam-zoom.png` and `collie-mural-v5-body-tone-seam-samples.md` — 8× registered detail and exact source/vector pixel grid across the olive separator.

## Tracing method and fit

Potrace 1.16 traced the audited binary masks with `--svg --opttolerance 0.35 --alphamax 1 --turdsize 0 --unit 10`; its 10× path coordinates are transformed back to the native source pixel frame. Geometry is filled contours, including the broad orange lobe and olive stroke widths. The eye is one source-fitted circle; the detached cheek mark is its own filled contour.

Expanded path-segment counts are 132 olive cubic + 28 olive line segments, 22 orange cubic + 8 orange line segments, 4 cheek cubic segments, and one eye circle. Total: 158 cubics, 36 lines, one circle. The baseline tolerance 0.2 had 180 cubic segments; the 0.35 result removes 22 while keeping the reviewed caps, lock points, paw split, and orange forks visually aligned.

The registered 0.35 opaque-union fit is IoU 0.9636 with symmetric boundary mean 0.21 px, p95 1.00 px, max 3.83 px. Olive including face marks is IoU 0.9539, mean 0.20 px, p95 1.00 px, max 2.00 px. Orange is IoU 0.9817, mean 0.18 px, p95 1.00 px, max 2.41 px. These are comparisons to the thresholded visible source masks; hidden artwork is not scored.

The 4.83 px maximum from the earlier 0.2 union comparison occurs at `(543,1255)` in a narrow green/orange transition. The source pixel there is orange `#CD8136`; adjacent hue-mask gap pixels are mixed colors (`#B19154`, `#AA975F`, `#AB9964`, `#AF9A64`, `#B49D64`), while nearby clean paper is `#FCFAEC`. This is antialiased mixed ink rather than clear cream space. Color-separated boundary checks remain below 2.42 px. No path was moved to chase this threshold artifact.

## Source color details

The final asset adds three source-derived fill masks behind the accepted contours: head/mane (`9879` core pixels, bounds `(513,1016)–(680,1210)`, source median `#FCF4DF`), cool chest/body (`11786`, `(580,1160)–(769,1265)`, `#FBF5E6`), and lower peach space (`9970`, `(505,1242)–(769,1328)`, `#FCF2DC`). The upper fill reaches the source olive wave's upper edge; the peach fill begins below its lower edge. The per-column wave boundary follows source olive pixels from `x=500` through `x=768`; the last visible boundary is carried one pixel to `x=769` at the phone crop. The orange lobe and accepted olive contour are drawn over these fills.

Both `userSpaceOnUse` gradients are explicitly mapped through `translate(0 1350) scale(0.1 -0.1)`, where `x_source=x_path/10` and `y_source=1350-y_path/10`. The olive tone runs from path coordinates `(7700,3400)` (source `(770,1010)`) to `(0,500)` (source `(0,1300)`), matching the darker head and brighter lower-left ribbon. The shared cool head/chest tone runs from `(0,3340)` (source y `1016`) to `(0,900)` (source y `1260`). The lower space uses measured flat `#FCF2DC`, kept below the olive separator so it does not tint the cool body field. Orange ink remains near-flat `#D8883A`; no glow or texture is added.

Eroded source/render core RGB medians: head olive `#9BA377` / `#9BA277`; ground ribbon `#A1A87D` / `#A1A87D`; cheek `#98A375` / `#98A375`; eye `#8A956A` / `#8A956A`. The exact eye-center source pixel is `#8B9769`, while the rendered circle is `#8A956A`; the 57-pixel eroded source core matches. Source/render 7×7 fill medians: head at `(625,1090)` `#FCF3DE` / `#FCF3DE`; chest at `(655,1200)` `#FBF5E5` / `#FBF5E5`; cool body at `(700,1240)` `#FAF6E7` / `#FAF6E7`; peach below the wave at `(630,1255)` `#FCF2DC` / `#FCF2DC`; peach core at `(650,1270)` `#FDF2DC` / `#FCF2DC`.

The source does not support a separate darker nose pad: the eroded bulb core `(733,1072)–(750,1091)` is `#9BA276`, while nearby profile core `(710,1072)–(735,1091)` is `#9BA277`; rendered medians are `#9BA277` in both. The accepted rounded nose path remains unchanged. Registered PNG corners and an exterior sample have alpha 0.

Accepted ink geometry was compared with `mural-potrace-035.svg`; all path data strings and eye center/radius are byte-identical. Segment counts below count expanded path segments, including repeated coordinate groups, rather than SVG command letters:

| Layer | Paths | Cubic | Line | Closepath |
|---|---:|---:|---:|---:|
| Olive main | 1 | 132 | 28 | 1 |
| Orange lobe | 1 | 22 | 8 | 1 |
| Cheek | 1 | 4 | 0 | 1 |
| Eye | circle | — | — | — |
| Head fill | 3 | 145 | 2 | 4 |
| Chest fill | 4 | 216 | 8 | 40 |
| Lower peach fill | 1 | 80 | 4 | 4 |

The olive main, orange lobe, and cheek path-data SHA-256 values are `6ab06bbf5f2c0f50de51ba3e405b8fa2521f702ec48a90c7ea27585db964ed1a`, `25dd1f421e239a13d7ca7b7879248cc3af0560d4a85451b1d8cc09d40930ac22`, and `b9ae37528e2af9d2dade5ecdd30a1f07e52eff48dc3923d3b837544656876e6a`. The eye remains centered at `(664.42,1069.708)`, radius `5.58`.

The corrected landmark inventory classifies H3 at `(767,1248)` as a tapered upturn next to the phone shadow. The native source/vector zoom supports that label; no path change or hidden continuation was made.
