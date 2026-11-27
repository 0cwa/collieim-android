# Backup agent rules

- **V2 on-device backups are the only Collie backup creation format.** Any new Collie install routes directly to V2 on-device backups (`LocalBackupsEntry` / `LocalArchiveJob`). `BackupsPreferenceFragment` exists only for pre-V2 installs that already had legacy backups enabled until they upgrade.
- **Legacy V1 `.backup` is a Molly migration and compatibility format only:**
  - Never add Collie-only features, settings, triggers, or UI to `LocalBackupJob`, `LocalBackupJobApi29`, `FullBackupExporter`, `BackupsPreferenceFragment`, or `fragment_backups.xml`.
  - Keep Collie-only keys out of `SignalStore.getKeysToIncludeInBackup()` (for example, device-local tokens, sync task IDs, or remote backup config that must be recreated or re-paired after restore). Add legacy restore handling in `FullBackupImporter` only when needed to safely import genuine Molly legacy `.backup` data.
  - **Existing exception:** `GroupAdmissionValues` stays in `SignalStore.getKeysToIncludeInBackup()` so device-to-device transfer continues preserving group-admission policy (see `docs/GROUP-ADMISSION.md`). Do not treat that as precedent for adding new Collie-only features to legacy `.backup` export.
- **Keep upstream Signal/Molly legacy backup code intact.** Do not delete or rewrite `LocalBackupJob*`, `BackupsPreferenceFragment`, `FullBackup*`, or Molly's existing `MollyValues.keysToIncludeInBackup` as cleanup; they remain upstream-owned so future Signal and Molly merges stay clean.
