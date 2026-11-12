# Current Collie website review set

These are actual T3 browser captures from the live static build at `http://localhost:8879/`, using an owned incognito tab. They replace the earlier images in this directory. Review the commit containing this document as one stable candidate; implementation source is `9e4cddf931c6`, with the fresh native assets in `555fff83a6b7e8f5b8de89f522859f250a166b5f`. No score is supplied by the implementation owner.

## Start with these views

| View | Screenshot | CSS viewport |
| --- | --- | --- |
| Hero and fresh encryption screen | [desktop-light.png](desktop-light.png) | 1280 × 800 |
| Foreground phone on mobile | [mobile-light.png](mobile-light.png) | 390 × 844, scrolled to phones |
| Compact feature grid | [features-desktop-light.png](features-desktop-light.png) | 1280 × 1000 |
| Mobile features | [features-mobile-light.png](features-mobile-light.png) | 390 × 1100 |
| Curated upcoming plans | [upcoming-desktop-light.png](upcoming-desktop-light.png) | 1280 × 900 |
| Upcoming plans in dark mode | [upcoming-mobile-dark.png](upcoming-mobile-dark.png) | 390 × 1100 |
| Persistent menu, closed | [menu-mobile-closed.png](menu-mobile-closed.png) | 390 × 844 |
| Persistent menu, open | [menu-mobile-open.png](menu-mobile-open.png) | 390 × 844 |

All captures use device pixel ratio 2; PNG dimensions are twice the CSS viewport dimensions. Supporting captures cover the consolidated introduction ([desktop](about-desktop.png), [mobile](about-mobile.png)), community ([desktop light](community-desktop-light.png), [mobile dark](community-mobile-dark.png), [320px dark](community-narrow-dark.png)), dark hero ([desktop](desktop-dark.png), [mobile phones](mobile-dark.png)), and the [actual Obtainium handoff](obtainium-desktop-light.png).

The browser capture pipeline can shift orange into pink despite normal computed CSS colors; this discrepancy was observed in exported PNG pixels as well as the inline viewer. Do not adjust source colors to compensate. Layout, reading order, content, overlap and navigation remain assessable. The native PNG itself has the app's actual orange pixels. Contrast values below come from computed CSS, not color sampling of these browser images.

## Product requirements to preserve

Collie is an Android messenger for ordinary Signal users. Explain familiar messaging and extra protection on the phone; do not pitch readable patches as a benefit to everyone. Existing factual copy is mostly approved. People skim, so grouping, type hierarchy, emphasis and actionable labels should communicate the story before paragraph reading. Preserve the bark, lichen and orange identity, Manrope and Sora, real screenshots and the logo-derived dashed ring. Decorative radial-gradient blobs were explicitly rejected and removed.

The introduction immediately below the hero consolidates Signal compatibility and Signal/Molly foundations. A text-only rearrangement and a competing provenance card did not satisfy the request for informational clarity. The current open composition puts its topic heading on the left, benefit first on the right, then quieter foundations and one optional source/security disclosure. Mobile follows that DOM order. Do not reintroduce Side by side, package-name, or one-account/two-app explanations. The legal disclaimer stays unchanged in the footer.

Matrix chat and the GitHub Discussions forum remain prominent entry points, with different chat-bubble and thread-list icons. Their actions are underlined. Bug reports and donations remain in the separate Help the project group. Keep selective bold and italic emphasis. The notifications card must retain the exact link text “Why it matters and why we're working on making it easy.” pointing to `#upcoming`.

Plans come from the existing `upcoming-planned-features` Discussions category, refreshed by daily builds, with label-priority items first. They must remain clearly labelled planned rather than shipped. The checked snapshot has ten discussions, eight in that category; no roadmap items were invented. Obtainium and the F-Droid repository have equal primary styling, with Recommended only on Obtainium. Manual APK download remains secondary. README uses the absolute Collie-hosted handoff; the website uses relative `obtainium/` so clicking the real CTA stays on the live build before publication. On the published domain it reaches the same destination. Preserve GitHub as the update source and `https://collie.im/#get` as the manual fallback. The verified nested-JSON APK filter is unchanged.

## Changes in this candidate

The roadmap renderer maps stable discussion numbers to concise display titles. Unknown titles retain their wording after removing editorial prefixes such as High Priority or bracketed tags. Escaping happens after display-title selection. A separate Priority badge comes only from the label, never title text. All eight discussion URLs are unchanged. Priority title weight is 600; ordinary plan titles are 500. The two-column grid reads across rows; mobile uses one column. The notifications introduction and italic planned-work disclaimer are unchanged. The gap from the roadmap follow-up links to the community heading is now 64 CSS px.

The lead feature retains its desktop two-column/two-row position and featured surface. Its 40px icon sits beside the heading; the approved explanation remains. Three short facts restate that explanation: Passphrase you choose; Locks after a set time; Stays encrypted if lost or stolen. A hairline separates the facts, and their row sits at the bottom. Smaller cards use one deliberate icon-to-heading gap rather than stacked padding and automatic margins. Narrow screens use compact card and section spacing, with stacked facts.

The narrow header is now a page-level sibling of the hero, so hero overflow cannot prevent persistent navigation. At 860px and below it is 56px high and sticky, with brand, Menu and Get Collie. Menu exposes the existing five section links in desktop order. Enter opens it; Escape closes it and returns focus; choosing a link or clicking outside closes it. Anchored sections have 56px scroll margin. The desktop header keeps its existing behavior. There is no menu animation to suppress in reduced-motion mode; existing reduced-motion rules also disable smooth scrolling and hero motion.

## Relevant implementation excerpts

```html
<div class="site-header">
  <header class="top shell">
    <!-- Existing brand -->
    <button class="menu-toggle" type="button" aria-expanded="false" aria-controls="section-menu">Menu <!-- SVG chevron --></button>
    <nav id="section-menu" aria-label="Main">
      <!-- Features, About Collie, Upcoming features, Community, Get Collie -->
    </nav>
    <a class="nav-get mobile-get" href="#get">Get Collie</a>
  </header>
</div>
<!-- The hero starts after this page-level header. -->
```

```css
.feature-head{display:flex;align-items:flex-start;gap:16px}
.feature-head h3{max-width:17ch}
.feature-facts{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:20px;margin-top:auto;padding-top:20px;border-top:1px solid var(--rule);font-size:.9rem;line-height:1.5;font-weight:500}
.plan-badge{flex:none;padding:3px 9px;border-radius:999px;background:var(--soft);color:var(--bark);font:600 .75rem/1.5 Manrope,sans-serif}
@media(max-width:860px){
  html{scroll-padding-top:0}
  main>section{scroll-margin-top:56px}
  .site-header{position:sticky;top:0}
  .top{position:relative;height:56px;padding-block:0;gap:12px}
  .top nav{display:none}
  .top nav.is-open{display:flex}
}
```

The full narrow-menu rule positions the solid panel immediately below the header; it is not a fixed overlay. JavaScript updates `aria-expanded` and the `is-open` class together. Escape invokes the same close function and calls `toggle.focus()`.

```python
priority = is_priority(node)
item_class = ' class="priority-plan"' if priority else ""
badge = '<span class="plan-badge">Priority</span>' if priority else ""
title = html.escape(display_roadmap_title(node))
items.append(f'<li{item_class}>{badge}<a href="https://github.com/{repo}/discussions/{int(node["number"])}">{title}</a></li>')
```

## Measured browser checks

| Actual CSS width | Horizontal overflow | Header | Downloads start, CSS px |
| --- | --- | --- | --- |
| 320 | none; document width 305 | 56px sticky | 6046.25 |
| 390 | none; document width 375 | 56px sticky | 5420.30 |
| 768 | none; document width 753 | 56px sticky | 4242.32 |
| 1280 | none; document width 1265 | 90px normal | 3684.98 |

These checks used actual `innerWidth`, not only the requested viewport setting. The narrower document widths reflect the vertical scrollbar. At 390px, downloads start 468.34px earlier than the previous 5888.64px. At 1280px the lead card is 408px tall, compared with 482.39px before this pass. Paragraph-to-facts space is 62.5px; the three lower-row heading tops all measure 1839.70px from the document top. At 390px the lead is 394px tall.

Native keyboard Tab from the brand reached Menu, Enter changed `aria-expanded` to true, and Escape restored false with focus on Menu. Outside-click closing passed. Each of the five actual menu links was clicked at both 390px and 768px. Every menu closed, and all headings landed below the header: heading tops ranged from 88.01px to 134.22px, with the header bottom at 56px. These position checks temporarily used automatic scrolling in the owned test tab to avoid measuring a smooth-scroll midpoint; source motion rules were unchanged.

Selected computed CSS contrast ratios were measured with the WCAG sRGB luminance formula. Light mode: badge 14.20:1, menu links 15.69:1, lead facts 14.20:1. Dark mode: badge 14.20:1, menu links 16.55:1, lead facts 8.01:1. These are checks of the new elements, not a whole-site automated contrast audit. The Impeccable detector used a degraded regex fallback because parser modules are unavailable. Its retained warning is the existing 3px curved hero boundary; no clean automated audit is claimed.

Clicking Add to Obtainium originally left localhost for the undeployed absolute route and showed GitHub Pages' not-found page. After making the website link relative, clicking that same CTA opened `http://localhost:8879/obtainium/`; the page text, Open Obtainium URI, download link and manual homepage fallback were read. Actual Obtainium installation/import on a phone was not performed.

## Fresh native image evidence

The [Fastlane PNG](../../../fastlane/metadata/android/en-US/images/phoneScreenshots/3.png) and [website WebP](../../site/assets/screens/encryption.webp) now come from the same newly captured API 35 screen. The old branch Fastlane image was visibly stale: its title overlapped the camera area and its content sat high. Merely moving an existing image to the front did not correct that file. See [encryption-capture.md](encryption-capture.md) for commands, source, hashes and security restoration.

The fresh PNG is 1080×2400; WebP is 540×1200. In the actual user-visible screen, the title's view begins at y578, well below the 128px status-bar inset. The final button ends at y1887, above the 63px navigation inset. Therefore free space is exactly 450px above and below. Title, lock circle, body and both controls are visibly centered and unobscured. The browser's loaded `currentSrc` and no-store fetch match the new WebP hash. Encryption remains the foreground hero phone, lock is left and proxy is right. No conversation image was fabricated.

## Validation and reproduction

```sh
python3 tooling/site/build.py --out /home/x/agent-scratch/collieim-android/pr-screenshots/site --tag v8.35.1-3-rc2 --roadmap-json /home/x/agent-scratch/collieim-android/pr-screenshots/roadmap-nodes.json
python3 -m unittest discover -s tooling/site -p test_build.py
```

Fourteen website tests pass, including five added checks for stable mappings, prefix cleaning, mapped-title escaping, unknown-title preservation and label-only badges. Existing category, ordering, injection, pagination, failure, safe-path and Obtainium checks remain. `git diff --check` passes. The actual native centering method passed without skip, and the screen-security restoration fixture passed. Temporary app test helpers, generated Android build directories, the dedicated emulator and its AVD were removed after evidence collection. The live Python preview remains running by request.

## Remaining review questions

Mobile hero length remains deliberately outside this pass: its copy appears before the phones, so the fresh image is below the first fold on narrow screens. Community remains lower in the page but has hero and persistent-menu entry points. Six feature cards remain the established topology. The notifications explanation is technical because it states real setup requirements, and the user asked to preserve that paragraph. No real conversation capture was supplied. Review these as honest remaining design tradeoffs; do not infer approval from passing tests or from this implementation note.

Impeccable 4.1.1 was already installed for Codex under `~/.agents/skills/impeccable`. Its context, layout and craft-floor guidance was used. Canonical documentation confirms that path and `$impeccable`; nothing was installed or downloaded. Source, imagery and behavior are ready for independent review at the containing commit.
