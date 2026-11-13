**Superseded fit evidence:** [current registered refinement and interactive comparison](refinement/README.md). The original fixed-alpha method below was insufficient; its renders precede the measured corrections.

# Implemented flowing landscape and lower Collie murals

Current production source: signed `40027033fabf87a374658d226393168c364193f6`; flowing-landscape checkpoint: signed `2699aa2d12709fc0a590d480ae10ff7f59fe8851`. [Comparison board](comparison.html) includes actual desktop/mobile renders, selected A/C raster references and same-coordinate fitting overlays. References are image-generated proposals; production backgrounds are hand-authored SVG, never raster substitutes.

## Scope and fit

Only decorative CSS/background assets changed. HTML, approved copy, foreground layout, fonts, native phone images, card geometry and the lowered symmetric arc are unchanged. The landscape is dominant; the recognizable Collie appears only in lower-page whitespace. Backgrounds have no focus/pointer targets; SVGs are aria-hidden. Light/dark colors use flat fills and strokes, without gradients.

A fitting: reference crop `(0,0,1024,516)`. Initial right-edge lichen/orange landmarks were 14/32px high; the left ribbon was 20px too thick. Handle adjustments refined the silhouette and smooth joins. The production lower-left ribbon sits 24px lower to clear actual facts: the AI reference’s shifted foreground is not a tracing target. `hero-production-overlay.png` overlays actual production paths at45% alpha in the same reference coordinates.

C fitting: lower reference crop `(0,1000,800,360)`. Three paths and one eye circle describe muzzle, ear, mane, body and paws. Moving the inner mane anchor from `(551,157)` to `(558,145)` and its neighboring handle from `(534,184)` to `(537,180)` removed an unintended hook. `collie-overlay-refined.png` uses60% alpha. Initial/refined fit SVGs retain the comparison geometry. Production placement moves the desktop profile20px down and96px left into existing whitespace; phone frieze scaling adds8px clearance from the unchanged donation action. Neither adjustment changes the landscape or foreground.

A:23 paths,61 cubic segments,109 anchor endpoints across four responsive SVGs (3542 bytes). C:3 paths,24 cubic segments,35 anchor endpoints plus one circle. Counts exclude control handles. Curves are purposeful C/S Béziers, not autotraced nodes.

## Verification and capture provenance

Fourteen site-generation tests passed after the final CSS change; `git diff --check` passed. All five SVG assets parsed and rasterized successfully. At320/390/768/1280, all33 measured foreground rectangles per viewport exactly matched the pre-background60c772ff baseline, including downstream section positions:132 comparisons, no changes. No horizontal overflow. The original arc CSS is byte-identical;40px cream strips remain uniform RGB249/251/244 at all four widths. Cards retain solid readable surfaces and decoration stays clear of controls.

T3 live preview status was `available:false, visible:false, tabId:null, url:null, title:null, loading:false`. These PNGs are actual browser **source renders**, not live-tab captures: unchanged built HTML/CSS, original image/font bytes embedded in fixed-size iframes through html_preview, DPR1/native overlay scrollbars, requested CSS width equals usable width. Fonts/images were ready. No layout reconstruction or recoloring. Live localhost8879 serves the same current build using the existing no-store/reload server.

Phase1 hero/features renders remain exact current appearance because Phase2 affects only lower sections. Hero sizes:1280×1000,390×1000,320×1000,768×1250; phone montage captures place stage at80px, tablet/desktop begin atpage top. Features:1280×1100/390×1200. Final community:1280×1050 light/dark,390×1400 light/dark,320×1550 light,768×1100 light. Selected measured foreground text contrasts range4.98–9.75:1 for the checked bark/lichen/forest combinations; this is not a full-site automated AA claim.

Existing native-image and Obtainium runtime proof in the parent directory remains applicable. No Android builds, emulators, account/service access, main-branch changes or new dependencies were needed for this implementation. Shared caches, pre-existing worktree and requested8879 server are retained.
