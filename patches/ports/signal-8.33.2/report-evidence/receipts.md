# Report action receipts

These records describe the actual checks, not independent reruns on each
report profile. Replay proves recorded file input and output blobs. It does not
prove the full tested source tree was identical to a report profile.

## Formatting checks

Sixteen changes were checked in a PR4-derived source context at tree
`70f2eba69967c4a3ae82dd9204999feb1bd70f9b` with the tested candidate actions
applied; the complete tested tree is not pinned. This is not a resource-link
run on PR3. Each associated XML-event log records `XML_EVENTS_EQUAL`. Each
associated resource log shows `:app:processProdWebsiteDebugResources` executed
and `BUILD SUCCESSFUL`, exit 0. The file blob identities and sequence are in
report metadata and the versioned action patches.

The command used JDK 21, the configured Android SDK, and `agent-heavy --
./gradlew --no-daemon --no-parallel --no-configuration-cache --max-workers=1
--dependency-verification=strict --init-script <test-worker-heap.init.gradle>
-Pkotlin.compiler.execution.strategy=in-process
'-Dorg.gradle.jvmargs=-Xmx5g -Xms256m -XX:MaxMetaspaceSize=1g -Dcom.android.tools.r8.threadCount=1 -XX:+UnlockExperimentalVMOptions -XX:hashCode=3'
:app:processProdWebsiteDebugResources`. Checks 15 and 16 used `--rerun`; their
original resource logs showed `UP-TO-DATE` and are superseded by the forced
executions. Check 15 was tested with change 16 reversed; check 16 was then
reapplied and checked separately. Source records in task scratch are
`xml-format-gates.tsv`, `xml-format-gates-notes.md`, and the numbered XML and
resource logs named by the metadata. Passing removal checks do not establish
necessity of any current hunk.

## Lexical checks

Seventy baseline files and 74 sequential restorations were individually
compiled using `aapt2 compile -o <output-dir> <relative-resource-path>` with
AAPT2 `9.4.0-15978811-linux`. Each recorded command exited 0 with empty output.
The inputs are the file blobs in the metadata; the baseline and final file
blobs match both report profiles. These are file-scoped compilation checks,
not full resource-link checks. The source record in task scratch is
`XML-LEXICAL-CHECK.tsv`, whose phase, hunk name, path and input blob identify
each command. The intermediate file blobs were reconstructed from the exact
recorded transformations and verified against that record; the versioned
patches make their replay independent of local dangling objects.

## Whole-file deletion inventory

The following tree-membership check classifies whole-file deletions from mechanical tree `759ef1c309d93aa7e4ac7217b144c04545562f11` to both PR3 source tree `129bd88f3f04dfb6b80a93b96ec026f6a5cd3010` and PR4 source tree `2b0ba46b23fd27974b8cefbd36e0d91791b525bd`. Inputs are old Signal `c47a74ba0961903eeaeda6d22347a990056c3e83`, new Signal `6e9dddc01f1b26e608ac683807c51e139c141ed2`, and Molly `0e202dda90785771ff8c87526eaaf30c655f54f3`. After running `python3 tools/patches/prepare_port_report.py` on both stack 3 and stack 4 so both target trees are available in the shared object store, reproduce the inventory and classification check from the repository root:

```sh
python3 - <<'PY'
import collections, json, subprocess
from pathlib import Path

def git(*args):
    return subprocess.check_output(['git', *args])
def paths(ref):
    return set(filter(None, git('ls-tree', '-r', '-z', '--name-only', ref).split(b'\0')))
old, new, molly, mechanical = map(paths, (
    'c47a74ba0961903eeaeda6d22347a990056c3e83',
    '6e9dddc01f1b26e608ac683807c51e139c141ed2',
    '0e202dda90785771ff8c87526eaaf30c655f54f3',
    '759ef1c309d93aa7e4ac7217b144c04545562f11'))
targets = [paths(x) for x in ('129bd88f3f04dfb6b80a93b96ec026f6a5cd3010',
                              '2b0ba46b23fd27974b8cefbd36e0d91791b525bd')]
deleted = mechanical - targets[0]
assert deleted == mechanical - targets[1]
annotations = json.loads(Path('patches/ports/signal-8.33.2/report-metadata.json').read_text())['deletions']
assert deleted == {p.encode() for p in annotations}
absent_roots = (b'demo/media-keyboard/', b'demo/list-detail/', b'demo/device-transfer/', b'lib/billing/')
assert all(not any(p.startswith(root) for p in target) for root in absent_roots for target in targets)
counts = collections.Counter()
for p in deleted:
    if p in old and p not in new:
        cls = 'Signal path removed'
    elif p in old and p in new and p not in molly:
        cls = 'Both Signal trees; absent in Molly'
    elif p not in old and p in new and p not in molly:
        cls = 'New Signal path under absent root' if p.startswith(absent_roots) else 'New Signal path under retained root'
    else:
        assert p in molly
        cls = 'Molly-present additional path'
    if cls == 'New Signal path under retained root':
        root = b'/'.join(p.split(b'/')[:2]) + b'/'
        assert all(any(q.startswith(root) for q in target) for target in targets)
    assert annotations[p.decode()]['classification'] == cls
    counts[cls] += 1
print(dict(sorted(counts.items())))
raw = git('diff', '--no-renames', '--numstat', '-z', '759ef1c309d93aa7e4ac7217b144c04545562f11', '129bd88f3f04dfb6b80a93b96ec026f6a5cd3010')
rows = [r.split(b'\t', 2) for r in raw.split(b'\0') if r]
selected = [(a, d, p) for a, d, p in rows if p in deleted]
print('paths, text, binary, deleted lines:', len(selected), sum(a != b'-' for a, _, _ in selected), sum(a == b'-' for a, _, _ in selected), sum(int(d) for a, d, _ in selected if a != b'-'))
raw = git('diff', '--no-renames', '--raw', '--no-abbrev', '-z', '759ef1c309d93aa7e4ac7217b144c04545562f11', '129bd88f3f04dfb6b80a93b96ec026f6a5cd3010').split(b'\0')
print('old modes:', dict(collections.Counter(raw[i].split()[0][1:].decode() for i in range(0, len(raw)-1, 2) if raw[i+1] in deleted)))
PY
```

Observed output: `Both Signal trees; absent in Molly: 250`, `Molly-present additional path: 6`, `New Signal path under absent root: 85`, `New Signal path under retained root: 321`, `Signal path removed: 111`; 773 paths, 745 text, 28 binary, 45,285 deleted text lines, all old mode `100644`. The four absent roots contain no target paths; retained roots have surviving target paths. The category predicates classify paths, not why they disappeared. Molly path absence does not prove an intentional exclusion; old Signal path removal does not prove behavior preservation; a retained root does not establish an individual file's intent. The six Molly-present paths include tests and possible relocated files, whose full behavior remains unverified. Screen lock, proxy, and shake-report paths merit further caller and policy checks; this inventory does not prove runtime equivalence or deletion necessity. Partial-file exclusions remain unmeasured.

## Current PR3 Settings negative checks


Source commit 6bf19c561f1c123269fdeb5d693bff154684998a, tree 129bd88f3f04dfb6b80a93b96ec026f6a5cd3010. SettingsValues.kt blob ec0c3b469a2137adede241c82a0e249dc78fb7aa, SHA-256 34d0b24a6318a7a630d562e37fcfca33171da0f8f0d8ab989b0f44aae5824544; LegacySharedPrefs.kt blob 7b339e26b7c3aa43d5310ea79b2418cf5dd7091b, SHA-256 64365414e8310f6b929eedb9f416a1a3329e74733ad570e4a85a91d0677ca48c.

Test input SHA-256: MollySettingsMigrationTest.kt 683ca2adbd9acfd0260a5c8e9dfb2195a5c5ca8724a0f5217ac95e7b52e91e47; MollyBackupPreferencesTest.kt 171017a051794cf25c3dcb7ee93ee6822baf36516ac4c7caf2ba0ef7e13c5f2e; MollyLanguageBackupTest.kt 6afc286c7e3f0e98cd344bdf6ccb3cf965e770c7c504721da05574211adc2e0b. PR3 backup preferences input differs from PR4.

Baseline Gradle exit 0: 10 tests passed, 0 skipped (6 migration, 3 backup preferences, 1 language backup). Command, environment and selectors are recorded in run.sh, matching the baseline; agent-heavy used one Gradle worker, in-process Kotlin, strict dependency verification, JDK 21 and existing build directory. Disk check OK before each candidate. Each candidate was applied independently and restored before the next; all compiled and reached tests.

- 1c87d9e382a99fab53ad: migration copy removal, migration.patch SHA-256 e4747cdeaae8f845eaa9d13bfaa6d0f2db58034b0ef7b553cd9c453d69c00631. Exit 1, 1/10 failed: language backup on upgrade expected de, got null.
- 9c616143ddc96d74a2bb: partial setter removal of only the additional key-value write, preserving the remainder of the larger hunk, setter.patch SHA-256 82ac2920ee8e13f19ecb4636020b295957e592fb701b773f0b5225778b52c24e. Exit 1, 1/10 failed: language backup after change expected fr, got de.
- 66d760f60b510768d21a: encrypted reader replaced by fully qualified androidx.preference.PreferenceManager default reader, a semantic substitution rather than a literal inverse to avoid touching a separate import hunk. reader.patch SHA-256 ee689534b0f88b5557fafddf689cfa3dc0fa5942a08524da189531c4898f0581. Exit 1, 3/10 failed: language backup expected de, got zz; migration language expected de, got en; encrypted preference migration assertion failed.

All runs had 0 skipped. Logs, XML-derived failure summaries and exact candidate diffs are adjacent. These targeted unit checks do not establish end-to-end encrypted backup behavior. No source change retained. After checks, original PR4 commit bf34633283d6cd01c8f6bc615ecf2367bd92d780, tree 2b0ba46b23fd27974b8cefbd36e0d91791b525bd restored; status, unstaged diff and staged diff clean.

The candidate patches were applied independently. With JDK 21 and the configured Android SDK, the gate was `agent-heavy -- ./gradlew --no-daemon --no-parallel --no-configuration-cache --max-workers=1 --dependency-verification=strict --init-script <test-worker-heap.init.gradle> -Pkotlin.compiler.execution.strategy=in-process '-Dorg.gradle.jvmargs=-Xmx5g -Xms256m -XX:MaxMetaspaceSize=1g -Dcom.android.tools.r8.threadCount=1 -XX:+UnlockExperimentalVMOptions -XX:hashCode=3' :app:testProdWebsiteDebugUnitTest --tests org.thoughtcrime.securesms.keyvalue.MollySettingsMigrationTest --tests org.thoughtcrime.securesms.backup.MollyBackupPreferencesTest --tests org.thoughtcrime.securesms.backup.MollyLanguageBackupTest`.

### PR3 migration candidate patch

```diff
diff --git a/app/src/main/java/org/thoughtcrime/securesms/keyvalue/SettingsValues.kt b/app/src/main/java/org/thoughtcrime/securesms/keyvalue/SettingsValues.kt
index ec0c3b469a..1e19d5a1bd 100644
--- a/app/src/main/java/org/thoughtcrime/securesms/keyvalue/SettingsValues.kt
+++ b/app/src/main/java/org/thoughtcrime/securesms/keyvalue/SettingsValues.kt
@@ -590,9 +590,6 @@ class SettingsValues internal constructor(store: KeyValueStore, private val cont
 
     val writer = store.beginWrite()
 
-    if (!store.containsKey(LANGUAGE)) {
-      writer.putString(LANGUAGE, LegacySharedPrefs.getString(context, "pref_language", "zz"))
-    }
     if (!store.containsKey(THEME)) {
       writer.putString(THEME, LegacySharedPrefs.getString(context, "pref_theme", if (DynamicTheme.systemThemeAvailable()) "system" else "light"))
     }
```

### PR3 setter candidate patch

```diff
diff --git a/app/src/main/java/org/thoughtcrime/securesms/keyvalue/SettingsValues.kt b/app/src/main/java/org/thoughtcrime/securesms/keyvalue/SettingsValues.kt
index ec0c3b469a..ff42d698a9 100644
--- a/app/src/main/java/org/thoughtcrime/securesms/keyvalue/SettingsValues.kt
+++ b/app/src/main/java/org/thoughtcrime/securesms/keyvalue/SettingsValues.kt
@@ -251,7 +251,6 @@ class SettingsValues internal constructor(store: KeyValueStore, private val cont
   var language: String
     get() = TextSecurePreferences.getLanguage(context)
     set(value) {
-      putString(LANGUAGE, value)
       TextSecurePreferences.setLanguage(context, value)
       configurationSettingChanged.postValue(LANGUAGE)
     }
```

### PR3 reader candidate patch

```diff
diff --git a/app/src/main/java/org/thoughtcrime/securesms/keyvalue/LegacySharedPrefs.kt b/app/src/main/java/org/thoughtcrime/securesms/keyvalue/LegacySharedPrefs.kt
index 7b339e26b7..b7f56283db 100644
--- a/app/src/main/java/org/thoughtcrime/securesms/keyvalue/LegacySharedPrefs.kt
+++ b/app/src/main/java/org/thoughtcrime/securesms/keyvalue/LegacySharedPrefs.kt
@@ -68,5 +68,5 @@ object LegacySharedPrefs {
     }
   }
 
-  private fun prefs(context: Context) = SecurePreferenceManager.getSecurePreferences(context)
+  private fun prefs(context: Context) = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
 }
```

## Twelve-path Signal equality audit


Read-only source review, 2026-11-01; existing test logs were subsequently reviewed. No new build, runtime test, patch-forest inspection, or minimization gate was performed by this reviewer. Paths below are relative to `app/src/main/java/org/thoughtcrime/securesms/` unless fully qualified. Old Signal S0 = `c47a74ba0961903eeaeda6d22347a990056c3e83`; old Molly M = `0e202dda90785771ff8c87526eaaf30c655f54f3`; new Signal S = `6e9dddc01f1b26e608ac683807c51e139c141ed2`; original source trees O3 = `2533afec2fe2d7f9fc682f699fc21d7624521889`, O4 = `59fa2b2af25d82ffd11d0198d5e9e8f5b5b3eadf`; current source trees C3 = `129bd88f3f04dfb6b80a93b96ec026f6a5cd3010`, C4 = `2b0ba46b23fd27974b8cefbd36e0d91791b525bd`.

Reproduce identity for each exact path: `git ls-tree <S|O3|O4|C3|C4> -- <path>`; compare mode and full blob ID. Reproduce old delta: `git diff --no-ext-diff --unified=2 <S0> <M> -- <path>`. All twelve are mode 100644 and O3/O4 match S. Eleven also match S in C3/C4; the monitor does not (S blob c199e4a9ec4db956600cac9ea126c07fe5ce525f; both current trees bb4aa1d105c53e92f853210ab689e97e94ffd521). For current implementation evidence use `git show <C4>:<path>` and `git diff <S> <C4> -- <path>`. C3 and C4 have identical blobs for all twelve, but that identity does not establish identity of their supporting paths. The source call-site observations below were checked against C4; C3 support paths need independent verification before asserting identical behavior. Molly branch `origin/signal-8.20.4` at `5551f3023e6093fbb65214acdcc2e568b7b63132` retains old Molly blobs for all twelve (historical audit); it does not prove later refactor equivalence.

1. `net/InternetConnectivityMonitor.kt`: Molly added the old 284-line monitor relative to S0. The original Signal-equal outcome is now changed in C3/C4. In C4 `messages/IncomingMessageObserver.kt:115-124,256,280` starts OFFLINE and gates on `isOnline`; `net/ConnectivityStateCalculator.kt:47-51` ranks blocked above unvalidated, and `net/InternetConnectivityMonitor.kt:422` also uses `isOnline` for debounce. The two previously identified policy differences—fail-open observer gating and unvalidated-over-blocked precedence—are fixed in source. Record these changes as a stated reason. Existing focused run `network-positive-retry.log` succeeded; final profile runs `pr3-final-full-retry.log` and `pr4-final-full-new.log` included both test classes and succeeded. C4 `app/src/test/java/org/thoughtcrime/securesms/messages/IncomingMessageObserverConnectivityTest.kt` has two Robolectric tests with mocked sockets/monitor: initial, unavailable and unvalidated states do not connect, ONLINE connects, and loss of validation calls disconnect on both sockets. `app/src/test/java/org/thoughtcrime/securesms/net/ConnectivityStateCalculatorTest.kt` has twelve unit tests, including blocked versus unvalidated in both orders and modeled VPN/underlying-network states. Existing `network-negative-static.log` records three expected assertion failures when the changes were reversed: both observer tests and blocked precedence. These do not measure actual device VPN routing, real monitor failure/recovery or real websocket reconnection. Do not claim all twelve remain Signal-equal or full network equivalence.
2. `reactions/ReactionsBottomSheetDialogFragment.java`: old Molly replaced fixed surface navigation bar color with `colorSurfaceContainerLow`; C4 removed the explicit override. No source proof establishes equivalent navigation bar appearance. Unresolved: runnable light/dark, gesture/three-button, pre-35/35+, collapsed/expanded checks; a stated reason may describe the removal, not claim preservation.
3. `feature/camera/src/main/java/org/signal/camera/hud/StandardCameraHud.kt`: old Molly changed the badge from #2C6BED to #7152FF; the old badge is absent from C4 HUD. `feature/media-send/src/main/java/org/signal/mediasend/screens/capture/MediaCaptureScreen.kt:158-168` supplies the next button/count; fixed purple is not established. Unresolved whether fixed color is required; exercise multi-capture/count/next and lifecycle. A stated reason can record the changed UI only.
4. `components/settings/app/account/AccountSettingsFragment.kt`: old Molly removed PIN confirmation for disabling reminders and restricted linked-device PIN, change-number and transfer controls, except transfer while unregistered. C4 `components/settings/app/account/AccountSettingsViewModel.kt:167-170` directly sets the toggle; `components/settings/app/AppSettingsActivity.kt:85` routes linked devices to separate settings. Record these facts, but linked-device restrictions across direct links and the unregistered exception need a runnable check; do not claim full preservation.
5. `components/settings/app/appearance/appicon/AppIconSelectionFragment.kt`: old Molly rendered adaptive drawable previews via bitmap; C4 `feature/app-settings/src/main/java/org/signal/appsettings/appicon/AppIconSelectionScreen.kt:215` converts `preset.loadIcon(context)` to bitmap, and `AppIconPreset.kt:34-37` loads disabled aliases. Record source-level move; run alias/fallback preview checks before asserting runtime equivalence.
6. `main/MainNavigation.kt`: old Molly set theme attributes on bar/rail and selected bar text. C4 `main/MainNavigationBar.kt:73,89-90,180,204` applies corresponding attributes and selected text color. Suitable stated source reason; light/dark bar/rail rendering remains untested.
7. `components/SharedContactView.java`: old Molly changed the action label to the generic message string. C4 `components/SharedContactView.java:317` uses `SharedContactView_message_button`; `app/src/main/res/values/strings.xml:2613` says Message. Suitable stated reason for English only; locale wording needs translation comparison and, if required, UI check.
8. `components/settings/app/subscription/receipts/detail/DonationReceiptDetailFragment.kt`: old Molly removed the Signal logo splash. C4 delegates to `components/settings/app/subscription/receipts/detail/DonationReceiptDetailScreen.kt:57`, whose receipt content has no logo. Suitable stated source reason; receipt rendering was not run.
9. `giph/mp4/GiphyMp4Repository.java`: old Molly used the app OkHttp client builder and `ContentProxySelector`; C4 `giph/mp4/GiphyMp4Repository.java:32` retains that construction. Suitable stated construction reason, not an end-to-end routing guarantee; proxy/DNS routing needs runnable verification if required.
10. `mediasend/v2/review/AddMessageDialogFragment.kt`: old Molly removed the forced dark inflater. C4 also has no such override and derives from `components/KeyboardEntryDialogFragment.kt`. Suitable stated reason for absence of forced dark inflater; resolved appearance/contrast needs light/dark runtime verification.
11. `util/DynamicLanguage.java`: old Molly read language from `TextSecurePreferences`; C4 `util/DynamicLanguage.java:49` reads `PlainTextKeyValueStore.getLanguage`, rather than the same call. Prior source research identifies the shared preference-backed pathway, but startup/restore/migration timing requires a runnable check; do not assert complete equivalence.
12. `util/PowerManagerCompat.java`: old Molly extracted the ignore-battery-optimization intent builder and used its result in the existing method. C4 `util/PowerManagerCompat.java:23,27` retains that exact helper (indeed S and M have the same blob). Suitable stated reason; prompt/result behavior on device was not tested.

## Libsignal independent reversion receipts

Historical PR3 input `d756c962296d51e6d910d9821a8df44b0cbd0578`, tree `3a20fd39d1f0e444bcc4711a2909635d8762b67e`; PR4 input `1923f7f681b790fdf53006fa943b61de473a5ef5`, tree `f25a1de0fc87f0f862cc270f823d240857878bc5`. Mechanical tree `759ef1c309d93aa7e4ac7217b144c04545562f11`. Historical baseline module compiles passed for both inputs. Each forward patch was independently reverse applied, gated and restored; each inverse gate exited 1 on both inputs. Gate not rerun on current target.

For reproduction, set `JAVA_HOME`, `ANDROID_HOME`, `TMPDIR` and `PATH` for the local toolchain, and `REPORT_WORKTREE` to this repository checkout. From the historical source checkout, for each ID use:

```sh
git apply --reverse "$REPORT_WORKTREE/patches/ports/signal-8.33.2/report-evidence/hunk-$ID.patch"
agent-heavy -- ./gradlew --offline --no-daemon --no-parallel --no-configuration-cache --max-workers=1 --dependency-verification=strict --init-script "$REPORT_WORKTREE/tools/patches/test-worker-heap.init.gradle" -Pkotlin.compiler.execution.strategy=in-process '-Dorg.gradle.jvmargs=-Xmx5g -Xms256m -XX:MaxMetaspaceSize=1g -Dcom.android.tools.r8.threadCount=1 -XX:+UnlockExperimentalVMOptions -XX:hashCode=3' :lib:libsignal-service:compileJava
git apply "$REPORT_WORKTREE/patches/ports/signal-8.33.2/report-evidence/hunk-$ID.patch"
```

PR3 and PR4 forward patches were byte-identical per ID. SHA-256: `92b4aeb23e3a9479eae3` = `451ad530e099fa080e0f30953f54afd32f901474a4240b841d2791776b12682e`; `889e6d1e15e5bdc65692` = `8e31ef9f0faff09bbbbf65e03798c60f772624a4fda59ea2feedcb29a5420b65`; `a194a470661695b89387` = `85a3c601342474786ee7b724715ff4e5c2c84560761588c95461857d662f7cc1`; `b735539990cfa3e6bdb8` = `9d2403f2d23273b78b20b3704ae912797b30ab0e99796068a70c92e0d24f5941`.

Current source commits PR3 `1500ad2a9c9ac9500a22ba77893171730a7f88f8` (tree `c50fa18dab3c5420e93719dae3040b7a4058795f`) and PR4 `cac99e421c14af3fae0251b64a922e235e3708c9` (tree `2a5ccdccacb3e7b77e7f4719dff4db75ff28b2b9`) have the same respective full blobs as both historical inputs for the first three paths: `932e209b0781e43dd8c3088abce07e790df90503`, `6803653710d662c148df6d0b87fff5dad913cd57`, `53d38ec128d36a40e447d92f01138a0a9e3418ce`. PushServiceSocket historical blob `be4becdd684b0afa671949baf203f1b2482eb6cd` differs from both current blobs `224f7cf88878d39b1cc62ad9528c6070ce16bfed` only by the accepted JsonProperty import restoration; the tested hunk body and context are unchanged. This transfers hunk identity, not the historical gate to the current trees.

The registration inverse reported missing DeviceAttributes and RegisterAsSecondaryDeviceResponse; both imports together are required by the existing implementation, but each was not tested alone. The other three inverses restored literal conflict markers and failed parsing (Java, Kotlin, Java respectively). These failures do not measure import policy, semantic choices or Svr3Credentials removal; app and full regression gates were not measured by these checks.

## Import restorations

Eight per-file partial restorations add 12 imports originally present in both the mechanical merge and Molly. Each action covers only the listed imports, not a complete compatibility hunk; filenames identify one proven restoration diff hunk, and the IDs below list all restoration diff hunks included in each patch. No one-hunk-at-a-time gate is claimed. No body occurrence of the 12 names or wildcard import was found in these files; this is a source-resolution check, not a compiler-plugin proof.

PR3 baseline 1500ad2a9c9ac9500a22ba77893171730a7f88f8 compiled successfully. Each PR3 case below and the PR3 combined tree 142dc8e37ad49cc97cac1fc99f6759bdecd5e11a passed the actual app compile. Kotlin executed for all cases; Java executed except for the DataMessageProcessor case, where Java was UP-TO-DATE. PR4 combined tree 4689da748e7ffb4b52d5d8fcbd9a1ebaf905f911 passed with Kotlin and Java executed; PR4 individual cases and PR4 baseline were not run. Gradle cache and prior outputs were retained, and no full build is claimed.

Gate command (each candidate checked out in the shared source worktree): `env JAVA_HOME=/var/home/x/.local/opt/jdk-21.0.13+9 ANDROID_HOME=/home/x/Android/Sdk agent-heavy -- ./gradlew --offline --no-daemon --no-parallel --no-configuration-cache --max-workers=1 --dependency-verification=strict --init-script /var/home/x/agent-scratch/mollyim-android/pr-stack/worktree/tools/patches/test-worker-heap.init.gradle -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.jvmargs="-Xmx5g -Xms256m -XX:MaxMetaspaceSize=1g -Dcom.android.tools.r8.threadCount=1 -XX:+UnlockExperimentalVMOptions -XX:hashCode=3" :app:compileProdWebsiteDebugKotlin :app:compileProdWebsiteDebugJavaWithJavac`.

All nine source checkpoints were independently checked by applying the candidate patch and by overlaying guarded import insertions in separate Git indexes; corresponding trees matched, with exactly 12 insertions in eight files and no deletions or mode changes. The later ConversationItem code changes remain present.

- `app/src/main/java/org/thoughtcrime/securesms/conversation/ConversationItem.java`: candidate `7f47e8d352d1212787fb5d196291de9947876be3` tree `345fdf47a388decc36e5d70ab9017e2107d61dea`; restoration IDs 67832bd595fbc116d5c9, a62b3651d3829be23031; BUILD SUCCESSFUL in 3m 16s, Kotlin EXECUTED, Java EXECUTED. Former current IDs 14b41a52a784480ff935, c73d116d4324245b6141; replacement current IDs none. None of the former IDs had hunk records or group assignments.

- `app/src/main/java/org/thoughtcrime/securesms/dependencies/CoreUiDependenciesProvider.kt`: candidate `3d28fe1b04957a65d4db42d40082261e2bba09fa` tree `479b63ad7db5fdbd1a802ce3cc3952c540abba7e`; restoration IDs a315df295c7834ff887e; BUILD SUCCESSFUL in 3m 13s, Kotlin EXECUTED, Java EXECUTED. Former current IDs 57c3f630741a4d6c46ac; replacement current IDs 0775a4537273b497f149. None of the former IDs had hunk records or group assignments.

- `app/src/main/java/org/thoughtcrime/securesms/glide/ChunkedImageUrlLoader.java`: candidate `9160c745bfba68633e46208ab7a9211ed34fc5b5` tree `f050073e299516207985ad437d21e062defc7c80`; restoration IDs b77e19bc0951cc903901; BUILD SUCCESSFUL in 3m 19s, Kotlin EXECUTED, Java EXECUTED. Former current IDs 8cfab22505ce579e1df9; replacement current IDs 02b95fd448af0bfb9d96. None of the former IDs had hunk records or group assignments.

- `app/src/main/java/org/thoughtcrime/securesms/keyvalue/AccountValues.kt`: candidate `22d000a03d8fab4c804a01d8f4d713e934d19f93` tree `ff4e297e15a2230079a9df0a9b49aa24146b58d2`; restoration IDs 89d2c0a1a74024cddd3d; BUILD SUCCESSFUL in 3m 28s, Kotlin EXECUTED, Java EXECUTED. Former current IDs 8f786732aadabe171ee5; replacement current IDs c1db604132e2c77751f7. None of the former IDs had hunk records or group assignments.

- `app/src/main/java/org/thoughtcrime/securesms/maps/PlacePickerActivity.java`: candidate `e29b197f1b418e8b73951e2bee232fe55196877d` tree `99fdc3cad67c8ab1bfd3e102b78b8bed123ef61e`; restoration IDs cc36c3786d0379aca969; BUILD SUCCESSFUL in 3m 17s, Kotlin EXECUTED, Java EXECUTED. Former current IDs 5600ed33e766ce909d67; replacement current IDs none. None of the former IDs had hunk records or group assignments.

- `app/src/main/java/org/thoughtcrime/securesms/mediasend/v3/MediaSendV3ForwardFragment.kt`: candidate `1bbb86678c854f8a454301041220e237bb62c9a4` tree `5ac193c2e22ac2b7a699ab5032b5497a3ac16650`; restoration IDs a10bbcea5de460447caf; BUILD SUCCESSFUL in 3m 13s, Kotlin EXECUTED, Java EXECUTED. Former current IDs ef533cae4a2bd288ce5c; replacement current IDs 143a0a1e561c975ed9f3. None of the former IDs had hunk records or group assignments.

- `app/src/main/java/org/thoughtcrime/securesms/messages/DataMessageProcessor.kt`: candidate `bc2f5a0884d48d4659068e0ce62e6e47a4c6cbe1` tree `2b487ba18c343ffba2e2135404d19326fb8b3d40`; restoration IDs 84bc91c45c0636a0562d, 757521f616948c304df1, 5cb94163ea5bb41fae5f; BUILD SUCCESSFUL in 3m 15s, Kotlin EXECUTED, Java UP-TO-DATE. Former current IDs 358e7ee8816a29ef8454, 533c3696666d688608c2, 7cfde89b8cc04aa53bb9; replacement current IDs none. None of the former IDs had hunk records or group assignments.

- `app/src/main/java/org/thoughtcrime/securesms/mms/AttachmentManager.java`: candidate `639524c00b6bc1dfdc7b315105ce44703344e602` tree `02f4ca8cca4fe424b8f605e2f2944e760a6d7e26`; restoration IDs f9f9edb32f3882f72c3f; BUILD SUCCESSFUL in 3m 20s, Kotlin EXECUTED, Java EXECUTED. Former current IDs f30277315359fadada35; replacement current IDs 2e9a4d6aa4c122488df0. None of the former IDs had hunk records or group assignments.

## Backup policy checks

The following checks used cached build outputs and offline Gradle, not a clean or full build. The command below uses `$REPORT_WORKTREE` for this checkout and `$SOURCE` for a checkout of each named candidate; set `JAVA_HOME` to JDK 21 and `ANDROID_HOME` to the Android SDK. Run in `$SOURCE`:

```sh
PATH="$JAVA_HOME/bin:$PATH" agent-heavy -- ./gradlew --offline --no-daemon --no-parallel --no-configuration-cache --max-workers=1 --dependency-verification=strict --init-script "$REPORT_WORKTREE/tools/patches/test-worker-heap.init.gradle" -Pkotlin.compiler.execution.strategy=in-process '-Dorg.gradle.jvmargs=-Xmx5g -Xms256m -XX:MaxMetaspaceSize=1g -Dcom.android.tools.r8.threadCount=1 -XX:+UnlockExperimentalVMOptions -XX:hashCode=3' :app:testProdWebsiteDebugUnitTest --tests "$SELECTOR"
```

PR3 original baseline `1500ad2a9c9ac9500a22ba77893171730a7f88f8` tree `c50fa18dab3c5420e93719dae3040b7a4058795f` passed FullBackupExporterTest and MollyBackupPreferencesTest. Signal-threshold supplement `b48bf7098597fa0e9756a4037c8895fba73069de` tree `7be61956c1d4a535ce05098b5408424a5ef7d979` passed all 11 exporter tests: the 24-hour fixture did not distinguish the predicates. Long/timeout-only inverse `0766a50e4cf379090fbefbe5ad42a7ef35cb6d18` tree `da7138800cc93c2c96916d5ed964dc814b302c29` failed 2 of 3 MollyBackupPreferencesTest tests: long value expected 2147483767 but was 0, and Integer cannot be cast to Long. Baseline importer blob `2c0a6cab56e8e19f890439e596ef47c832ee83b4` and preference test blob `aba1a441a587da06fff2dc76547cd6c729115248` match packaged PR3 before the fixture change; these are historical runs, not runs at the new packaged head. The partial inverse patch is `hunk-18d50f852eca12a4e423.patch`; it does not reverse the entire report hunk.

The selected seven-day test is `org.thoughtcrime.securesms.backup.FullBackupExporterTest.started disappearing messages are excluded even with substantial time remaining`. Baseline `da3131fde031e09017e778c736f7d34f8b5a7600` tree `d4b5d14b580bc2faa8af989c48d34e77cada3ca4` passed one test; Signal-threshold supplement `99014fba9d5df76f07d76ac74257c991be7f159a` tree `a23dd5ea87c7eabfdec564ef543a723aa1a57eaf` failed that test's assertion. The supplement changes the terminal predicate while leaving the conflict marker removed (`hunk-1b2207dcd3236258f280.patch`); it is not a literal full-hunk inverse. The one-line fixture change is `hunk-e987574c43c572243a5d.patch`. Initial checkpoint owns this test. Each of the nine new checkpoints independently differed from its previous graph counterpart by exactly this path, one insertion and one deletion. PR3 final graph tree equals the tested baseline tree; this changes no production code. Molly `0e202dda90785771ff8c87526eaaf30c655f54f3` exporter lines 593–596 uses the same terminal predicate as the retained policy. The local tip search covered only the named branches listed in the report, not all Molly history.

## Executed translation task witness

At source PR4 commit `cf3146bb3fcc29eb3059528df52a5f304a6b379a` (tree `96ce7bb4a50dcf1da4952fe07ff59e48ca5fd985`), the pinned Signal `6e9dddc01f1b26e608ac683807c51e139c141ed2` supplied 816 localized `strings.xml` inputs. The 816 source blobs and modes, all default `values/strings.xml` files, and `build-logic/plugins/src/main/java/mollyify.gradle.kts` match PR3 source `842e226be91f6b9d664df3b92ef460d07e6bde14`. The transformation script is byte-identical to old Molly `0e202dda90785771ff8c87526eaaf30c655f54f3`.

The executed command was `env JAVA_HOME=/var/home/x/.local/opt/jdk-21.0.13+9 ANDROID_HOME=/home/x/Android/Sdk PATH=/var/home/x/.local/opt/jdk-21.0.13+9/bin:$PATH agent-heavy -- ./gradlew --offline --no-daemon --no-parallel --no-configuration-cache --console=plain --info --max-workers=1 --dependency-verification=strict -Pkotlin.compiler.execution.strategy=in-process "-Dorg.gradle.jvmargs=-Xmx5g -Xms256m -XX:MaxMetaspaceSize=1g -Dcom.android.tools.r8.threadCount=1 -XX:+UnlockExperimentalVMOptions -XX:hashCode=3" updateTranslationsAll`. It exited 0. All thirteen module tasks executed: `:app`, `:core:ui`, `:core:util`, `:feature:app-settings`, `:feature:chat-settings`, `:feature:media-keyboard`, `:feature:media-send`, `:feature:media-viewer`, `:feature:registration`, `:lib:device-transfer`, `:lib:emoji`, `:lib:password-manager`, and `:lib:signal-login` (each `:updateTranslations`); the root aggregate had no actions. The exact task and output inventory is in `translation-task-witness.tsv`.

Comparing task output with the baseline across all 816 files found identical parsed string/plurals values, attributes, child markup and tails, except 68 baseline-only app `please_wait` entries. All 68 baseline elements match old Molly at the XML element level; the task does not create them. There were no other mismatches or parse errors. Comments and root indentation are outside this comparison. Only 552/816 task-output and baseline files were byte-identical; the other 264 differ in bytes, so this does not establish formatting provenance or whole-file identity. The selected Signal, task-output and baseline files each had 204 SPDX files and zero literal `&#160;` or `&#8212;` occurrences; this limited selection does not supersede broader XML reviews. The actual execution strengthens the earlier model-based receipts; individual inverse outcomes and their limits remain as recorded.

## Residual partial inverses

Each result below measured a partial resolved-reference inverse mapped to a raw report hunk by target interval, not a complete raw-hunk inversion. The PR3 and PR4 target file blobs match, but their source trees and gates were independently tested. No reduction was accepted. The forward patches restore the corresponding complete parent tree; private commit and tree identifiers and forward patch SHA-256 values follow.

PR3 parent `842e226be91f6b9d664df3b92ef460d07e6bde14`, tree `d4b5d14b580bc2faa8af989c48d34e77cada3ca4`; PR4 parent `cf3146bb3fcc29eb3059528df52a5f304a6b379a`, tree `96ce7bb4a50dcf1da4952fe07ff59e48ca5fd985`. Each private counter is a child of its respective parent. The resolved-reference baseline is heuristic and is not a correctness claim.

| Branch | Residual ID | Raw ID | Experimental commit | Tree | Forward patch SHA256 |
|---|---|---|---|---|---|
| pr3 | `05f8c29fbf4bfc24fed5` | `8dd2f3b524d96720db2f` | `9237bbb2cf44994620849fb54d875f29dd975331` | `448d49e1b1582322b3e5477d7333c42971b5f981` | `a323fb70883dc5f728af6f622157acb7d856b07a50e20ff49c6a1252bdbc7dd3` |
| pr3 | `7660ef164061ffc93a96` | `7660ef164061ffc93a96` | `5557b3a5bec0f00eb75ca79b970f0746d9a5e56a` | `0fd53da31078a32e35d5241a5b8aba54a3fdb1d4` | `2f84057f649ed1284133d299f948a547186a45e2fcaef4486bcec65602c8d213` |
| pr3 | `7a1001390e195c747030` | `7a1001390e195c747030` | `7bd5b4e9966c6e0d956181e34474ec9b1830b620` | `dc829d9ddc866575f0aad2ccc62728158d5db286` | `6dab280032e28784260de2725a81314c9823699da929e41ccb894d89a576b684` |
| pr3 | `c10dc6afae3768148078` | `61a49a1d5fe0b4bc6f4b` | `d7dc70d0de983d91086604c2cf6238d852dc0976` | `e3ca80b640a515e9c2c6bf45ab25c73ccb37f1cd` | `b3f2937164bb97851e6f3107c19c7516e059fa0821cd143b9b44666caef43084` |
| pr3 | `d8d7bb6195b2ada6285c` | `d8d7bb6195b2ada6285c` | `6ef91f752a756dc9e3589361c9c877bd49d4146d` | `dfa8aa590dce63661d00a83accab6153d9831282` | `c1fcc4b8de6d83ba7de58f52a37f9d42c0dbfc145b8d7ba2b4a1a9f967fb694d` |
| pr3 | `e7331e58449f2fac31d3` | `e7331e58449f2fac31d3` | `e5df7cd85fff499bf9f1e40ffcf4107d3899c6e1` | `e5663a2112deca6fb05647b689f7aae23eaba357` | `a5123c3899cfcec9485f306b05bb2357b5b6ac200a96a3478ef6a627d562dea1` |
| pr4 | `05f8c29fbf4bfc24fed5` | `8dd2f3b524d96720db2f` | `89e83f9d58ff1c0da1ca9a81f5370210542278f7` | `66dd3b16446dcc3e6291400ec9a720aee8e9c7e4` | `a323fb70883dc5f728af6f622157acb7d856b07a50e20ff49c6a1252bdbc7dd3` |
| pr4 | `7660ef164061ffc93a96` | `7660ef164061ffc93a96` | `9be4648688c440eafd911efa6d49d20898f0a089` | `e121ef8d2ead8d1a1fcbad51f7372427893bc365` | `2f84057f649ed1284133d299f948a547186a45e2fcaef4486bcec65602c8d213` |
| pr4 | `7a1001390e195c747030` | `7a1001390e195c747030` | `35a672b6ec7524835f0b49268ec8626072e9039c` | `15e6965f89b1ebe1ce3dc724d44d5444f39f839b` | `6dab280032e28784260de2725a81314c9823699da929e41ccb894d89a576b684` |
| pr4 | `c10dc6afae3768148078` | `61a49a1d5fe0b4bc6f4b` | `96b1544741c9c235194a07f0058fabbbc13d0423` | `c7238d161c4d5f5cc543ff5a0a3694bf7e0327f0` | `b3f2937164bb97851e6f3107c19c7516e059fa0821cd143b9b44666caef43084` |
| pr4 | `d8d7bb6195b2ada6285c` | `d8d7bb6195b2ada6285c` | `060832cbb2d74e1eb519cb4607d984578f28a0fd` | `985a483e08f437c399313d2c8730c88c31a284a1` | `c1fcc4b8de6d83ba7de58f52a37f9d42c0dbfc145b8d7ba2b4a1a9f967fb694d` |
| pr4 | `e7331e58449f2fac31d3` | `e7331e58449f2fac31d3` | `95dc93fbf54e085f0d650ff278d72b91d675067c` | `70eafeae625dda64fb8577b5779cf7599361d6b5` | `a5123c3899cfcec9485f306b05bb2357b5b6ac200a96a3478ef6a627d562dea1` |


On each branch the screen baseline passed and its inverse failed four assertions out of ten; the view-model baseline passed 11 tests, the badge baseline passed three, the proxy baseline passed both selected classes (PR4: 13 and two tests), and registration and Material baseline compiles passed. Each of those five inverse gates failed compilation in the changed file: view-model action handling, `autoUpdate` and Long/Duration; registration `makeGooglePlayServicesAvailable`; badge references; proxy `fromProxy`; Material constructor candidates. These failures establish consistency with surrounding code, not necessity of every line. Registration behavior and visual equivalence remain unmeasured. Whole update, badge and proxy inverses lose Molly behavior or policy and are not eligible reductions. PR4 Material baseline reused the successful PR4 view-model baseline app compile.

Commands below ran offline in the corresponding source checkout with JDK 21 and the existing SDK and build outputs. The same command was used for both branches except PR3 screen baseline used `--rerun` as shown. The named test tasks used `--rerun`; baseline and counter commands otherwise match. PR4 Material baseline used the view-model command.

- screen-baseline-executed: `agent-heavy -- ./gradlew --offline --no-daemon --no-parallel --no-configuration-cache --max-workers=1 --dependency-verification=strict --init-script /var/home/x/agent-scratch/mollyim-android/pr-stack/worktree/tools/patches/test-worker-heap.init.gradle -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.jvmargs=-Xmx5g\ -Xms256m\ -XX:MaxMetaspaceSize=1g\ -Dcom.android.tools.r8.threadCount=1\ -XX:+UnlockExperimentalVMOptions\ -XX:hashCode=3 :feature:app-settings:testDebugUnitTest --rerun --tests org.signal.appsettings.updates.AppUpdatesSettingsScreenTest`
- viewmodel-counter: `agent-heavy -- ./gradlew --offline --no-daemon --no-parallel --no-configuration-cache --max-workers=1 --dependency-verification=strict --init-script /var/home/x/agent-scratch/mollyim-android/pr-stack/worktree/tools/patches/test-worker-heap.init.gradle -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.jvmargs=-Xmx5g\ -Xms256m\ -XX:MaxMetaspaceSize=1g\ -Dcom.android.tools.r8.threadCount=1\ -XX:+UnlockExperimentalVMOptions\ -XX:hashCode=3 :app:testProdWebsiteDebugUnitTest --rerun --tests org.thoughtcrime.securesms.components.settings.app.updates.AppUpdatesSettingsViewModelTest`
- registration-counter: `agent-heavy -- ./gradlew --offline --no-daemon --no-parallel --no-configuration-cache --max-workers=1 --dependency-verification=strict --init-script /var/home/x/agent-scratch/mollyim-android/pr-stack/worktree/tools/patches/test-worker-heap.init.gradle -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.jvmargs=-Xmx5g\ -Xms256m\ -XX:MaxMetaspaceSize=1g\ -Dcom.android.tools.r8.threadCount=1\ -XX:+UnlockExperimentalVMOptions\ -XX:hashCode=3 :feature:registration:compileDebugKotlin`
- badge-counter: `agent-heavy -- ./gradlew --offline --no-daemon --no-parallel --no-configuration-cache --max-workers=1 --dependency-verification=strict --init-script /var/home/x/agent-scratch/mollyim-android/pr-stack/worktree/tools/patches/test-worker-heap.init.gradle -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.jvmargs=-Xmx5g\ -Xms256m\ -XX:MaxMetaspaceSize=1g\ -Dcom.android.tools.r8.threadCount=1\ -XX:+UnlockExperimentalVMOptions\ -XX:hashCode=3 :app:testProdWebsiteDebugUnitTest --rerun --tests org.thoughtcrime.securesms.components.settings.conversation.shared.ConversationHeaderBadgeTest`
- proxy-counter: `agent-heavy -- ./gradlew --offline --no-daemon --no-parallel --no-configuration-cache --max-workers=1 --dependency-verification=strict --init-script /var/home/x/agent-scratch/mollyim-android/pr-stack/worktree/tools/patches/test-worker-heap.init.gradle -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.jvmargs=-Xmx5g\ -Xms256m\ -XX:MaxMetaspaceSize=1g\ -Dcom.android.tools.r8.threadCount=1\ -XX:+UnlockExperimentalVMOptions\ -XX:hashCode=3 :core:network:test --rerun --tests org.signal.network.config.ProxyConfigTest --tests org.signal.network.config.LibSignalProxyPolicyTest`
- material-counter: `agent-heavy -- ./gradlew --offline --no-daemon --no-parallel --no-configuration-cache --max-workers=1 --dependency-verification=strict --init-script /var/home/x/agent-scratch/mollyim-android/pr-stack/worktree/tools/patches/test-worker-heap.init.gradle -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.jvmargs=-Xmx5g\ -Xms256m\ -XX:MaxMetaspaceSize=1g\ -Dcom.android.tools.r8.threadCount=1\ -XX:+UnlockExperimentalVMOptions\ -XX:hashCode=3 :app:compileProdWebsiteDebugKotlin`

Full preflight passed at the previous outer heads PR3 `23ff849b799fd09f25751a7ddebbf83f95ed39cc` and PR4 `c964a912eb92f8de0f5c0b785479e49583c79043`; those runs compiled the unchanged source trees above. The report-only outer heads require their applicable preflight before push. CI has not run on them.

### Proxy configuration policy controls

A separate focused counter changes `Proxy.Type.SOCKS` conversion from source-aware `SOCKS_REMOTE_DNS` for users to `SOCKS` for all sources. PR3 counter commit `4222415a7278dc73f2e57141e5e230d97b51735f`, tree `32e88a931a5723f09be149a79a308350b15334ac`, has the PR3 parent above; PR4 counter commit `3da3c017797744941c0a44b3e2cd28a8a4dbcf89`, tree `b3f4dc4b794f811d34444e43bc4264936f0d6c6c`, has the PR4 parent above. Each baseline passed 13 tests; each counter executed 13 tests and failed exactly two assertions, with no compile failures, errors or skips. Both failures expected `SOCKS_REMOTE_DNS` and received `SOCKS`: `resolve preserves a user proxy when censorship circumvention is enabled` and `resolve prefers a user SOCKS proxy with remote DNS over the system proxy`. This checks configuration policy, not actual DNS traffic or necessity of the entire raw hunk. No reduction was accepted.

The exact baseline and counter command on each branch, in its source checkout, was:

```sh
JAVA_HOME=/var/home/x/.local/opt/jdk-21.0.13+9 ANDROID_HOME=/home/x/Android/Sdk TMPDIR=/var/home/x/agent-scratch/mollyim-android/pr-stack/tmp PATH=$JAVA_HOME/bin:$PATH
agent-heavy -- ./gradlew --offline --no-daemon --no-parallel --no-configuration-cache --max-workers=1 --dependency-verification=strict --init-script /var/home/x/agent-scratch/mollyim-android/pr-stack/worktree/tools/patches/test-worker-heap.init.gradle -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.jvmargs=-Xmx5g\ -Xms256m\ -XX:MaxMetaspaceSize=1g\ -Dcom.android.tools.r8.threadCount=1\ -XX:+UnlockExperimentalVMOptions\ -XX:hashCode=3 :core:network:test --rerun --tests org.signal.network.config.ProxyConfigTest
```
