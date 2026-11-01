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

## Current PR4 Settings negative checks


Source commit bf34633283d6cd01c8f6bc615ecf2367bd92d780, tree 2b0ba46b23fd27974b8cefbd36e0d91791b525bd. Clean status before and after; `git diff --exit-code` and `git diff --cached --exit-code` pass after restoration. `SettingsValues.kt` Git blob 097ace05ff60c0e418090b52eef1b2c7c72011d8, SHA-256 127caf6f326496318bcea4b28912e7ccbfd3c3e6c2552de3beaecdfb15aec7a4; `LegacySharedPrefs.kt` Git blob 7b339e26b7c3aa43d5310ea79b2418cf5dd7091b, SHA-256 64365414e8310f6b929eedb9f416a1a3329e74733ad570e4a85a91d0677ca48c. Report snapshot commit 11a5ce83180ded7c191e6670fbfc1089188c96db identifies the three paths and hunk IDs below; report rows were unmeasured at that snapshot.

Test inputs (SHA-256): `MollySettingsMigrationTest.kt` 683ca2adbd9acfd0260a5c8e9dfb2195a5c5ca8724a0f5217ac95e7b52e91e47; `MollyBackupPreferencesTest.kt` 280ccc20175d3a75d7017c4383681cdd43670c0489e7037daaf27a02cb9f526e; `MollyLanguageBackupTest.kt` 6afc286c7e3f0e98cd344bdf6ccb3cf965e770c7c504721da05574211adc2e0b.

All runs used `JAVA_HOME=/var/home/x/.local/opt/jdk-21.0.13+9`, `ANDROID_HOME=/var/home/x/Android/Sdk`, `TMPDIR=/var/home/x/agent-scratch/mollyim-android/pr-stack/tmp`, JDK bin on PATH, and:

```
agent-heavy -- ./gradlew --no-daemon --no-parallel --no-configuration-cache --max-workers=1 --dependency-verification=strict --init-script /var/home/x/agent-scratch/mollyim-android/pr-stack/worktree/tools/patches/test-worker-heap.init.gradle -Pkotlin.compiler.execution.strategy=in-process '-Dorg.gradle.jvmargs=-Xmx5g -Xms256m -XX:MaxMetaspaceSize=1g -Dcom.android.tools.r8.threadCount=1 -XX:+UnlockExperimentalVMOptions -XX:hashCode=3' :app:testProdWebsiteDebugUnitTest --tests org.thoughtcrime.securesms.keyvalue.MollySettingsMigrationTest --tests org.thoughtcrime.securesms.backup.MollyBackupPreferencesTest --tests org.thoughtcrime.securesms.backup.MollyLanguageBackupTest
```

Baseline `baseline.log`: exit 0, 12 tests passed, 0 skipped. All candidate gates compiled and reached tests; each was applied separately to the baseline and restored. The exact diff fragments are the adjacent patch files; SHA-256 values below.

- `1c87d9e382a99fab53ad`, SettingsValues migration copy: `migration.patch`, SHA-256 ab33146e34eada9145a609a3b48b068c588938392d096f264588d3499cae4dd7. Gradle exit 1, 1/12 failed, no skips; `MollyLanguageBackupTest` expected `de` but got `null` on upgrade. `migration.log`, `migration-results.txt`.
- `9c616143ddc96d74a2bb`, only additional setter KV write, preserving the other retained Molly behavior in that larger hunk: `setter.patch`, SHA-256 7e603322932df07851f65d91182d2257d9f9b3177c73d9212b693c19d3f1d9d2. Gradle exit 1, 1/12 failed, no skips; `MollyLanguageBackupTest` expected `fr` but got `de` after change. `setter.log`, `setter-results.txt`.
- `66d760f60b510768d21a`, LegacySharedPrefs encrypted reader: `reader.patch`, SHA-256 ee689534b0f88b5557fafddf689cfa3dc0fa5942a08524da189531c4898f0581. Gradle exit 1, 3/12 failed, no skips: `MollyLanguageBackupTest` expected `de` but got `zz`; `MollySettingsMigrationTest` language expected `de` but got `en`, and encrypted preference migration assertion failed. `reader.log`, `reader-results.txt`. The fully qualified `androidx.preference.PreferenceManager` is a semantic substitution for the default reader, not a literal inverse, to avoid dependency on the separate import hunk.

Old-Molly and upstream reference searches are documented in adjacent parent scratch `SETTINGS-REVERT-RESULTS.md` and `LANGUAGE-BACKUP-RESULTS.md`: original Molly and two Molly branches used a preference-backed Java getter/setter; Signal upstream branches did not establish Molly policy. These tests exercise PR4 only: SettingsValues differs on PR3, so they do not establish PR3 exact-current behavior. No removal was retained or recommended from these failed negative checks. This is not an end-to-end encrypted backup test.

The following exact independent candidate diffs correspond to the hashes above.

### Migration candidate

```diff
diff --git a/app/src/main/java/org/thoughtcrime/securesms/keyvalue/SettingsValues.kt b/app/src/main/java/org/thoughtcrime/securesms/keyvalue/SettingsValues.kt
index 097ace05ff..f104868b0a 100644
--- a/app/src/main/java/org/thoughtcrime/securesms/keyvalue/SettingsValues.kt
+++ b/app/src/main/java/org/thoughtcrime/securesms/keyvalue/SettingsValues.kt
@@ -607,9 +607,6 @@ class SettingsValues internal constructor(store: KeyValueStore, private val cont
 
     val writer = store.beginWrite()
 
-    if (!store.containsKey(LANGUAGE)) {
-      writer.putString(LANGUAGE, LegacySharedPrefs.getString(context, "pref_language", "zz"))
-    }
     if (!store.containsKey(THEME)) {
       writer.putString(THEME, LegacySharedPrefs.getString(context, "pref_theme", if (DynamicTheme.systemThemeAvailable()) "system" else "light"))
     }
```

### Setter candidate

```diff
diff --git a/app/src/main/java/org/thoughtcrime/securesms/keyvalue/SettingsValues.kt b/app/src/main/java/org/thoughtcrime/securesms/keyvalue/SettingsValues.kt
index 097ace05ff..ed390c4858 100644
--- a/app/src/main/java/org/thoughtcrime/securesms/keyvalue/SettingsValues.kt
+++ b/app/src/main/java/org/thoughtcrime/securesms/keyvalue/SettingsValues.kt
@@ -252,7 +252,6 @@ class SettingsValues internal constructor(store: KeyValueStore, private val cont
   var language: String
     get() = TextSecurePreferences.getLanguage(context)
     set(value) {
-      putString(LANGUAGE, value)
       TextSecurePreferences.setLanguage(context, value)
       configurationSettingChanged.postValue(LANGUAGE)
     }
```

### Reader candidate

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
