**Current dark mural:** [monochrome theme adaptation and unchanged-light proof](dark-mural/README.md). Full-colour canonical and copy/background evidence below remain retained snapshots.

**Current copy cleanup:** [reviewed wording, hierarchy, native views and scoped checks](copy-cleanup/README.md). Background/canonical asset proofs below remain unchanged.

**Final A landscape refinement:** [registered forest correction and current feature views](backgrounds/a-refinement/README.md). Canonical mural evidence below remains unchanged.

**Current final artwork:** [one faithful canonical mural, native light/dark ON/OFF views and checks](backgrounds/canonical/README.md). Source e8e9f8b0 retains foreground geometry and uses the same independent SVG at every width. Earlier resting redraws and visibility metrics below are historical.

# Current website review evidence

**Earlier registered SVG refinement:** [interactive comparison and current renders](backgrounds/refinement/README.md). Source888d1383 corrects measured band/contour mismatches while retaining all foreground geometry.

**Earlier SVG-background appearance:** [background implementation, renders and fitting evidence](backgrounds/README.md) · [comparison board](backgrounds/comparison.html). Source40027033 adds the selected flowing landscape and lower Collie murals without moving foreground content. The older views below predate these backgrounds; use them for retained component/native/runtime evidence rather than current background appearance.


Current chat image/provenance source is signed d754358d1afac77eb84c260a7383cfe0688e38ee; Current CSS is signed531cc156584569c8b40cdec2bbadbfbcf71dae9b; HTML/copy remains eb9e7c2376d78222dc0a86926f33084b26d96879. The only current CSS change adds bottom space below the phones; the original symmetric arc geometry is unchanged. Original encryption/lock provenance remains b3b4857fe6ebc0338f732cd9129c8b1bc94880f0. Review the containing evidence commit as one stable candidate. No rating is supplied by the implementation owner.

## Current views

All paths below are in this directory. Desktop hero light/dark,390 transition light/dark,320 transition and768 page top are freshly rendered after the divider clearance fix. Initial320/390 page-top appearance is unchanged because the boundary remains below those844px crops. Community views retain eb9e7c source; features/Upcoming/downloads/introductions retain unchanged e532bde component appearance. Their document positions shift with the extra64px(phone)/80px(wider) hero space. No decoration is implemented. Exact matched before/after measurements and current strip/anchor checks are in divider-clearance.md.

| View | File | Actual iframe CSS viewport / PNG dimensions |
| --- | --- | --- |
| Desktop hero | [light](desktop-light.png) · [dark](desktop-dark.png) |1280×960|
| Phone hero, scrolled to phones | [light](mobile-light.png) · [dark](mobile-dark.png) |390×844|
| Narrow hero/curve | [hero-narrow-light.png](hero-narrow-light.png) |320×844|
| Actual phone page top | [page-top-mobile-light.png](page-top-mobile-light.png) |390×844, scrollY 0|
| Actual narrow page top | [page-top-narrow-light.png](page-top-narrow-light.png) |320×844, scrollY 0|
| Actual tablet page top | [page-top-tablet-light.png](page-top-tablet-light.png) |768×1200, scrollY 0|
| Features | [desktop](features-desktop-light.png) · [tablet](features-tablet-light.png) |1280×1000 /768×1300|
| Phone features | [390](features-mobile-light.png) · [320](features-narrow-light.png) |390×1100 /320×1300|
| Upcoming plans | [desktop light](upcoming-desktop-light.png) · [phone dark](upcoming-mobile-dark.png) · [narrow light](upcoming-narrow-light.png) |1280×900 /390×1100 /320×1250|
| Community/help | [desktop light](community-desktop-light.png) · [phone dark](community-mobile-dark.png) · [narrow light](community-narrow-light.png) |1280×900 /390×1300 /320×1500|
| Downloads/footer | [desktop light](downloads-footer-desktop-light.png) · [dark](downloads-footer-desktop-dark.png) |1280×1100|
| Phone downloads | [light](downloads-mobile-light.png) · [dark](downloads-mobile-dark.png) · [narrow](downloads-narrow-light.png) |390×1100 /320×1250|
| Current phone introduction | [390](about-mobile.png) · [320](about-narrow-light.png) |390×844 /320×844|
| Phone footer | [light](footer-mobile-light.png) · [dark](footer-mobile-dark.png) |390×844|

These are actual browser renders of the existing static build HTML/CSS with original font/image bytes embedded in fixed-size iframes through T3 html_preview. They are source renders, not live-tab screenshots. Only resource URLs are embedded; the helper waits for fonts/visible images, scrolls to the existing section and finishes native CSS animations. No content/layout reconstruction or recoloring. Native overlay scrollbars, DPR 1: usable width equals actual innerWidth. Every current hero view reported its requested width, loaded fonts/images, expected light/dark media and no overflow. Phone montage views use stage top 80px to clear the 56px sticky header; page-top views are explicitly scrollY 0.

[render-source.py.txt](render-source.py.txt) preserves the exact rendering helper. It reads the existing build, not a mockup. The conversation's natural 540×1080 image renders 205.156×410.312px at 1280 and 153.094×306.188px at 390, preserving its ratio and complete header/composer without stretching or cropping. The rear encryption image remains 540×1200, likewise preserving its natural ratio.

Earlier raw browser exports shifted orange into pink, including in their PNG pixels. This source-render path preserves actual source RGB: desktop primary 242/152/43 (#F2982B) and bark 36/19/13 (#24130D), measured with ffmpeg rgb24 crop/od. No source colors were changed to compensate. Older supporting live captures (menu, narrow header, desktop introduction, Obtainium) retain c2a7b7c647de24fa50185f84b29fe141aaa6d931 provenance. Their component source is unchanged; about-mobile/about-narrow are freshly captured and show the current balanced feature heading at their bottom. community-narrow-dark.png is historical and superseded by the current community views. Use the table above for current hero/palette/feature judgments. Live T3 snapshot/input transport later timed out across callers and disconnected; no failed call is claimed as a pass.

## Audience and retained requirements

Collie is an Android messenger for ordinary Signal users. Copy is largely approved; people skim, so reading priority, grouping, hierarchy and actionable labels matter. The hero now shows familiar messaging first, backed by on-device protection. Preserve the Collie bark/lichen/orange palette, self-hosted Manrope/Sora and logo-derived dashed ring. Decorative radial-gradient blobs were rejected and removed.

Immediately below the hero, one introduction consolidates Signal compatibility and Signal/Molly foundations. The benefit leads, provenance is quieter, and Source and security details is an optional native disclosure. Side by side, package-name and one-account/two-app explanations remain absent from that introduction; installation verification still names the package. The legal footer disclaimer remains byte-identical.

Matrix chat and GitHub Discussions forum are prominent, with distinct chat/thread-list icons, 40px icon boxes and 22px titles; Help the project is a quieter 20px heading, with 16.8px Help subheads. Bug reporting/donations retain a separate Help the project group. Actions remain underlined, with selective bold/italic emphasis. Matrix/forum actions now compute weight600 against Help action500, correcting a specificity override that previously made the primary actions400. Their sizes/colors/icons are unchanged. The existing emphasized “suggest a feature” words in the Community intro now link to the already-used Ideas/Requests category; a real request returned HTTP200 at that exact GitHub category.

Upcoming retains the existing nine-word first sentence as an 18px/400 benefit lead. A native details summary keeps the complete UnifiedPush/trusted MollySocket sentence visible. Only the unchanged easier-setup/linked-phone sentence is disclosed. Phones initially close this detail; desktop, direct #upcoming entry and markup without the site script keep it open. The exact feature Why link opens it before following #upcoming. Ordinary menu navigation does not force it open. No sentence, disclaimer, plan or URL was removed. The native underlined summary remains focusable, with measured heights 96/72/44px at 320/390/1280.

Plans are generated from the existing upcoming-planned-features category during daily builds, priority-labelled discussions first with a separate label-derived Priority badge. The supplied snapshot has ten discussions/eight plans. The planned-work disclaimer is explicit; URLs remain unchanged. Discussion 106's title “Send a text with its own timer” was checked against its source: holding Send applies a one-time timer to one text; no duration/start/view-once claim was invented.

Notifications now says “Get message alerts without Google's push service. Collie can keep its own connection open, or use UnifiedPush with a server you trust.” The UnifiedPush anchor is unchanged. Exact required link text remains “Why it matters and why we're working on making it easy.” → #upcoming. That target's existing explanation replaces the vague “but setup still takes extra steps” with “but currently needs a trusted MollySocket server.” Planned disclaimer/list/URLs are unchanged. Source verification used IncomingMessageObserver, ApplicationContext and MollySocketRepository; this is consumer wording for real behavior, not removal of the trusted-server requirement.

Obtainium and F-Droid repository share primary styling; only Obtainium gets Recommended. The short secondary note “Needs the Obtainium app.” now states its prerequisite; existing description/button/handoff remain unchanged. Manual APK remains secondary. Website uses relative obtainium/ so the actual localhost CTA reaches the handoff; README uses https://collie.im/obtainium/. GitHub remains the APK update source and https://collie.im/#get the manual fallback. The verified nested-JSON APK filter was not altered. An account-free first-install walkthrough now passed on API35/Chrome124.0.6367.219 with official Obtainium1.7.4 x86_64: absent-app help remained available, the real URI imported Collie, and public v8.35.1-3-rc2 installed without launching Collie. See [exact runtime scope, versions, APK hashes and three native captures](obtainium-walkthrough/README.md); no frontend/config change was needed.

## Genuine native images and composition

The montage uses **conversation front, corrected encryption left, locked screen right**. The technical proxy image is retained in source/Fastlane but no longer fronts this ordinary-user story. No CSS redesign was needed. Rear overlap covers portions of the encryption title/controls; the lock glyph/centered setup remain recognizable, and the full native image is available separately. There is no claim that every rear control is unobscured.

The first fully readable message moved from native1003px to373px (bubble top985→355px). The historical top message is naturally partly masked at the app bar; it is not counted as fully readable. At unchanged hero geometry, readable text moves320:720.85→649.92px,390:705.01→615.70px,1280:397.00→277.32px. No image cropping or HTML/CSS/copy/layout change.

The chat is real API 35 app UI with fictional offline Alex/fourteen-message ongoing thread inserted by a temporary fixture. Header, bubbles, avatar and composer are app-rendered, not fabricated/composited pixels. The native 1080×2160 PNG is Fastlane 1.png, SHA256 c70ebe40ab720f40c93d87254c3f906776b7678bfe011a4153b8c6edcc9f9f11. conversation.webp is its 540×1080 q82 resize, SHA256 230620ee9b676c145f4f729fd356258921826732fcad4050b889a072326edd62. No personal account or service traffic: guest airplane mode on, data/Wi-Fi off, no active default network; fixture networking disabled before registration and checked afterward.

Fastlane 3.png is a **separate new native 1080×2160 encryption capture**, SHA256 b85f15db028af21b96692d4baa431496e19ccc7304e11cd4612046ee0245f5da. Its title y451, last button bottom 1760 and system insets 115/63 give 336/337px free space. Offline no-registration centering passed 1 collected test with no skip. Durable restoration passed 1 test, followed by cold production PID 6443/task 18 with WMS SECURE. A temporary setter-only restoration initially failed that real-window proof; waiting for SignalStore writes fixed the fixture. No production Android change is included.

The website encryption remains the original corrected 540×1200 WebP, SHA256 6862fbc60d5eb744ec9ba441bdec5ba4e064f3674343d5909760a19180bb0848. Its original 1080×2400 PNG and 450/450px geometry remain preserved at immutable 555fff83a6b7e8f5b8de89f522859f250a166b5f. Do not confuse those original website pixels with the distinct 2:1 Fastlane capture; hashes/dimensions differ honestly. The unchanged validator and official Izzy 2:1 rule required a native viewport capture, not cropping/padding or weakening policy.

Fastlane first three are chat/lock/encryption. Existing privacy moves to 4.png; 5–9 remain; settings-overview.png preserves old 1.png byte-for-byte. No other screenshot was discarded. Local main received only the new 3.png in signed a156e045212300b65bc1846bde39f9928fbad3d3, preserving the original website WebP and unrelated files; main was not pushed. A later signed image-only main follow-up8e32173c04b50e2bbe31cd475726821f69a8641d replaces its still-original Fastlane1 settings image with the current chat and adds the previously absent conversation.webp. It does not reorder main's other screenshots or change any other file; unrelated .local/tools state is preserved. The PR's deliberate chat/lock/encryption order must not be assumed for main.

See [ongoing-conversation-capture.md](ongoing-conversation-capture.md) for the current chat commands, matching APK/helper hashes, UI dump and durable security proof. The normal conversation already opened at its latest messages; no scroll manipulation was necessary. The earlier [conversation-capture.md](conversation-capture.md) remains the encryption and historical six-message record; [encryption-capture.md](encryption-capture.md) records the original viewport. Use only offline-demo-security.kt.txt for future fixture reproduction; historical pre-flush snapshots are provenance-only and clearly superseded.

## Current focused phone clarity measurements

“Installs alongside Signal” is removed from the hero at every width. Only “Free and open source” and “Android 8.1 or newer” remain; no replacement installer-name fact was added. Coexistence/two-app/package-name marketing is absent; the ordinary “alongside” in the unrelated Upcoming sentence and the installation verification package ID are intentionally retained.

Headline and lede wording, weight/line-height ratio, orange second sentence, native images and phone composition are unchanged. Phone-only sizing/gaps now make the real messages appear earlier:

| Actual CSS width | Headline / line height / lines | Alex header text Y | First fully readable message text Y | Facts / font | Primary / secondary target |
| --- | --- | --- | --- | --- | --- |
|320|30 /32.4px /4|626.84|649.92|2 rows /14.4px|288×50 /288×44px|
|390|32 /34.56px /4|586.64|615.70|1 row /14.4px|358×50 /358×44px|

Y is document CSS pixels at scrollY 0 in the accurate overlay-scrollbar renderer, using actual front-image geometry and matching native UI text bounds. Fixed phone gaps: header→headline 20px, headline→lede 20, lede→buttons 24, button rows 12, buttons→facts 20, facts→stage 16. Lede is 16px/26.4px. Focus-ring CSS is unchanged. At 768 and 1280, measured headline, lede, actions, stage and front-image rectangles/fonts are exactly identical to the prior candidate; only the third fact is deliberately absent.

At 390 the Get panel grows 859.52→881.52px, exactly 22px, from the prerequisite's 20px line plus 2px margin. Existing card copy/Recommended/primary styles/handoff are unchanged; no extra auto-update promise. All six feature descriptions, community, legal line and planned disclaimer/list/URLs are byte-identical. The current pass preserves this phone hero/download behavior and all native assets, metadata, local main and native proof. It changes supporting text weight, roadmap disclosure, desktop lead balance and desktop section spacing only.

## Current symmetric divider fix

The existing symmetric arc width, height, curvature,3px stroke and lateral position are unchanged. Only hero bottom padding adds64px below600px and80px above600px. Matched320/390/768/1280 montage-bottom→center-apex clearances change21.48/21.07/22.55/44.31→85.48/85.07/102.55/124.31px. Hero title/lede/CTA/photo geometry is unchanged; intro moves down by the same64/80px. Every narrow section heading clears the56px header. Full-width40px light strips below the arc remain exactly249/251/244RGB: no shadow spill. [Exact current evidence](divider-clearance.md). No contour or other decoration accompanies this change.

## Verified focused changes and measurements

| Supplied finding | Current evidence and disposition | Result |
| --- | --- | --- |
| Phone roadmap preamble hides first plan | Valid: first border was 568.06/478.64px at 320/390 with section top about 56px | 478.88/391.84px at the same header offset; lead 81/54px, summary 96/72px, planned note 68.39px, list gap 14px |
| Secondary type is delicate | Valid: 300 body weight | 400 for secondary feature bodies (16px/24px), community descriptions (15.6px/25.74px), Help copy (17px desktop, 16px phone), Upcoming detail (16px); colors/hero lede/headings unchanged |
| Desktop protection body/facts have an isolated band | Valid: body→divider 89.69px | 20px; the existing whole content group is centered with equal 67.84px outer clearance. Card remains 714.66×408px and six-card topology is unchanged; phone lead is unchanged |
| Section gaps vary; Help heading outranks channels | Valid: measured text/group boundaries 191.98/64/179.19px, Help title 23.2px | One desktop --section-gap token yields 96/96/96px; Help title20px versus Matrix/forum22px |

Visible desktop boundary definition is consistent: last Also included paragraph bottom→Upcoming heading top; Upcoming-more paragraph bottom→community heading top; entire Help group bottom→Get panel top. The shared gap token applies above 860px only. Desktop Upcoming supporting detail is 78.39px versus 79.17px before (0.78px shorter); its full-width open flow preserves the intro height without duplicated copy or a custom disclosure widget.

Browser DOM-action probes on the actual built page observed: summary click opens, second click closes, exact Why click opens the explanation; all eight plans remain present. A direct-fragment setup and site-script-omitted setup both leave the native detail open with the full explanation visible. Those are programmatic DOM-action/source probes, not new trusted keyboard/physical-pointer transport evidence. Native summary supplies the keyboard behavior; T3 live input remains unavailable. No-JS markup defaults open.

## Layout and validation evidence

Below 600px, the encryption lead is followed by five visible compact feature items: 40px icon beside title, body aligned under title and hairline separators. No hidden descriptions or new padding. Heading balances “Locked when” / “you step away” at 320/390/768/1280; 320 wrap is unchanged. Desktop body/facts share 640.66px, card remains 714.66×408 and longest body line ends 51.66px from its edge.

Current overlay-scrollbar measurements:

| Width / usable width | Five-item region | Downloads document Y | Overflow |
| --- | --- | --- | --- |
|320 /320|685.63px|5043.67px|none|
|390 /390|614.69px|4632.08px|none|
|768 /768|668px|4258.05px|none|
|1280 /1280|668px|3559.33px|none|

The stronger secondary body weight adds two 24px lines to the combined five-item region at 390 and one at 768; the 320 and desktop region heights are unchanged. Text and links remain complete. The phone-only hero compaction moves downstream sections earlier; the desktop/tablet hero geometry is unchanged. Phone intro heading is 24px against 32px features (75%). Notification action is 306×48px at 390 and 236×48px at 320, above 24px. Earlier live T3 reserved 15px for a scrollbar: its390 region 614.73px was measured against real 978px baseline; that environment is distinct, not substituted for current source-render widths.

The56px page-level sticky narrow header retains Menu/Get Collie and all five section links. Prior native Tab→Menu, Enter/Escape, outside/link closing and heading-clearance checks apply to unchanged navigation source; heading tops were 88–134px with header bottom 56px. Later timed-out pointer/key transport did not reach DOM and is not new successful interaction evidence. Native source disclosure is focusable/underlined, 44px high with 8px chevron gap. Previous cream-strip checks remain valid: full-width 40px bands after the curve were exactly 249/251/244 at 390/320. Navigation and curve CSS remain unchanged. The preceding phone hero type/spacing refinement and this supporting-text/disclosure/desktop-spacing pass did change CSS; the unchanged navigation/curve evidence is not a claim that the whole stylesheet stayed fixed.

Fourteen website tests pass (category/priority/title mapping/escaping/API failures/pagination/safe paths/Obtainium), the Fastlane metadata validator passes, and git diff --check passes. The new fourteen-message seed and durable restore each collected/passed1test; cold production PID4149 RoutingActivity showed SECURE afterward. Earlier encryption centering1/1 proof remains valid and was not rerun for this chat-only update. Selected computed-CSS contrast evidence only: light badge 14.20/menu 15.69/facts 14.20; dark badge 14.20/menu 16.55/facts 8.01. No whole-site AA claim. The Impeccable detector used its limited fallback because parser modules are absent; no full automated audit is claimed. Impeccable 4.1.1 was already installed for Codex and used; no installation/download.

Remaining design tradeoffs are visible in the images: naturally clipped older chat content at the top of an ongoing thread, rear-screen overlap and a technical limitation in the visible Upcoming summary. No independent approval is implied by passing checks. The requested localhost:8879 server/shared caches remain; temporary Android test sources, 47 generated build/cache directories and owned AVD/runtime were removed after exact proof was saved. Unrelated emulators/worktrees and user files were untouched.
