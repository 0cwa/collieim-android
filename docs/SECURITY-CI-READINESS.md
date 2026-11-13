# Preparing the security CI gate

Status: implementation prepared for review; repository rules remain unchanged.
The native provenance work must retain its own evidence and acceptance decision.
A successful APK build and host test run establish application build/test readiness;
independent verification of the production native libraries needs the separate
source, toolchain, rebuilt artifact, and comparison records.

## Reuse the existing host test repair

[PR #104](https://github.com/0cwa/collieim-android/pull/104) supplies test-only Linux
JNI built from Collie's pinned libsignal source and proxy patch. It also repairs
the independent fixture failures: legacy preferences initialization, cleanup of
real dependencies, static RemoteConfig mocks, and dispatcher scheduling.

Its [run 43127267393](https://github.com/0cwa/collieim-android/actions/runs/43127267393)
at `c692de5cc7fa64bea214db8d3b8cb35aea8f0d85` executed 7,841 passing cases across
22 Android unit-test tasks, with zero failures/errors and 66 skipped cases. The app task ran
4,863 passing cases. The run retained XML reports and the host native receipt.
These are historical results for that PR's source. The current candidate must
run again after integration with the latest `main` and the native verification work.

That command omitted the JVM-only `:core:util-jvm:test` and
`:lib:libsignal-service:test` tasks. Their modules use `java-library` and Kotlin
JVM plugins, so the `testDebugUnitTest` selector does not run them. This preparation
adds both tasks explicitly, including `ResettableLazyTests` concurrency regressions
and `ControlMessageListTest` per-device control-message routing regressions. The
two added JVM tasks have no passing result in the historical run; their current
results remain required before activation.

Do not replace the production Android AAR with the Linux test library. Gradle's
`collieHostJniDir` property adds the generated resource only to JVM `Test` tasks.
When that property is set, test tasks cannot reuse output-cache entries or
up-to-date results: they execute again for the candidate. Compilation tasks keep
their normal caching behavior.

## What the prepared checks require

| Check | Evidence required |
| --- | --- |
| `Unit tests` | The Android test selection and the two explicitly added JVM modules succeed; all 24 named tasks execute tests; every reported failure/error rejects the result. |
| Security suite coverage inside `Unit tests` | Each of the 17 named suites in `.github/ci-required-tests.json` meets its reviewed minimum and has zero skipped cases. |
| `Unsigned production build` | The same candidate builds `assembleProdWebsiteRelease` through the release recipe and retains its APK hashes, commit, and tree. |
| `Security readiness` | Both preceding jobs conclude `success`. Missing, skipped, failed, or cancelled dependencies fail this stable aggregate check. |
| Release publication | A trusted main-branch CI run must pass for the exact tagged source, including the unit, production-build, and aggregate checks. |

Both jobs use the default checkout for the triggering event. For pull requests
this tests GitHub's proposed merge commit. For pushes to `main` it tests the
actual main commit, which supplies the exact-source release evidence. A manual
run on `main` can regenerate that evidence. Evidence from a previous PR head
does not establish that a later release commit passed.

The release verifier reads the newest eligible run and its current attempt.
If a failed-jobs-only rerun omits one of the required jobs from that attempt,
rerun the complete workflow. Do not combine successful jobs from different
attempts to authorize a release.

The report verifier compares JUnit summary counts with individual testcases,
rejects duplicate suite reports, and retains a named skip inventory in
`build/ci/unit-test-results.json`. There are no new test exclusions. Existing
skips outside the required security suites remain visible for review. The 66
skips in the historical PR run are a recorded observation, not a future allowance.

The 22 Android task names come from that successful historical run; the two
added JVM task names come from their module configuration. This is an explicit
test selection, not a claim that every test task in the repository is covered.
The security minima come from the test methods present in the candidate source;
they require a real CI run before activation. Keep the task and suite inventory
under review when modules or tests are intentionally renamed or removed.
New tasks' failures are still rejected even before adding them to the required list.

## Activation order

1. Finish the native verification work and record which production libraries
   have independent source-to-binary evidence. Resolve differences using their
   retained reports; do not change accepted hashes to make a comparison pass.
2. Integrate PR #104 through the repository's normal reviewed, signed commit
   process. Integrate this gate preparation on the same source line. Refresh
   stale branches without losing their existing authorship or `Layer:` trailers.
3. Run `PR checks` on the exact integrated candidate. Inspect the full task table,
   all 17 security suites, the named skips, host-native receipt, and unsigned
   production artifact. A missing native input or unavailable runner is a failed
   readiness attempt; do not exclude affected test classes.
4. After the candidate is merged, require a successful `PR checks` run for that
   exact `main` SHA. Retain the run URL, attempt, tested SHA/tree, artifact digests,
   and native provenance evidence with the release review.
5. In the existing `Main changes only through pull requests` ruleset, preserve
   its current protections and add the stable `Security readiness` required
   check. Bind it to GitHub Actions when selecting the check source. Keep
   `Fast lint` and `Layer trailers`; require branches to be up to date before
   merging. Do this only after the check exists and passes on the integrated tree.
6. Exercise a draft release check for the intended signed tag. Confirm that an
   older successful commit, a skipped test job, a cancelled run, and an absent
   production build cannot authorize publishing. Keep publication blocked when
   the exact source lacks a successful run.
7. Assign an independent maintainer for security-sensitive review. Once the
   reviewer is identified and has repository access, add code-owner coverage for
   credential/account handling, cryptography, backups, locking, push-state code,
   native inputs, and workflows; require code-owner approval and dismiss stale
   approvals on new commits. A sole author's own approval cannot satisfy this.

This document does not apply repository settings, select reviewers, merge a PR,
sign a release, or publish an APK. Those actions remain visible review steps.

## Coverage still required for the two application findings

The selected suites exercise existing identity handling, lock timeout behavior,
backup preference serialization, encrypted-push contracts, enrollment persistence,
push lifecycle transitions, per-device control-message routing, and concurrent
reset of cached state. Two audit cases still need application fixes and
their own regression coverage:

- **Relay revocation:** a stored linked-device ID remains present under a changed
  or undecryptable name. Disabling or replacing the relay must request server
  deletion; a deletion failure must preserve a durable retry record.
- **Molly restore:** a fixture uses the literal source preference filename
  `im.molly.app`. Restored biometric lock, unknown-contact blocking, logging, and
  lock timing must be observed through Collie's public getters and lock behavior.

The existing `MollyBackupPreferencesTest` round trip does not establish this
cross-application migration case. Add the new regressions to the required suite
inventory when their application fixes are ready. Treat a passing readiness gate
as evidence for the tests it actually executes.

## Local verification

The report-gate tests require Python only and exercise missing modules, missing
security suites, skips, failures, malformed XML, duplicate reports, and false
summary counts:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tooling/ci -p 'test_*.py'
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tooling/host-tests -p 'test_*.py'
```

For the configured Android and JVM test selection, follow
`tooling/host-tests/README.md`, then run:

```sh
./gradlew --no-daemon --continue \
  -PcollieHostJniDir=build/host-tests/resources \
  :app:testProdWebsiteDebugUnitTest testDebugUnitTest \
  :core:util-jvm:test :lib:libsignal-service:test
python3 .github/scripts/check-unit-test-results.py \
  --json-output build/ci/unit-test-results.json
```

Running the Python gate tests alone does not supply Android runtime results.
