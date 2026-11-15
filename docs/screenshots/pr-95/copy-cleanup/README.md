# Reviewed copy and hierarchy cleanup

Source `f53266c5a81bec979cd152f624f70b67c144a581`. The user requested fewer words, information levels and confusing disclosures. Two Sol consultations and one Opus recommendation pass preceded implementation; the parent inspected the final native sections. No scores or further review loop.

## Improvements

- Shortened the hero lede while retaining its headline and free/open-source/Android facts.
- About now has one provenance heading, one paragraph and a Source/security link. Removed its nested heading and technical disclosure. The old reproduction caveat was removed from the homepage, not falsely described as moved to README. No reproducibility claim or README edit.
- Shortened all six feature descriptions; retained bold passphrase and lost/stolen benefit, linked-history qualifier, and the exact Why-link. Removed the repeated three-fact recap. Existing centered lead card/grid retained.
- Upcoming has one always-visible explanation with the MollySocket/account-metadata trust requirement and setup/linked-phone work. Explicit notice: “These are plans, not features you can use yet.” All eight sourced item titles/URLs and label-derived badge remain unchanged. Removed disclosure/open-on-Why logic.
- Shorter Matrix/forum titles/descriptions/actions retain separate icons and primary emphasis. Help is secondary, with two direct actions and no h4 tiers. Donations explicitly fund work toward a desktop client.
- Shortened install copy while retaining prerequisites, equal Obtainium/F-Droid primary buttons, sole Recommended badge and secondary manual updates. Verification now links directly to the checked package/signing-fingerprint section.
- Shortened handoff recovery/install/fallback wording. Normal import anchor, Android attempt, URI/filter and manual fallback are unchanged; no new native install was performed.
- Fixed reflow-induced background/text collisions with paint offsets only (-80px wide/-96px phone), and scoped homepage art to #main so handoff headings stay plain. No SVG/native/font/palette/navigation change; symmetric divider geometry and legal line are byte-unchanged.

## Measured reduction

[Counts](word-counts.json) cover rendered words in hero, About, Features, Upcoming, Community and Get. They exclude header/navigation/footer and initially closed disclosure bodies. Whitespace tokens must contain a letter/digit; punctuation-only arrows do not count. Desktop baseline had Upcoming open; phone baseline had it initially closed.

| CSS width | Before | After | Reduction |
|---|---|---|---|
|1280|566|424|142 words (25.1%)|
|390|534|424|110 words (20.6%)|

Content disclosures 2→0; heading nodes 19→16 (h1–h4), maximum heading tier 4→3. The phone Upcoming explanation is now visible rather than hidden, so that individual section grows by 6 words while the page becomes substantially shorter.

## Actual proof

All PNGs are exact-built-source browser renders with embedded original fonts/images and native overlay scrollbars. Recorded viewport width equals usable width. Source/asset fidelity is preserved; these are not claimed to be live-tab screenshots.

| Surface | Light | Dark |
|---|---|---|
|features 1280px|[view](features-1280-light.png)|[view](features-1280-dark.png)|
|about 1280px|[view](about-1280-light.png)|[view](about-1280-dark.png)|
|community 1280px|[view](community-1280-light.png)|[view](community-1280-dark.png)|
|about 390px|[view](about-390-light.png)|[view](about-390-dark.png)|
|upcoming 390px|[view](upcoming-390-light.png)|[view](upcoming-390-dark.png)|
|community 390px|[view](community-390-light.png)|[view](community-390-dark.png)|
|get 390px|[view](get-390-light.png)|[view](get-390-dark.png)|
|handoff 390px|[view](handoff-390-light.png)|[view](handoff-390-dark.png)|
|about 320px|[view](about-320-light.png)|[view](about-320-dark.png)|
|features 768px|[view](features-768-light.png)|[view](features-768-dark.png)|

## Checks and limits

27 site/output-safety tests passed once in 0.141s; current external build and diffcheck pass. 20 rendered frames checked fonts/visible-image readiness and no overflow at exact 320/390/768/1280. All 18 non-CSS asset files and Obtainium JSON hashes are unchanged. [Checks](final-checks.json).

The owned incognito live tab measured 427 CSS px / 411 usable despite a 390 request; [actual synchronous event probe](live-probe.json) confirmed menu opening, anchor close, Escape/focus return and exact Why→#upcoming, with heading below the sticky header. Actual homepage Obtainium CTA reached localhost:8879/obtainium/ with the unchanged generated import URI/manual fallback. These are DOM activation/synthetic key-event checks, not a claim of physical pointer/keyboard transport. Async animation-frame waits in the hidden tab did not return useful results; no success is attributed to them. The temporary tab was closed; the user's preview/server retained.

[Verification destination](verification-target.txt) actually displays im.collie.app and the signing fingerprint at the linked anchor. The Impeccable detector lacked parser modules and ran only regex fallback: its sole warning is the deliberately retained 3px symmetric hero arc. No full automated contrast/accessibility audit is claimed.

## Verified findings

| Finding | Disposition/evidence | Action |
|---|---|---|
| Repeated About/provenance and disclosure tiers | Valid; old source contains separate heading/paragraph/details | One paragraph and source link |
| Repeated lead facts and verbose feature copy | Valid; same promises appeared in paragraph and recap | Concise reviewed descriptions; recap removed |
| Roadmap explanation behind disclosure | Valid; phone defaults closed | Visible exact reviewed paragraph/notice; 8 items retained |
| Community/help repeats and h4 tiers | Valid; source repeats join/forum labels and two subheads | Short actions/copy; h4s removed |
| Short verification route | Valid; target has actual package/signing section | Direct verification link |
| Wordy handoff | Valid; duplicate recovery/explanatory text | Short wording; normal link preserved |
| Reflow bands crossed text | Valid; actual first captures | Paint offset; all SVG bytes preserved |
| Handoff inherited homepage art | Valid; actual capture showed heading crossings | Scope artwork to homepage#main |
| Detector hero-border warning | Intentional; user pinned symmetric arc | Retained unchanged |
