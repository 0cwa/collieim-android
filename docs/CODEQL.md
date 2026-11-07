# CodeQL analysis

The CodeQL workflow scans the repository with GitHub's `security-extended` suite
and preserves the existing language categories. This suite includes the default
security queries plus additional queries with lower precision or severity. Every
finding needs investigation; a larger suite is not a guarantee of fewer false
positives. There are no directory exclusions, severity filters or query exclusions
in `.github/codeql/codeql-config.yml`.

## What is analyzed

| Area | Analysis | Category | Limits |
| --- | --- | --- | --- |
| GitHub Actions | Repository workflow files | `/language:actions` | Covers the checked-out workflows; it does not execute the jobs. |
| Python | Repository Python tools, scripts and tests | `/language:python` | Patch text is not executable Python source for CodeQL. |
| JavaScript / HTML | Repository JavaScript and HTML assets | `/language:javascript-typescript` | The checkout contains the Signal baseline. Collie changes stored only in patches require source reconstruction before application coverage can be claimed. |
| C / C++ | Traced Android NDK build of the repository's JNI module for its four declared ABIs | `/language:c-cpp` | This scans the baseline JNI module, not the native dependencies packaged in Collie's APK. |
| Java / Kotlin | **Not analyzed by this workflow** | None | A complete scan requires a traced build of the reconstructed Collie source with its verified native dependencies. |
| RingRTC / WebRTC / libsignal native dependencies | **Not analyzed by this workflow** | None | Prebuilt libraries are not source analysis. Their pinned source trees and appropriate build commands need separate analysis. |

The successful default-setup run on main `f6f68cdee08fb8d9acb6a866b1673deb10525f20`
analyzed 9 Actions files, 111 Python files, 4 JavaScript files, 2 HTML files, and
reported 1 C plus 1 C++ file. The latter are a JNI header and its one C++ translation
unit, not two independently compiled source files. It had no Java/Kotlin job. Those counts describe that revision,
not a permanent coverage target. See
[run 42336405699](https://github.com/0cwa/collieim-android/actions/runs/42336405699).
New runs record their own extraction evidence.

The release route reconstructs Collie with `tools/patches/release.py` and verifies
the resulting tree. `tools/patches/build.py` then requires verified native
integration before invoking Gradle. The complete public native input/build route
is not yet available for this hosted scan. Replacing that route with the raw
Signal checkout, stubs or unverified dependencies would produce misleading
application coverage. Using Java's `build-mode: none` would also omit Kotlin.

A future Java/Kotlin job must reconstruct the selected Collie profile, verify its
source-tree identity, initialize CodeQL against that source root, and compile it
under tracing without reusing compiled task outputs. The build must retain strict
dependency verification and the project's native checks. Alert paths must refer
to the source actually analyzed; a reconstructed file's line numbers may not
match the baseline file at the same path on GitHub. The source publication work
must account for that mapping. No such application job is claimed here.

## JNI extraction build

`tools/codeql/build_jni.py` runs the existing `app/jni/Android.mk` with the Android
NDK pinned by both `gradle/libs.versions.toml` and `molly-build.json`, currently
`28.0.13004108`. It builds and links `libnative-utils.so` for all four ABIs declared
in `app/jni/Application.mk`: `armeabi-v7a`, `x86`, `arm64-v8a` and `x86_64`.
The JNI header is parsed through the C++ translation unit. Both files must appear
in extraction coverage; the header is not separately compiled as C.

The target API comes from the current Gradle `minSdk`, currently 23. It explicitly
overrides the historical makefile's `android-19`, which the current NDK no longer
supports. The compile uses real NDK JNI, Android libc and architecture headers.
An ordinary host JDK compilation or placeholder header is not a substitute.

The helper also points the extractor's documented `USR_INCLUDE` fallback at the
same Android sysroot. Otherwise CodeQL's EDG frontend can append host
`/usr/include` after the Android include list, allowing bionic's `include_next`
to pull in glibc headers which the Android compiler itself does not use. This
keeps the analysis on the actual target headers; it does not disable errors.

The workflow installs the pinned NDK before CodeQL initialization, selects
`build-mode: manual` for `c-cpp`, and invokes the helper after initialization:

```sh
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --install "ndk;28.0.13004108"
# Initialize CodeQL with language c-cpp and build-mode manual here.
python3 tools/codeql/build_jni.py \
  --ndk "$ANDROID_HOME/ndk/28.0.13004108" \
  --output "$RUNNER_TEMP/codeql-jni"
```

The output directory must be new and outside the source and NDK trees. The build
forces recompilation and disables NDK ccache use so CodeQL observes the real
compiler invocations. It selects the runner's GNU Make through the supported
`GNUMAKE` override, retaining CodeQL's tracing environment. The helper rejects a mismatched NDK, missing JNI headers,
inconsistent release/Gradle pins and an unrecognized ABI list. Its `build.json`
records the actual compiler build command, API, NDK revision and binary hashes.
These are disposable analysis artifacts, not release artifacts or evidence of a
full application build.

## Reviewing results and coverage

`tools/codeql/report.py` reads the SARIF produced by CodeQL and writes a JSON report
and a Markdown summary. It exits nonzero for:

- Any unreviewed SARIF finding, including `note` or `none` level results, baseline
  results or suppressed results. The gate does not exempt tests or upstream code.
- Diagnostic warnings or errors, unsuccessful or missing invocation records, or
  missing expected files in the selected language's extraction baseline.
- Positive C/C++ compiler-error or failed-include metrics, which CodeQL can emit
  without any warning notification even when it reports all files extracted.
- A C/C++ extractor summary reporting failed compilations, even when another ABI
  extracted all the expected files and the summary itself has only note level.
- Incremental analysis, including diff-informed PR queries and overlay runs,
  even when all expected files were extracted and no findings were returned.
- Empty, missing, invalid or wrong-language analysis evidence.

This workflow intends to evaluate the selected queries across the whole checked-out
repository for each configured language, including on pull requests. The pinned
CodeQL Action normally enables diff-informed PR queries, which can limit findings
to changed ranges. The workflow explicitly disables that mode, and the report
retains and rejects SARIF `run.properties.incrementalMode` if it nevertheless
appears. Full query scope and successful file extraction are separate evidence;
neither establishes coverage of the reconstructed Collie application described above.

CodeQL metrics under `run.properties.metricResults` are retained separately from
findings. Lines-of-code metrics and their baselines are not file coverage
percentages. The report counts actual successful-extraction diagnostic locations
and compares them with expected-extraction locations for the selected language.
Other languages can appear in SARIF baseline diagnostics without having been
analyzed; the report does not count them as scanned. When the SARIF does not record
an expected-file baseline, its denominator is explicitly unknown. The CodeQL
Action may omit that baseline on pull requests; an unknown denominator must not
be described as complete file coverage.

Problem and path-problem findings retain rule IDs, resolved rule levels, primary
locations, related locations and code-flow locations in JSON. The source SARIF
remains the full evidence, including the query metadata and exact messages.

Example for a downloaded Python analysis:

```sh
python3 tools/codeql/report.py results/python.sarif \
  --language python --output audit/python.json --summary audit/python.md \
  --reviews .github/codeql/reviewed-findings.json --source-root .
python3 -m unittest discover -s tools/codeql -p 'test_*.py'
```

Fix the cause of an alert and verify it disappears from a fresh scan of the fixed
revision. A finding in a test may indicate an unsafe helper that should be fixed
or made explicit, rather than a reason to exclude all tests.

### Exact false-positive reviews

`.github/codeql/reviewed-findings.json` records individually reviewed false
positives without modifying SARIF or excluding queries. A review matches one
finding's language, category, CodeQL version, rule, severity, query kind and
precision, message and complete location. It also binds the SHA-256 of the entire
source file. Changed source bytes require a new review. A changed finding or tool
version is not accepted by the previous review, and duplicate matches fail.
Extraction failures cannot be reviewed away. Raw, reviewed and unreviewed counts
remain separate in the audit; the original result remains visible in SARIF and
any code-scanning upload.

The initial review covers `actions/untrusted-checkout/medium` in
`build-artifacts.yml`. The query treats its input named `ref` as a possible
pull-request head. This workflow runs only on `workflow_dispatch`: a maintainer
with write access chooses the source; neither a pull-request event nor a fork
contributor supplies it. Checkouts do not persist credentials, the job token has
read-only contents access, and `cache-mode: read` prevents the selected build from
writing shared Actions caches. BuildKit cache export is also removed.

This is still a trusted-source operation: nightly builds receive `NIGHTLY_URL`.
The dispatcher must review the selected source. The review addresses the query's
untrusted-pull-request classification; it does not establish that arbitrary build
inputs are safe. The exact rationale is included in every matching audit.

The CodeQL action is pinned to an immutable commit and uses its `linked` bundle,
so the tool and queries used for a review are reproducible. Dependabot proposes
weekly action updates; new versions must pass the same gate and any affected
false-positive review must be reconsidered.

## Default-setup migration

GitHub rejects CodeQL SARIF uploads from advanced workflows while default setup
is enabled. The workflow therefore supports a staged transition: run the advanced
analysis with `upload: never`, retain its SARIF and audit artifacts, and keep the
existing default scan active during review. `upload-database: false` also prevents
database publication during this stage.

After the replacement workflow is approved and available on main, switch the
repository to advanced setup and enable the workflow's explicit upload setting.
The repository variable `CODEQL_ADVANCED_UPLOAD=true` expresses that completed
configuration change; it does not change GitHub's default-setup setting itself.
Verify a successful uploaded analysis for every category and compare the alerts
with the recorded baseline. Preserve category identities for equivalent scans;
review any obsolete configurations separately instead of making alerts vanish
by changing their category. This workflow does not modify the repository setting
or dismiss alerts.

The read-only snapshot helper records open CodeQL alerts and recent analyses.
An unavailable configuration endpoint must remain an explicit unknown state,
not be interpreted as evidence that default setup is disabled.

## References

- [CodeQL query suites](https://docs.github.com/en/code-security/concepts/code-scanning/codeql/codeql-query-suites)
- [CodeQL for compiled languages](https://docs.github.com/en/code-security/how-tos/find-and-fix-code-vulnerabilities/manage-your-configuration/codeql-for-compiled-languages)
- [Default setup blocks CodeQL SARIF uploads](https://docs.github.com/en/code-security/reference/code-scanning/sarif-files/troubleshoot-sarif-uploads/default-setup-enabled)
- [CodeQL analyze action inputs](https://github.com/github/codeql-action/blob/v4/analyze/action.yml)
- [Pinned action diff-informed feature flag](https://github.com/github/codeql-action/blob/33d7b85da1a04171e211befe2c739303b9a4c066/src/feature-flags.ts)
- [Pinned action incremental SARIF reporting](https://github.com/github/codeql-action/blob/33d7b85da1a04171e211befe2c739303b9a4c066/src/analyze.ts)
- [GitHub Actions cache access syntax](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax#jobsjob_idcache-mode)
- [Android NDK r28 and its revision](https://github.com/android/ndk/releases/tag/r28)
- [Android NDK target configuration](https://developer.android.com/ndk/guides/other_build_systems)
- [EDG include search and `USR_INCLUDE`](https://edgcpp.org/doc/ext_intf/)

## Consolidated security changes and source cutover

CODEQL-CONSOLIDATION.md records the42/44 unique-change disposition and current
coverage boundaries. Keep CODEQL_ADVANCED_UPLOAD as the single opt-in control;
this work does not enable it. Screenshot generation and publication now use
separate workflows: generate on a reviewed feature branch, inspect its data
artifact, then dispatch publish-screenshots.yml on main with exact run/attempt/
artifact/branch/head identities. No candidate Gradle code receives the publisher's
write token or private build-cache values. The trusted publisher permits only
bounded PNG/LFS changes and refuses a moved branch.

After source cutover, engineering owns the same audit/parser tools. The new
application source workflow must pin that reviewed engineering revision as an
explicit publication layer; it must not copy old baseline Spinner/JNI assumptions.
Java/Kotlin application analysis is still NOT RUN until the exact public native
inputs and a traced direct-source Android build are actually verified.


Pull request analysis reads the finding review manifest and report tool from the
base commit; push and manual runs use the commit being analyzed. This prevents a
pull request from approving its own findings by editing the manifest or audit
tool. During first-time adoption, when the base commit has no manifest yet, the
workflow supplies an empty review list, so findings remain unreviewed and fail
the report gate. Workflow YAML can still be changed by a pull request, so repository owners
must protect workflow changes with external review and branch protection rules.
This workflow does not make its own definition tamperproof.
