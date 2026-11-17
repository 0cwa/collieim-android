# Signal 8.35.2 upgrade

This upgrade applies the binary, rename-aware diff between these upstream tags to Collie's existing source tree:

| Tag | Commit |
| --- | --- |
| `v8.35.1` | `a74583b01e260944f699c4da03ba4e8ce04de32b` |
| `v8.35.2` | `d507a800a61cd5819a3670fe71e84e103efa7bda` |

No upstream history is merged. The upstream range contains five commits by Greyson Parrelli and Alex Hart.

## Changes retained

- Canonical Signal version `8.35.2`, code `1778`. Collie's revision remains `1`, giving source builds version code `177801` and version name `8.35.2-1-port-unvalidated`. The unvalidated CI version name now derives from these values instead of retaining the old hardcoded `8.35.1` label. The stale `8.35.1-3-rc2` / `177705` release overrides are removed so they cannot mislabel the upgraded source. A separate release-prep PR must choose the next release identity and provide its changelog and release notes, as described in `docs/RELEASING.md`.
- The Colombian peso (`COP`) network-amount conversion correction and both upstream regression tests in `FiatMoneyTest`.
- The storage-service fallback IP update, through the existing network configuration.
- Upstream startup/baseline profiles and applicable translation updates.

## Collie adaptations

The only source-code conflict was the upstream donation-menu label change. Collie has removed that menu item, so the existing toolbar is retained. The matching donation strings remain absent in all locales. The upgrade adds no donation or payment entry point.

Incoming translations that describe this app or its screen contents use the Collie name, including the Persian and Tamil spellings of the upstream name. References to the Signal network and Signal Secure Backups retain their service names. Existing translations outside the upstream changes are not rewritten.

The upgrade does not change the native dependency catalog, proxy implementation, Google/Firebase dependencies, or build-input pins. No native library version bump or rebuild is required by this upstream diff.

## Validation

The resolved diff passes `git diff --check`, all 100 changed XML resource files parse, and no conflict markers remain. The toolbar and native dependency catalog are byte-identical to the pre-upgrade Collie source.

Run the upstream currency regression tests with:

```sh
./gradlew --no-daemon :core:util:testDebugUnitTest \
  --tests org.signal.core.util.money.FiatMoneyTest
```

The initial local attempt could not start Gradle: the required Gradle 9.6.0 distribution was not cached and its download failed with `Network is unreachable`. This is not a passing test result. The PR must record CI results for the full stack and a debug APK build before merge; a source-only review does not establish Android runtime behavior.
