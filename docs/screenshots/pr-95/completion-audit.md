# Completion audit for the current website candidate

This is a factual requirement audit, not a design rating. Source and current images are packaged in the containing commit; native image provenance is unchanged from the separately pushed correction.

| Requirement | Evidence/current state |
| --- | --- |
| Ordinary messenger audience and skimming | Patch-reading pitch removed; mostly approved factual paragraphs retained. Intro is one reading group immediately after hero; phone feature recap removed. |
| Signal/Molly consolidation | Intro contains compatibility, foundations and optional source/security details. Side by side and account/two-app explanations are absent. The installation verification still names the package as before. |
| Community/help | Matrix and Discussions have distinct chat/forum icons and visible actions. Help the project remains a separate bug-report/donation group. |
| Exact notification link and emphasis | Required sentence still links to #upcoming; selective bold/italic and underlined actions remain. |
| Discussion-generated plans | Existing category only; eight plans in the supplied snapshot, label-priority first, separate badge, explicit planned disclaimer. Daily workflow uses 06:17 UTC and read permissions. Discussion106 wording was checked against the public source; URL unchanged. |
| Installation options | Obtainium/F-Droid share primary styling; Recommended appears only once; manual APK remains secondary. Website's actual CTA reaches its local handoff; README retains absolute Collie URL. GitHub update source, manual collie.im/#get fallback and tested nested-JSON filter are unchanged. |
| Fresh native screenshot | Fastlane PNG and website WebP share the new 06:53 API 35 capture. Matching-APK centering test passed unskipped; actual title/button geometry gives equal 450px clearance. Security preference restoration passed and subsequent window SECURE flag was verified. No fabricated chat image. |
| Actual hero/current pixels | Encryption stays in front, lock left, proxy right. Browser loads the new WebP hash. Current native hashes match signed 555fff83a6b7 and local-main signed bf7e38610090. |
| Brand/live workflow | Solid hero; no CSS radial gradients. Fonts/palette/order unchanged. Existing localhost:8879 server retained. Impeccable 4.1.1 was present and used; no install/download. |
| Narrow navigation | 56px persistent header, native button/ARIA state, existing section links and Escape/outside/link closing. Prior successful keyboard/anchor evidence is distinguished from later degraded input transport in README. |
| Validation | 14 website tests passed; no overflow at actual 320/390/768/1280. Current intro/features, hero, roadmap, community and downloads/footer use exact built-source browser renders with verified fonts/images/media/viewport. Exported button/bark RGB matches source. Supporting older live captures retain original provenance. Selected contrast scope only; no whole-site AA assertion. |
| Local main |Only the two immutable image blobs were committed as bf7e386100905d5c5c03bb2f22cff480c647ebe4. Main was not pushed; unrelated .local/ and tools/ were preserved. |
| Cleanup |Dedicated emulator-5562/AVD, temporary app tests and 47 generated build/cache directories removed. Emulator-5554 was untouched. Shared SDK/JDK/dependency caches and requested preview server retained. |

## Verified review findings

| Finding | Disposition/confidence | Scoped result |
| --- | --- | --- |
| Phone secondary cards are too tall | Valid, high | Below600 only: five visible list items with inline40px icons and aligned body text. Live390 region978 to614.73px; encryption lead unchanged. Wider feature PNGs are byte-identical to baseline. |
| Phone introduction competes with features | Valid, high | Heading24px against32px features (75%); disclosure remains visible. |
| Phone feature facts repeat the paragraph | Valid, high | Hidden with their divider only at widths up to 600px; desktop remains. |
| Phone roadmap spacing is too generous | Valid, high | Margins changed from 18/32px to 12/22px without changing either paragraph. |
| Disclosure chevron is detached from its label | Valid, high | Flex alignment now starts at the label with an 8px gap; native summary retained. |
| Header is tight at 320px | Valid as a requested visual refinement, high | No overflow existed; wordmark reduction adds 6.85px, giving a 19.16px text gap. |
| Dark links retain light colors | Stale/retracted, high | Fresh navigation resolves per-element media state; dark CSS variables are correct. No palette change. |
| Plan106 wording is hard to parse | Valid clarity finding, high | Public source supports an individual-text timer; concise display title and existing assertion updated. URL unchanged. |

## PR102 proof remains separate

PR102 head is signed 614ed0f18d5301496fa1f86a756e64cb07e4910b. Its code matches the tested 5660b3934dea8855530fb32ab6e10f12754a54c5; only eight PNGs and capture notes were added afterward. The recorded run reports OK (6 tests): five native UI methods and one link method, no skips. Six portrait captures are 1080 × 2400 and two landscape captures 2400 × 1080. All eight full-SHA images and reproduction notes are attached to the PR description. Battery-restriction prompting alongside the notes and the short large-text landscape reading area remain explicitly documented; this website pass does not claim to resolve them.

## Remaining completion steps outside this frozen review packet

- Independent reviews of this exact candidate are pending; no prior scores or desired grade are supplied here.
- PR95 remote head is 555fff83a6b7e8f5b8de89f522859f250a166b5f, including the fresh native assets. The final design candidate has not been pushed. After acceptance/push, its description needs the current screenshot gallery, 14-test count and precise selected-contrast wording; the original blanket all-text AA statement is stronger than the recorded evidence.
- Public deployment and actual Obtainium app installation/import have not been performed in this website lane. The local CTA/handoff was read end to end. No default-branch push, merge or production deployment occurred.

The footer disclaimer is byte-for-byte unchanged from the consolidation base. No source, screenshot or documentation updates should be made while this candidate is being scored.
