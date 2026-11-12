# Current Collie website review set

Implementation source is signed `b1baba8fbc0906b3470ca3db3fea2591a2200370`; review the containing evidence commit as one candidate. Fresh native assets remain in `555fff83a6b7e8f5b8de89f522859f250a166b5f`. No score is supplied by the implementation owner.

## Current affected views

| View | Screenshot | Actual iframe CSS viewport / PNG dimensions |
| --- | --- | --- |
| Phone features | [features-mobile-light.png](features-mobile-light.png) | 390 × 1100 |
| Phone introduction | [about-mobile.png](about-mobile.png) | 390 × 844 |
| Narrow features | [features-narrow-light.png](features-narrow-light.png) | 320 × 1300 |
| Narrow introduction | [about-narrow-light.png](about-narrow-light.png) | 320 × 844 |
| Unchanged desktop features | [features-desktop-light.png](features-desktop-light.png) | 1280 × 1000 |
| Unchanged tablet features | [features-tablet-light.png](features-tablet-light.png) | 768 × 1300 |

These six are actual browser renders of the existing build HTML, CSS, embedded original fonts and image bytes in a fixed-size iframe via T3 `html_preview`. They are **source renders**, not live-tab screenshots. Only resource URLs are embedded; a capture script waits for fonts and scrolls to the requested existing section. No page layout, content, colors or assets are reconstructed or changed. Visible images reported loaded, fonts reported loaded, and actual iframe widths matched all four requested widths. This renderer uses overlay scrollbars and DPR 1; its usable width equals its viewport width. The last live T3 browser reserved 15px for a scrollbar and used DPR 2. Both measurements are reported separately below. Current and previous-source feature renders at 768 and 1280 are byte-for-byte identical in the same renderer.

## Current remaining key views

| View | Screenshot | Actual iframe CSS viewport / PNG dimensions |
| --- | --- | --- |
| Hero | [desktop-light.png](desktop-light.png) | 1280 × 800 |
| Hero phones | [mobile-light.png](mobile-light.png) | 390 × 844 |
| Upcoming plans | [upcoming-desktop-light.png](upcoming-desktop-light.png) | 1280 × 900 |
| Dark phone plans | [upcoming-mobile-dark.png](upcoming-mobile-dark.png) | 390 × 1100 |
| Community and help | [community-desktop-light.png](community-desktop-light.png) | 1280 × 900 |
| Dark phone community and help | [community-mobile-dark.png](community-mobile-dark.png) | 390 × 1300 |
| Downloads/footer | [light](downloads-footer-desktop-light.png) · [dark](downloads-footer-desktop-dark.png) | 1280 × 1100 |
| Phone downloads | [light](downloads-mobile-light.png) · [dark](downloads-mobile-dark.png) | 390 × 1100 |
| Phone footer | [light](footer-mobile-light.png) · [dark](footer-mobile-dark.png) | 390 × 844 |

These twelve use the same exact-source renderer, native overlay-scrollbar environment and DPR 1 as the six affected views. CSS animations are finished through their native animation API before capture, showing their actual final state. The phone hero is scrolled with the stage top 80px below the viewport top to keep all cases visible beneath the 56px sticky header. Every capture reports the requested actual width, loaded fonts and visible images, expected light/dark media and no horizontal overflow. Built CSS bytes exactly match signed production source.

Exported hero pixels match source colors without recoloring: at (120,590), inside the primary button, RGB 242/152/43 is source #F2982B; at (20,100), bark RGB 36/19/13 is source #24130D. These were sampled from the 1280×800 PNG using `ffmpeg -vf 'format=rgb24,crop=1:1:X:Y' -frames:v 1 -f rawvideo - | od -An -tu1`. Current key review views therefore need no pink/orange capture caveat. Contrast checks below remain computed-CSS measurements with their original scope.

Exact capture-helper source is retained in [render-source.py.txt](render-source.py.txt). It reads the existing static build, embeds original resources, wraps it in a fixed-size iframe and logs readiness/position evidence. Invocation was `python3 render-exact.py WIDTH HEIGHT SECTION`, followed by T3 `html_preview` with the resulting exact-render.html path, matching width and light/dark appearance. PNG data was saved directly without processing. Sections are existing IDs, or hero/phones/footer for their existing class selectors.

Supporting images not listed in either current table retain their actual live T3 captures packaged in c2a7b7c647de24fa50185f84b29fe141aaa6d931: desktop introduction, narrow header, open/closed menu, narrow dark community, dark hero/phones and Obtainium handoff. Their source appearance is unchanged, but their pixels are not relabelled as fresh. Those older exports can shift orange into pink, a discrepancy measured in PNG pixels. Live screenshot/recording transport later timed out across callers and reported an unavailable automation host. Cached Chromium failed before CDP under the host allocator; its installed child-only standard-allocator wrapper was denied by the execution context. No browser was installed and no system security policy changed.

## Product requirements to preserve

Collie is an Android messenger for ordinary Signal users. Explain familiar messaging and extra protection on the phone; do not pitch readable patches as a benefit to everyone. Existing factual copy is mostly approved. People skim, so grouping, type hierarchy, emphasis and actionable labels should communicate the story before paragraph reading. Preserve the bark, lichen and orange identity, Manrope and Sora, real screenshots and the logo-derived dashed ring. Decorative radial-gradient blobs were explicitly rejected and removed.

The introduction immediately below the hero consolidates Signal compatibility and Signal/Molly foundations. A text-only rearrangement and a competing provenance card did not satisfy the request for informational clarity. The current open composition puts its topic heading on the left, benefit first on the right, then quieter foundations and one optional source/security disclosure. Mobile follows that DOM order. Do not reintroduce Side by side, package-name, or one-account/two-app explanations. The legal disclaimer stays unchanged in the footer.

Matrix chat and the GitHub Discussions forum remain prominent entry points, with different chat-bubble and thread-list icons. Their actions are underlined. Bug reports and donations remain in the separate Help the project group. Keep selective bold and italic emphasis. The notifications card must retain the exact link text “Why it matters and why we're working on making it easy.” pointing to `#upcoming`.

Plans come from the existing `upcoming-planned-features` Discussions category, refreshed by daily builds, with label-priority items first. They must remain clearly labelled planned rather than shipped. The checked snapshot has ten discussions, eight in that category; no roadmap items were invented. Obtainium and the F-Droid repository have equal primary styling, with Recommended only on Obtainium. Manual APK download remains secondary. README uses the absolute Collie-hosted handoff; the website uses relative `obtainium/` so clicking the real CTA stays on the live build before publication. On the published domain it reaches the same destination. Preserve GitHub as the update source and `https://collie.im/#get` as the manual fallback. The verified nested-JSON APK filter is unchanged.

## Changes in this candidate

The roadmap renderer maps stable discussion numbers to concise display titles. Unknown titles retain their wording after removing editorial prefixes such as High Priority or bracketed tags. Escaping happens after display-title selection. A separate Priority badge comes only from the label, never title text. All eight discussion URLs are unchanged. Priority title weight is 600; ordinary plan titles are 500. The two-column grid reads across rows; mobile uses one column. The notifications introduction and italic planned-work disclaimer are unchanged. The gap from the roadmap follow-up links to the community heading is now 64 CSS px.

Only below 600px, the five secondary feature cards now form a compact list: 40px icons sit beside titles, body text aligns beneath titles, and the existing rule color separates items. Their card backgrounds, outlines, radii and inner padding are removed. Every word and link is retained. The encryption lead is unchanged. The intro heading is 24px against the 32px feature heading (75%), with the source/security disclosure visible in both introduction captures. At 768px and above, cards and typography are unchanged.

The lead feature retains its desktop two-column/two-row position and featured surface. Its 40px icon sits beside the heading; the approved explanation remains. Three short facts restate that explanation: Passphrase you choose; Locks after a set time; Stays encrypted if lost or stolen. A hairline separates the facts, and their row sits at the bottom. Smaller cards use one deliberate icon-to-heading gap rather than stacked padding and automatic margins. At phone widths up to 600px, the repeated facts and their divider are hidden; the approved paragraph already says the same thing. Wider screens retain the three facts. Phone spacing from the upcoming introduction to the italic note is 12px, and from that note to the list is 22px. No wording in either paragraph changed.

The narrow header is now a page-level sibling of the hero, so hero overflow cannot prevent persistent navigation. At 860px and below it is 56px high and sticky, with brand, Menu and Get Collie. Menu exposes the existing five section links in desktop order. Enter opens it; Escape closes it and returns focus; choosing a link or clicking outside closes it. Anchored sections have 56px scroll margin. Below 360px the wordmark is 16px rather than 18.4px, adding breathing room while retaining the 56px header, logo and Get Collie. At 320px the wordmark-to-Menu text gap is 19.16px, compared with 12.30px before this refinement. The desktop header keeps its existing behavior. There is no menu animation to suppress in reduced-motion mode; existing reduced-motion rules also disable smooth scrolling and hero motion.

The source/security disclosure keeps its native underlined, focusable 44px summary. Its chevron now follows the label with an 8px gap rather than sitting at the far edge.

The display title for [Discussion 106](https://github.com/0cwa/collieim-android/discussions/106) is “Send a text with its own timer”. The discussion describes holding Send to apply a one-time timer to an individual text. It does not specify duration choices, timer start conditions or view-once media. The link remains unchanged, and an assertion in the existing mapping test covers the concise title.

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
@media(max-width:600px){
  .feature-facts{display:none}
  .intro h2{font-size:1.5rem;line-height:1.25}
  .card:not(.featured){position:relative;display:grid;grid-template-columns:40px minmax(0,1fr);gap:4px 12px;margin-top:0;padding:0;border:0;border-radius:0;background:none}
  .card.featured + .card{margin-top:8px}
  .card:not(.featured)::before{content:"";position:absolute;inset-inline:0;top:-6px;border-top:1px solid var(--rule)}
  .card:not(.featured) .ico{grid-column:1;grid-row:1/span 2;width:40px;height:40px;margin:0}
  .card:not(.featured) h3{grid-column:2;grid-row:1;min-width:0;font-size:1.0625rem;line-height:1.35;font-weight:400}
  .card:not(.featured) p{grid-column:2;grid-row:2;min-width:0}
  .card:not(.featured) a[href="#upcoming"]{display:inline-block;min-height:24px;line-height:24px}
  .upcoming-status{margin-top:12px}
  .roadmap-list{margin-top:22px}
}
@media(max-width:359px){.top .brand{font-size:1rem}}
.intro .technical-details summary{justify-content:flex-start;gap:8px}
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
| 320 | none; document width 305 | 56px sticky | 5437.09 |
| 390 | none; document width 375 | 56px sticky | 4865.26 |
| 768 | none; document width 753 | 56px sticky | 4214.27 |
| 1280 | none; document width 1265 | 90px normal | 3684.98 |

These live checks used actual `innerWidth`. At 390px, the five-item region shrank from 978px to 614.73px; at 320px, from 1150px to 732.62px. This counts first secondary item top through last item bottom, including gaps. The encryption lead remains exactly 279.73px at 390 and 306.93px at 320. Every title and body starts at x68, to the right of the icon at x16; wrapped titles never flow below the icon. The notification action measures 291×48px at 390 and 221×48px at 320. All six card rectangles match the previous source exactly at 768 and 1280.

The source-render environment has no reserved scrollbar. At widths 320/390/768/1280, usable widths are likewise 320/390/768/1280, with no horizontal overflow and loaded fonts. Five-item heights are 661.63/566.69/644/668px; downloads start at 5219.22/4776.11/4188.48/3684.78px. This is an explicit environment difference, not a replacement for live measurements. Intro/feature headings are 24/32px on phones, 32/32px at 768, and 44/48px at1280. Current and baseline PNG bytes match exactly at both wider widths. The intro disclosure is visible in both phone introduction renders.

Native keyboard Tab from the brand reached Menu, Enter changed `aria-expanded` to true, and Escape restored false with focus on Menu. Outside-click closing passed. Each of the five actual menu links was clicked at both 390px and 768px. Every menu closed, and all headings landed below the header: heading tops ranged from 88.01px to 134.22px, with the header bottom at 56px. These position checks used automatic scrolling in the owned test tab to avoid measuring a smooth-scroll midpoint; source motion rules were unchanged. That Menu keyboard evidence applies to the unchanged navigation markup and JavaScript. In this scoped pass, the native summary was focusable and DOM activation opened and closed it. Later pointer transport timed out, and an instrumented Enter attempt produced no DOM keydown or keyup events; no fresh trusted-keyboard or pointer result is claimed from those failed transport calls.

Selected computed CSS contrast ratios were measured with the WCAG sRGB luminance formula. Light mode: badge 14.20:1, menu links 15.69:1, lead facts 14.20:1. Dark mode: badge 14.20:1, menu links 16.55:1, lead facts 8.01:1. These are checks of the new elements, not a whole-site automated contrast audit. Appearance and viewport readings were taken after fresh navigation; stale per-element media values were not treated as source defects. The Impeccable detector used a degraded regex fallback because parser modules are unavailable. Its retained warning is the existing 3px curved hero boundary; no clean automated audit is claimed.

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

Mobile hero length remains deliberately outside this pass: its copy appears before the phones, so the fresh image is below the first fold on narrow screens. Community remains lower in the page but has hero and persistent-menu entry points. Desktop/tablet six-card topology is unchanged; phones retain the featured card followed by five visible list items. The notifications explanation is technical because it states real setup requirements, and the user asked to preserve that paragraph. No real conversation capture was supplied. Review these as honest remaining design tradeoffs; do not infer approval from passing tests or from this implementation note.

Impeccable 4.1.1 was already installed for Codex under `~/.agents/skills/impeccable`. Its context, layout and craft-floor guidance was used. Canonical documentation confirms that path and `$impeccable`; nothing was installed or downloaded. Source, imagery and validation limits are recorded for independent review at the containing commit.
