# Phone features, Donate row and section-anchored artwork

Before = last published head `21532f966a5`, built from that commit. After = this branch.
All images are headless Chromium renders of the built site (`tooling/site/build.py --tag v8.35.1-3-rc2`)
at the exact CSS width in the file name. Section crops render the sticky header in place
so it does not cover the section.

| View | Before | After |
|---|---|---|
| Phone features, 320px | [before](before-features-320-light.png) | [after](after-features-320-light.png), [dark](after-features-320-dark.png) |
| Phone features, 390px | [before](before-features-390-light.png) | [after](after-features-390-light.png) |
| Community + Donate, 390px | [before](before-community-390-light.png) | [after](after-community-390-light.png), [dark](after-community-390-dark.png) |
| Community + Donate, desktop | [1280 before](before-community-1280-light.png) | [1280 after](after-community-1280-light.png), [1440 dark](after-community-1440-dark.png) |
| Orange band below features, 1280px | [before](before-band-1280-light.png) | [after](after-band-1280-light.png) |
| Header at 200% text, 390px | — | [closed](header-200pct-390-light.png), [menu open](menu-open-200pct-390-light.png) |

## Measured checks (this branch)

- Horizontal overflow: none at 320, 375, 390, 430, 768, 1024, 1280 and 1440px in light and dark,
  and none at 320, 390 and 768px with 200% root text size.
- Orange band below Features, rasterised at its painted position and compared with the actual
  text line boxes: at least 52px clear of "Also included" and the Upcoming heading at every
  tested width from 601 to 1920px; band ink stays inside its crop (no cut ends).
- Mural vs Community: no text line or link underline (line box + 4px) meets mural ink at 320, 375,
  390, 430, 601, 700, 768, 861, 1024, 1280, 1440 and 1920px; light and dark at 320/390/768/1280.
- Header: 56px at normal size on phones. At 200% text it wraps (Get Collie moves to a second row),
  Menu stays 44px tall, the menu opens directly below the bar, choosing a link closes it and the
  section heading lands below the sticky bar, and Escape closes it and returns focus to Menu.
- 27 site/output-safety tests pass.

Limits: headless renders and computed geometry only; no physical device, browser-zoom (as opposed
to root text size) or full accessibility audit.
