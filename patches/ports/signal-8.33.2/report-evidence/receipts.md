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
