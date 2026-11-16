# Project log

What's new has two tabs. **This update** shows the bundled highlights (`CollieReleaseNotes`, see [FINISH-SETUP.md](FINISH-SETUP.md)). **Project log** lists every change people would notice, one plain sentence each, grouped by release, newest first. Settings → Help → What's new opens straight on the log.

The log ships inside the APK under `app/src/main/assets/collie-log/` and is never fetched. It never takes the status line under "Collie" and has no badge or unread count. Each line with a pull request number opens that PR on GitHub in the browser; the page says the browser doesn't use Collie's proxy.

## Adding a line in a pull request

Add `app/src/main/assets/collie-log/unreleased/<PR number>.txt`:

```
topic: push
pr: 96
Collie notices when push stops working. Settings has a Test my push button.
```

- One file per PR, so PRs never conflict over the log.
- The sentence says what changes for the person, in at most 120 characters. No class names or PR jargon. A security fix says what is now protected, never how it could have been attacked.
- `topic` is one of: setup, push, notifications, backups, privacy, security, updates, settings, devices, look, signal, fixes. `pr` is optional.
- Lines are English only. Topic labels are translated (`collie_log.xml`).
- A PR with nothing people would notice (CI, tests, docs, release tooling) gets the **no log entry** label instead. The *Project log* check (`.github/workflows/project-log.yml`) asks for one or the other when `app/`, `feature/` or `lib/` code changes.

Entries in `unreleased/` show in the app as **In this build · Not in a release yet**, since a build from `main` really contains them.

## Release prep

In the release-prep PR (see [RELEASING.md](RELEASING.md)):

```sh
python3 tooling/collie-log/stamp.py <versionName> --version-code <versionCode>
```

It moves every waiting entry into `releases/<versionName>.txt` (with `version` and `date` at the top, ordered by topic) and drafts `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` from the same sentences, cut to 500 bytes of ASCII. Trim the draft by hand. The installed version is marked **Your version** in the log when its name matches a release file.

`ProjectLogTest` checks every bundled file: release files need `version` and `date` and are named after their version, sentences fit in 120 characters, and topics are known.

The releases up to 8.35.1-3 RC2 were written by hand from their GitHub release notes.
