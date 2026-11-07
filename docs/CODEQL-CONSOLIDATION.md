# Security review consolidation

Input application main:62db1d3e13d53286a720c3cebc6e26561a982dd5 (approved Matrix
README/image changes preserved by ordinary merge). Starting security branch:
PR42 d6001052447be56625bec2ba2b60533972d4b296. Compared competing PR44:
541b82f135bef6ffe115cf1171a3a424faae5270. Both were open/non-draft at inspection;
neither was treated as a superset or closed automatically.

## Unique-change disposition

| Input | Decision | Reason/result |
| --- | --- | --- |
|42 common Spinner navigation/textContent/history and five CDN integrity pins|Take|Preserve reviewed repository-only corrections. Spinner is absent from compiled Collie and future published source.|
|42 Ace/sqlformatter/mode/theme preload guards, guarded JSON/array/string history,25-query limit, exact query restoration|Take unchanged|The stronger existing guard behavior and eight Node regression cases remain.|
|42 report.py/snapshot.py, exact reviewed-finding identity/source hash, extended suite and traced JNI builder|Take|Keep one SARIF auditor; raw findings and extraction failures remain visible.|
|42 CODEQL_ADVANCED_UPLOAD|Take unchanged|Artifact evidence remains default. No live default-setup/upload setting was modified.|
|44 screenshot data parser, PNG/LFS limits, branch/head lease and data-only artifact transfer|Adapt|Main-only manual publication uses reviewed policy; branch generation has no write/private-cache credentials. Producer run/attempt/path/head/repository are checked before artifact use.|
|44 screenshot comment behavior|Adapt|Comments give dispatch guidance without executing candidate code in the default-branch runner scope. Publication is a separate main-only manual action after reviewing data.|
|44 sync.yml immutable checkout pin|Take|Small workflow pin correction; no upstream-sync behavior change.|
|44 alert_inventory.py/check_sarif.py/second reviewed-finding format and CODEQL_UPLOAD_MODE|Skip|Duplicate reporting/upload controls would diverge;42's stricter gate remains authoritative.|
|44 buildless C/C++ configuration|Skip|It does not establish equivalence to42's actual traced old JNI TU and header; neither covers shipped RingRTC/WebRTC/libsignal sources.|
|44 Spinner variants/test_spinner.cjs|Skip|Would discard42's unique editor/history guards; preserve42's MJS tests instead.|
|44 narrower Dependabot and removal of cache-mode/read comments|Skip|Keep42's broader pinned action upkeep and server-enforced read-only cache scope.|
|44 scope.py/pins.py/security-source-overlay policy changes|Separate owner|Runtime/app40 owns exact projection/scope integration; this consolidation does not overwrite it or widen directory exceptions.|

Original contribution/provenance remains inspectable at those immutable commits.
The screenshot parser/tests are adapted from PR44 rather than claimed as newly
invented code. No compiled application Java/Kotlin/C++ or native binary was changed
by these adaptations. No production setting, signing identity or release asset
was modified. This is not evidence that a new application source candidate builds.

## Screenshot trust boundary

update-screenshots.yml only generates on an explicitly dispatched feature branch.
Its build has contents:read, cache-mode:none, disabled Gradle cache and no private
cache/signing/repository-write values. It uploads one bounded JSON manifest in an
explicit ZIP artifact. Issue comments only resolve metadata and provide guidance.

publish-screenshots.yml is a separate main-only manual workflow. Its policy helper
comes from that exact trusted main checkout, not the candidate/artifact. It checks
the successful generation run, attempt, source SHA, branch, repository and workflow
path, then reads one bounded JSON ZIP member without extraction. Only canonical
PNG paths in existing screenshot source sets can change. The publisher never
checks out or executes candidate code. It writes LFS pointers in a fresh bare Git
store, disables ambient Git/hooks/filters/replace refs and forces the GitHub LFS
endpoint. An exact branch lease rejects movement during upload. The current default/main and8.*
release branches are not allowed targets. Actual remote screenshot generation/LFS
publication is NOT RUN; tests use isolated fixtures and mocked API data.

## Coverage and ownership after source publication

The current application bridge continues42's diagnostic categories: Actions,
Python, JavaScript/HTML and the old checked-in JNI module. Its Android NDK build
traces one C++ TU and its header for the old module's four declared ABIs; it does
not analyze packaged RingRTC/WebRTC/libsignal native sources. Java/Kotlin remains
NOT RUN. Buildless Java omits Kotlin, so it is not a substitute for an actual
verified traced Android build.

Engineering is the authoritative owner after cutover: one report/snapshot/parser
implementation, tests, query config and Actions/Python workflow. The temporary
application copies are removed when the published-source workflow can pin that
reviewed durable engineering commit. Future app workflow templates are explicit
publication inputs; fill their immutable engineering revision only AFTER that
commit exists, then generate G. They must never restore old Spinner/JNI paths or
be silently overwritten by the publisher. No source commit embeds its own hash.

Application workflow coverage and any future Java/Kotlin traced build are labeled
separately. Actual native/public dependency and Android build proof must precede
Java/Kotlin activation; artifacts/metrics from another source tree are not proof.
The engineering tooling scan does not claim application JVM/native coverage.
Scope.py's narrow main/Matrix/temporary bridge transition is owned by app40 and
must be combined/reviewed before this preparation becomes merge-ready.

Official behavior references inspected:
https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax
(cache-mode read/none is enforced with scoped cache tokens), and
https://docs.github.com/en/code-security/reference/code-scanning/codeql/build-options-for-compiled-languages
(Java none excludes Kotlin). No advanced-upload/admin configuration was enabled.

## Executed local checks

PASS:35 CodeQL auditor/snapshot/JNI-helper unit tests;18 screenshot parser/Git/
workflow-boundary tests;19 existing CodeQL scope tests;8 preserved Spinner Node
cases. YAML parsed with PyYAML6.0.3 and modified workflow action refs are immutable
40-character commits. The approved Matrix README/SVG bytes equal current62db;
both Spinner files equal PR42's reviewed blobs. No compiled app source changed.

NOT RUN: new hosted CodeQL scan/SARIF, actual screenshot Gradle rendering or remote
LFS/branch publication, reconstructed/published Android application Java/Kotlin or
native-dependency source analysis. Local CodeQL CLI was not installed; no bundle
was downloaded. The existing scope unit tests passing does not establish that a
new cumulative PR/file set is authorized: app40's owned main/base/path integration
still needs the final combined-candidate check before merge eligibility.

The future published application workflow is supplied as an explicit publication
layer by `tools/codeql/render_publication.py`. Render with the immutable reviewed
engineering tools commit after that commit is durable; apply the resulting five
files before generating G. The layer carries Actions coverage, the same report
gate/upload toggle and pinned engineering screenshot parser/publisher. Java/Kotlin
and native dependency coverage remain NOT RUN until a reviewed traced Android
build proves extraction. The old baseline JNI builder/Spinner are not part of the
engineering canonical tool export or the future application workflow. Normal
application builds never invoke this renderer or inject source.
