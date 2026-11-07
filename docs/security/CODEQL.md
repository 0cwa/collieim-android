# CodeQL

`.github/workflows/codeql.yml` runs the `security-extended` suite on pull requests,
pushes to `main`, a weekly schedule, and manual dispatches. The checked-in
configuration keeps the default security queries, adds the extended security
queries, and does not exclude source paths or disable rules.

The workflow first works alongside GitHub's existing default setup: it produces
SARIF artifacts and enforces its own result gate. Uploading those results to the
code-scanning dashboard requires the explicit transition below. Opening or
merging the PR does not change repository security settings.

## Coverage

| Analysis | Source analyzed | Important limits |
| --- | --- | --- |
| GitHub Actions | Workflows and supported action definitions in this checkout | Static analysis does not prove the safety of every third-party action or repository setting. |
| Python | Checked-in release, source-proof, native-build, and other Python tools and tests | No custom library models or additional local-source threat models are claimed. |
| JavaScript/TypeScript | Checked-in scripts and supported templates, including the spinner and debug-log viewers | CodeQL's extraction rules still determine which generated or minified library code it models. |
| C/C++ | Checked-in native source, using `build-mode: none` | This does not analyze downloaded native binaries, RingRTC, libsignal, or other external source trees. No-build extraction makes dependency/compiler assumptions. |

**Java and Kotlin application code is not covered by this workflow.** On the
current patch-based `main`, the source to release must first be reconstructed
with `tools/patches/release.py --profile collie`. A useful Android scan must then
trace a successful build of that exact source and its verified native inputs.
`tools/patches/build.py` requires the pinned native artifact integration; the
hosted build route does not yet supply those inputs. Selecting Java's no-build
mode would exclude Kotlin and would not establish application coverage.

When that build becomes available, add a separate `java-kotlin` manual-build
job. Reuse the source proof and native verification, run the actual compiler
after CodeQL initialization, prevent compilation being skipped by task caches or
an existing Gradle daemon, and inspect Java and Kotlin extraction diagnostics.
Do not substitute different native dependencies to obtain a green scan. A future
source-publication cutover also needs a review of this matrix and its required
source paths.

## Result gate and artifacts

`tools/codeql/check_sarif.py` checks the raw SARIF after analysis. It fails for:

- Missing, malformed, or empty analysis output, absent query metadata, or an
  unsuccessful CodeQL invocation.
- Warning/error analysis diagnostics, including source files that could not be
  parsed. Informational diagnostics, such as the C/C++ no-build limitation,
  remain in the report without being treated as extraction failures.
- Missing successfully extracted sources or a missing representative source
  file selected in the matrix.
- Any finding without a matching, current, explicit review record.

The gate does not ignore old alerts, lower severities, or SARIF suppressions. It
reports raw, reviewed, and unreviewed finding counts separately. GitHub's normal
new-alert check and dashboard dismissals do not determine its exit status.

Full analysis is intentional for these four relatively small language sets. The
workflow disables diff-informed query filtering and overlay database reuse and
requests extraction diagnostics and file coverage on PRs. The relevant controls
are verified against the pinned CodeQL Action revision. Review them along with
the extraction evidence when updating that action.

Every matrix job retains its unmodified SARIF and `check.json` as a
`codeql-<language>-<run>-<attempt>` artifact for 30 days, including failed result
checks. The report lists query counts, findings, successfully extracted paths,
diagnostics, and the CLI version. A successful local gate is evidence about
that analyzed revision; it is not proof that default-branch dashboard alerts
have closed.

The Actions job also reads all pages of **open CodeQL alerts on the default
branch** using its built-in token. `default-branch-alerts.json` retains only alert
numbers, rule IDs, severity, state, locations, links, and analysis/ref/commit
metadata. It excludes source snippets, alert messages, and dismissal comments.
Fork PRs explicitly record the inventory as unavailable. An unavailable inventory
does not mean zero alerts. This is a read-only snapshot taken during that run,
not a claim that the PR has already changed main's alerts.

### Reviewed findings

`.github/codeql/reviewed-findings.json` is a narrow gate disposition record. It
does not filter the query suite, rewrite SARIF, suppress an upload, or dismiss an
alert on GitHub.

A record applies to exactly one result, matching the language, rule ID, sole
primary source location, and CodeQL primary line fingerprint. It additionally
requires the SHA256 of the complete reviewed source file. Changing that file,
changing the result's fingerprint, adding a primary location, or producing an
additional finding fails the gate. A record whose finding disappears also fails
so the obsolete record must be removed. The reason for accepting a result is
kept with the record and is reviewable in the same PR.

The intentional manually dispatched legacy build can select another source
revision. CodeQL reports `actions/untrusted-checkout/medium` for that checkout,
even though it runs under the workflow's reviewed manual-run boundary. The
workflow is restricted to a read-only token, does not persist checkout
credentials, and does not receive signing or repository-write credentials.
It still receives nightly configuration from repository secrets. The maintainer
dispatching that workflow must trust the selected source revision with those
values; this is not a safe route for automatically running arbitrary fork code.
The reviewed disposition is bound to the complete hardened workflow, including
its cache behavior; it must be reassessed if any of that changes. The original
finding remains visible in SARIF and in the code-scanning dashboard once
advanced uploads are enabled.

## Screenshot workflow boundary

`!screenshots` comments still authenticate the requester and resolve the PR
branch, but now reply with a manual-dispatch command. They do not execute the
candidate's Gradle build. The maintainer runs `update-screenshots.yml` on the
chosen non-default branch; `main`, the default branch, and `8.*` release branches
are rejected. The generation job checks out that dispatch's immutable
`github.sha` and has a read-only repository token.

This distinction also protects Actions caches. A build started directly from an
issue-comment event has the default branch's cache scope, even if ordinary cache
uploads are disabled. Branch dispatch gives candidate code its own branch's
scope instead. The separate publisher validates the generated screenshot data
and uses a fresh bare Git repository and an exact branch lease; it never checks
out or runs the candidate tree with a write token. See
`.github/scripts/screenshot_artifacts.py` and its adversarial tests for the data
and publication checks.

## Transition from default to advanced setup

GitHub rejects advanced CodeQL uploads while default setup is enabled. The
workflow therefore treats an unset `CODEQL_UPLOAD_MODE` repository variable as
`artifacts`. Setting it to `artifacts` explicitly has the same effect. Analysis,
the local gate, and artifacts continue to run in this mode.

The `GET /repos/{owner}/{repo}/code-scanning/default-setup` endpoint requires
repository administration permission, which the built-in Actions token does not
have. The workflow does not request an administrator token or infer that default
setup is disabled from a failed API call. The ordinary code-scanning alert list
uses `security-events` permission and is read independently.

After the PR is reviewed and the replacement workflow has produced its complete
four-language evidence:

1. Check the current default-branch alert inventory and the PR's raw SARIF,
   source fixes, reviewed disposition, and extraction diagnostics.
2. In repository **Settings → Advanced Security → CodeQL analysis**, switch
   from default to advanced setup. GitHub may label the intermediate action
   **Disable CodeQL**; the checked-in workflow is the replacement. Do not leave
   the repository with neither analysis path running.
3. Set the repository Actions variable `CODEQL_UPLOAD_MODE` to exactly
   `advanced`. This enables normal SARIF uploads on PRs and `main`. Any other
   nonempty value fails configuration validation.
4. Run the workflow on `main` and confirm all four language jobs, extraction
   checks, uploads, and server processing succeed. Re-enable the workflow if a
   previous default-setup transition disabled it.
5. Inspect the dashboard's configurations and source revisions. The workflow
   retains the existing `/language:<language>` categories, but an old workflow
   configuration can still retain stale alert state. Review stale configurations
   individually; do not mass-dismiss findings to make the migration look clean.
6. Require the new workflow's result checks under the repository's normal branch
   protection policy if they should block merges.

To return to default setup, set `CODEQL_UPLOAD_MODE=artifacts` before enabling
default setup again. Confirm default scans actually run; GitHub can disable
preexisting advanced workflows during that transition. Keep the reviewed source
fixes and evidence.

## Updating and reproducing the analysis

The workflow pins its actions to immutable commits. Dependabot is configured to
propose weekly updates for `github/codeql-action`; it does not automatically
merge them. Review new queries, tool versions, extraction diagnostics, and the
reviewed finding on each update. `security-extended` can find more vulnerabilities
than the default suite, but its additional queries can also have lower precision.
A larger query set is not a guarantee of higher accuracy.

The reviewed baseline used CodeQL CLI 2.27.4, supplied by CodeQL Action 4.39.3.
For a local replay with the same official bundle, use a clean checkout and keep
the database/results outside the source tree. For example:

```sh
codeql database create /absolute/tmp/collie-codeql-python \
  --language=python --build-mode=none --source-root=. \
  --codescanning-config=.github/codeql/codeql-config.yml
mkdir -p /absolute/tmp/collie-codeql-python-results
codeql database analyze /absolute/tmp/collie-codeql-python \
  --format=sarif-latest --sarif-include-diagnostics \
  --sarif-add-baseline-file-info --sarif-category=/language:python \
  --output=/absolute/tmp/collie-codeql-python-results/python.sarif
python3 tools/codeql/check_sarif.py \
  --sarif-dir /absolute/tmp/collie-codeql-python-results --language python \
  --require-source tools/patches/release.py --source-root . \
  --reviewed-findings .github/codeql/reviewed-findings.json \
  --report /absolute/tmp/collie-codeql-python-results/check.json
python3 -m unittest discover -s tools/codeql -p 'test_*.py' -v
python3 -m unittest discover -s .github/scripts -p 'test_*.py' -v
node --test tools/codeql/test_spinner.cjs
```

Use the same CLI, suites, source tree, and analysis scope for before/after
comparisons. Save the default-suite comparison separately when demonstrating
that preexisting findings were fixed, then review the additional extended-suite
results. Never compare a filtered scan with an unfiltered baseline and call the
difference a source fix.

## References

- [GitHub: CodeQL query suites](https://docs.github.com/en/code-security/concepts/code-scanning/codeql/codeql-query-suites)
- [GitHub: default and advanced configuration interactions](https://docs.github.com/en/code-security/reference/code-scanning/troubleshoot-analysis-errors/results-different-than-expected)
- [GitHub: configuring advanced setup](https://docs.github.com/en/code-security/how-tos/find-and-fix-code-vulnerabilities/configure-code-scanning/configuring-advanced-setup-for-code-scanning)
- [GitHub: code-scanning REST endpoints and permissions](https://docs.github.com/en/rest/code-scanning/code-scanning)
- [GitHub: Kotlin files require a build](https://docs.github.com/en/code-security/reference/code-scanning/troubleshoot-analysis-errors/kotlin-detected-in-no-build)
- [CodeQL Action 4.39.3: supported upload modes](https://github.com/github/codeql-action/blob/33d7b85da1a04171e211befe2c739303b9a4c066/analyze/action.yml)
