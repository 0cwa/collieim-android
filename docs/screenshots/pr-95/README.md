# Current website review evidence

Implementation source is signed 0456fa475b76111a94c52949e2381f13a2ed8108; native assets/provenance are signed b3b4857fe6ebc0338f732cd9129c8b1bc94880f0. Review the containing evidence commit as one stable candidate. No rating is supplied by the implementation owner.

## Current views

All paths below are in this directory. The eight hero/page-top views are freshly rendered after adding the genuine chat. Unchanged component views retain their original source provenance: feature/Upcoming views from b7b32031a75c, community from 4a954f19b0b0, downloads/footer from 2917bdc27ad5. Their component source/appearance is unchanged by this image-only hero pass; their document offsets are reported separately.

| View | File | Actual iframe CSS viewport / PNG dimensions |
| --- | --- | --- |
| Desktop hero | [light](desktop-light.png) · [dark](desktop-dark.png) |1280×800|
| Phone hero, scrolled to phones | [light](mobile-light.png) · [dark](mobile-dark.png) |390×844|
| Narrow hero/curve | [hero-narrow-light.png](hero-narrow-light.png) |320×844|
| Actual phone page top | [page-top-mobile-light.png](page-top-mobile-light.png) |390×844, scrollY0|
| Actual narrow page top | [page-top-narrow-light.png](page-top-narrow-light.png) |320×844, scrollY0|
| Actual tablet page top | [page-top-tablet-light.png](page-top-tablet-light.png) |768×1024, scrollY0|
| Features | [desktop](features-desktop-light.png) · [tablet](features-tablet-light.png) |1280×1000 /768×1300|
| Phone features | [390](features-mobile-light.png) · [320](features-narrow-light.png) |390×1100 /320×1300|
| Upcoming plans | [desktop light](upcoming-desktop-light.png) · [phone dark](upcoming-mobile-dark.png) |1280×900 /390×1100|
| Community/help | [desktop light](community-desktop-light.png) · [phone dark](community-mobile-dark.png) · [narrow light](community-narrow-light.png) |1280×900 /390×1300 /320×1500|
| Downloads/footer | [desktop light](downloads-footer-desktop-light.png) · [dark](downloads-footer-desktop-dark.png) |1280×1100|
| Phone downloads | [light](downloads-mobile-light.png) · [dark](downloads-mobile-dark.png) |390×1100|
| Phone footer | [light](footer-mobile-light.png) · [dark](footer-mobile-dark.png) |390×844|

These are actual browser renders of the existing static build HTML/CSS with original font/image bytes embedded in fixed-size iframes through T3 html_preview. They are source renders, not live-tab screenshots. Only resource URLs are embedded; the helper waits for fonts/visible images, scrolls to the existing section and finishes native CSS animations. No content/layout reconstruction or recoloring. Native overlay scrollbars, DPR 1: usable width equals actual innerWidth. Every current hero view reported its requested width, loaded fonts/images, expected light/dark media and no overflow. Phone montage views use stage top 80px to clear the 56px sticky header; page-top views are explicitly scrollY0.

[render-source.py.txt](render-source.py.txt) preserves the exact rendering helper. It reads the existing build, not a mockup. The conversation's natural 540×1080 image renders 205.156×410.312px at 1280 and 153.094×306.188px at 390, preserving its ratio and complete header/composer without stretching or cropping. The rear encryption image remains 540×1200, likewise preserving its natural ratio.

Earlier raw browser exports shifted orange into pink, including in their PNG pixels. This source-render path preserves actual source RGB: desktop primary 242/152/43 (#F2982B) and bark 36/19/13 (#24130D), measured with ffmpeg rgb24 crop/od. No source colors were changed to compensate. Older supporting live captures (menu, narrow header, desktop introduction, Obtainium) retain c2a7b7c647de24fa50185f84b29fe141aaa6d931 provenance. Their component source is unchanged; about-mobile/about-narrow retain valid intro composition but their partial old feature-heading pixels are superseded by current feature views. community-narrow-dark.png is historical and superseded by the current community views. Use the table above for current hero/palette/feature judgments. Live T3 snapshot/input transport later timed out across callers and disconnected; no failed call is claimed as a pass.

## Audience and retained requirements

Collie is an Android messenger for ordinary Signal users. Copy is largely approved; people skim, so reading priority, grouping, hierarchy and actionable labels matter. The hero now shows familiar messaging first, backed by on-device protection. Preserve the Collie bark/lichen/orange palette, self-hosted Manrope/Sora and logo-derived dashed ring. Decorative radial-gradient blobs were rejected and removed.

Immediately below the hero, one introduction consolidates Signal compatibility and Signal/Molly foundations. The benefit leads, provenance is quieter, and Source and security details is an optional native disclosure. Side by side, package-name and one-account/two-app explanations remain absent from that introduction; installation verification still names the package. The legal footer disclaimer remains byte-identical.

Matrix chat and GitHub Discussions forum are prominent, with distinct chat/thread-list icons, 40px icon boxes and 22px titles against 16.8px lighter Help subheads. Bug reporting/donations retain a separate Help the project group. Actions remain underlined, with selective bold/italic emphasis.

Plans are generated from the existing upcoming-planned-features category during daily builds, priority-labelled discussions first with a separate label-derived Priority badge. The supplied snapshot has ten discussions/eight plans. The planned-work disclaimer is explicit; URLs remain unchanged. Discussion 106's title “Send a text with its own timer” was checked against its source: holding Send applies a one-time timer to one text; no duration/start/view-once claim was invented.

Notifications now says “Get message alerts without Google's push service. Collie can keep its own connection open, or use UnifiedPush with a server you trust.” The UnifiedPush anchor is unchanged. Exact required link text remains “Why it matters and why we're working on making it easy.” → #upcoming. That target's existing explanation replaces the vague “but setup still takes extra steps” with “but currently needs a trusted MollySocket server.” Planned disclaimer/list/URLs are unchanged. Source verification used IncomingMessageObserver, ApplicationContext and MollySocketRepository; this is consumer wording for real behavior, not removal of the trusted-server requirement.

Obtainium and F-Droid repository share primary styling; only Obtainium gets Recommended. Manual APK remains secondary. Website uses relative obtainium/ so the actual localhost CTA reaches the handoff; README uses https://collie.im/obtainium/. GitHub remains the APK update source and https://collie.im/#get the manual fallback. The verified nested-JSON APK filter was not altered. Actual app import/install through Obtainium was not performed.

## Genuine native images and composition

The montage uses **conversation front, corrected encryption left, locked screen right**. The technical proxy image is retained in source/Fastlane but no longer fronts this ordinary-user story. No CSS redesign was needed. Rear overlap covers portions of the encryption title/controls; the lock glyph/centered setup remain recognizable, and the full native image is available separately. There is no claim that every rear control is unobscured.

The chat is real API 35 app UI with fictional offline Alex/six messages inserted by a temporary fixture. Header, bubbles, avatar and composer are app-rendered, not fabricated/composited pixels. The native 1080×2160 PNG is Fastlane 1.png, SHA256 28ade62dd49c45047c283ce232f14b7dedf0e36d788e85825dfa6d1fea4a2688. conversation.webp is its 540×1080 q82 resize, SHA256 b94444eeab129ef45a453dd19c73b7ad181bed0d9d636623cdb30c3a81ea5b54. No personal account or service traffic: guest airplane mode on, data/Wi-Fi off, no active default network; fixture networking disabled before registration and checked afterward.

Fastlane 3.png is a **separate new native 1080×2160 encryption capture**, SHA256 b85f15db028af21b96692d4baa431496e19ccc7304e11cd4612046ee0245f5da. Its title y451, last button bottom 1760 and system insets 115/63 give 336/337px free space. Offline no-registration centering passed 1 collected test with no skip. Durable restoration passed 1 test, followed by cold production PID 6443/task 18 with WMS SECURE. A temporary setter-only restoration initially failed that real-window proof; waiting for SignalStore writes fixed the fixture. No production Android change is included.

The website encryption remains the original corrected 540×1200 WebP, SHA256 6862fbc60d5eb744ec9ba441bdec5ba4e064f3674343d5909760a19180bb0848. Its original 1080×2400 PNG and 450/450px geometry remain preserved at immutable 555fff83a6b7e8f5b8de89f522859f250a166b5f. Do not confuse those original website pixels with the distinct 2:1 Fastlane capture; hashes/dimensions differ honestly. The unchanged validator and official Izzy 2:1 rule required a native viewport capture, not cropping/padding or weakening policy.

Fastlane first three are chat/lock/encryption. Existing privacy moves to 4.png; 5–9 remain; settings-overview.png preserves old 1.png byte-for-byte. No other screenshot was discarded. Local main received only the new 3.png in signed a156e045212300b65bc1846bde39f9928fbad3d3, preserving the original website WebP and unrelated files; main was not pushed.

See [conversation-capture.md](conversation-capture.md) for exact commands, matching APK/helper hashes, UI dumps and security proof, and [encryption-capture.md](encryption-capture.md) for the original viewport record. Use only offline-demo-security.kt.txt for future fixture reproduction; historical pre-flush snapshots are provenance-only and clearly superseded.

## Layout and validation evidence

Below 600px, the encryption lead is followed by five visible compact feature items: 40px icon beside title, body aligned under title and hairline separators. No hidden descriptions or new padding. Heading balances “Locked when” / “you step away” at 320/390/768/1280; 320 wrap is unchanged. Desktop body/facts share 640.66px, card remains 714.66×408 and longest body line ends 51.66px from its edge.

Current overlay-scrollbar measurements:

| Width / usable width | Five-item region | Downloads document Y | Overflow |
| --- | --- | --- | --- |
|320 /320|685.63px|5337.80px|none|
|390 /390|566.69px|4785.45px|none|
|768 /768|644px|4197.83px|none|
|1280 /1280|668px|3689.45px|none|

The 390 compact region is unchanged by the final notification wording; 320 uses one extra 24px text line. Hero imagery does not alter downstream layout. Phone intro heading is 24px against 32px features (75%). Notification action is 306×48px at 390 and 236×48px at 320, above 24px. Earlier live T3 reserved 15px for a scrollbar: its390 region 614.73px was measured against real 978px baseline; that environment is distinct, not substituted for current source-render widths.

The56px page-level sticky narrow header retains Menu/Get Collie and all five section links. Prior native Tab→Menu, Enter/Escape, outside/link closing and heading-clearance checks apply to unchanged navigation source; heading tops were 88–134px with header bottom 56px. Later timed-out pointer/key transport did not reach DOM and is not new successful interaction evidence. Native source disclosure is focusable/underlined, 44px high with 8px chevron gap. Previous cream-strip checks remain valid: full-width 40px bands after the curve were exactly 249/251/244 at 390/320. No CSS changed in this image pass.

Fourteen website tests pass (category/priority/title mapping/escaping/API failures/pagination/safe paths/Obtainium), the Fastlane metadata validator passes, and git diff --check passes. Native seed, offline centering and durable restore each collected/passed 1 test. Selected computed-CSS contrast evidence only: light badge 14.20/menu 15.69/facts 14.20; dark badge 14.20/menu 16.55/facts 8.01. No whole-site AA claim. The Impeccable detector used its limited fallback because parser modules are absent; no full automated audit is claimed. Impeccable 4.1.1 was already installed for Codex and used; no installation/download.

Remaining design tradeoffs are visible in the images: long mobile hero before the full phones, a large genuine native profile header in the demo chat, rear-screen overlap and the technical setup detail in Upcoming. No independent approval is implied by passing checks. The requested localhost:8879 server/shared caches remain; temporary Android test sources, 47 generated build/cache directories and owned AVD/runtime were removed after exact proof was saved. Unrelated emulators/worktrees and user files were untouched.
