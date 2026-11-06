# Collie - The Signal Fork

<p align="center">
  <img src="assets/collie-icon.svg" alt="Collie icon" width="240">
</p>

<p align="center">
  <a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22im.collie.app%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2F0cwa%2Fcollieim-android%22%2C%22author%22%3A%220cwa%22%2C%22name%22%3A%22Collie%22%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Afalse%2C%5C%22apkFilterRegEx%5C%22%3A%5C%22%5Ecollie%5C%5C%5C%5C.apk%24%5C%22%7D%22%7D"><img src="assets/badge_obtainium.png" alt="Get it on Obtainium" height="80"></a> <a href="https://liberapay.com/Collie/">
  <img src="https://raw.githubusercontent.com/aha999/DonateButtons/master/LiberaPay.png" alt="Donate via Liberapay" height="80">
</a>
</p>

<p align="center">
  <a href="https://github.com/0cwa/collieim-android/blob/v8.33.2-1-rc3/gradle/libs.versions.toml"><img src="https://img.shields.io/badge/v8.33.2--1--rc3%20min%20Android-6.0%2B-2F4F3A?style=flat&amp;labelColor=5A4033" alt="Minimum Android 6.0 for the published v8.33.2-1-rc3 APKs"></a>
  <a href="https://github.com/0cwa/collieim-android/blob/main/LICENSE"><img src="https://img.shields.io/badge/license-AGPL--3.0--only-687E59?style=flat&amp;labelColor=5A4033" alt="AGPL 3.0 only"></a>
</p>

Welcome!  Collie is an independent Android messenger built on [Signal](https://signal.org/) and [Molly](https://molly.im/). It uses the Signal network for private messages and calls, with Molly's protections for data stored on your device.  We use a patch-based release system for maximum code auditability, fast security updates ⚡, and interoperability.  [We contribute](https://github.com/mollyim/mollyim-android/pull/852) [back upstream](https://github.com/mollyim/mollyim-android/pull/853).

<b>🌟 Stars! 🧡</b>

<a href="https://www.star-history.com/?repos=0cwa%2Fcollieim-android&type=date&releases=&legend=bottom-right">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=0cwa/collieim-android&type=date&theme=dark&legend=bottom-right" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=0cwa/collieim-android&type=date&legend=bottom-right" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=0cwa/collieim-android&type=date&legend=bottom-right" width="600" />
 </picture>
</a>

This project keeps Molly's features available as Signal develops, while offering Collie as a separate app for people who want more control over what stays on their phone. The releases share the same source and build on one another.

## Current releases

<p align="left">
  <a href="https://github.com/0cwa/collieim-android/releases"><img src="https://img.shields.io/github/v/release/0cwa/collieim-android?include_prereleases&amp;sort=date&amp;display_name=tag&amp;style=flat&amp;labelColor=5A4033&amp;label=release" alt="Latest published GitHub release including prereleases"></a>
  <a href="https://github.com/0cwa/collieim-android/releases"><img src="https://img.shields.io/github/release-date-pre/0cwa/collieim-android?display_date=published_at&amp;style=flat&amp;labelColor=5A4033&amp;label=published" alt="Publication date of the latest release including prereleases"></a>
</p>

| Release | What it offers | Install |
| --- | --- | --- |
| **<ins>Collie</ins>**<br><a href="https://github.com/0cwa/collieim-android/releases"><img src="https://img.shields.io/github/downloads/0cwa/collieim-android/collie.apk?displayAssetName=false&amp;style=flat&amp;labelColor=5A4033&amp;label=downloads" alt="Downloads of collie.apk across releases"></a> | **<ins>Recommended</ins>.** Molly Continued plus optional controls for keeping messages and media. | [Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22im.collie.app%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2F0cwa%2Fcollieim-android%22%2C%22author%22%3A%220cwa%22%2C%22name%22%3A%22Collie%22%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Afalse%2C%5C%22apkFilterRegEx%5C%22%3A%5C%22%5Ecollie%5C%5C%5C%5C.apk%24%5C%22%7D%22%7D) |
| **Collie FOSS**<br><a href="https://github.com/0cwa/collieim-android/releases"><img src="https://img.shields.io/github/downloads/0cwa/collieim-android/collie-foss.apk?displayAssetName=false&amp;style=flat&amp;labelColor=5A4033&amp;label=downloads" alt="Downloads of collie-foss.apk across releases"></a> | Collie without FCM - Google's notification system. (Only host Mollysocket yourself! - details below in the Features section) | [Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22im.collie.app%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2F0cwa%2Fcollieim-android%22%2C%22author%22%3A%220cwa%22%2C%22name%22%3A%22Collie%20FOSS%22%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Afalse%2C%5C%22apkFilterRegEx%5C%22%3A%5C%22%5Ecollie%5C%5C%5C%5C-foss%5C%5C%5C%5C.apk%24%5C%22%7D%22%7D) |
| **Molly, Continued**<br><a href="https://github.com/0cwa/collieim-android/releases"><img src="https://img.shields.io/github/downloads/0cwa/collieim-android/molly-continued.apk?displayAssetName=false&amp;style=flat&amp;labelColor=5A4033&amp;label=downloads" alt="Downloads of molly-continued.apk across releases"></a> | Molly on a newer Signal version, with further registration and notification improvements. | [Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22im.collie.app%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2F0cwa%2Fcollieim-android%22%2C%22author%22%3A%220cwa%22%2C%22name%22%3A%22Molly%20Continued%22%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Afalse%2C%5C%22apkFilterRegEx%5C%22%3A%5C%22%5Emolly%5C%5C%5C%5C-continued%5C%5C%5C%5C.apk%24%5C%22%7D%22%7D) |
| **Molly FOSS, Continued**<br><a href="https://github.com/0cwa/collieim-android/releases"><img src="https://img.shields.io/github/downloads/0cwa/collieim-android/molly-foss-continued.apk?displayAssetName=false&amp;style=flat&amp;labelColor=5A4033&amp;label=downloads" alt="Downloads of molly-foss-continued.apk across releases"></a> | Molly Continued without FCM - Google's notification system.  (Only host Mollysocket yourself! - details below in Features section) | [Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22im.collie.app%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2F0cwa%2Fcollieim-android%22%2C%22author%22%3A%220cwa%22%2C%22name%22%3A%22Molly%20FOSS%20Continued%22%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Afalse%2C%5C%22apkFilterRegEx%5C%22%3A%5C%22%5Emolly%5C%5C%5C%5C-foss%5C%5C%5C%5C-continued%5C%5C%5C%5C.apk%24%5C%22%7D%22%7D) |
| **Mollie, Minimal**<br><a href="https://github.com/0cwa/collieim-android/releases"><img src="https://img.shields.io/github/downloads/0cwa/collieim-android/molly-minimal.apk?displayAssetName=false&amp;style=flat&amp;labelColor=5A4033&amp;label=downloads" alt="Downloads of molly-minimal.apk across releases"></a> | Molly's minimalist feature set on a newer Signal version, without the Continued additions. | [Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22im.collie.app%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2F0cwa%2Fcollieim-android%22%2C%22author%22%3A%220cwa%22%2C%22name%22%3A%22Molly%20Minimal%22%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Afalse%2C%5C%22apkFilterRegEx%5C%22%3A%5C%22%5Emolly%5C%5C%5C%5C-minimal%5C%5C%5C%5C.apk%24%5C%22%7D%22%7D) |

> [!NOTE]
> *App names are planned to shift as features are adopted* - don't be surprised if Molly, Continued becomes Collie and Collie becomes Collie+ (or maybe Collie!) - I'm collecting community feedback to decide official variants. 🧡

Molly Minimal provides the updated Molly foundation. Both Molly Continued releases and both Collie releases add network settings during registration for proxy users, visible errors and retry options for failed UnifiedPush registration, and updated donation confirmation text. Collie and Collie FOSS also add the four optional retention controls described below. The two FOSS releases omit Firebase; the other three include it.

> [!TIP]
> All five releases use the package `im.collie.app`, so users can switch between them and install Collie alongside upstream Molly.

Releases are published on the [Releases page](https://github.com/0cwa/collieim-android/releases) as APKs. The Obtainium links above add this repository to [Obtainium](https://obtainium.imranr.dev/) with pre-releases disabled and select that release's APK, so Obtainium can install and update it. The releases share one app identity, so Obtainium tracks only one of them at a time.

The source badges below describe published release [v8.33.2-1-rc3](https://github.com/0cwa/collieim-android/tree/v8.33.2-1-rc3); the Android minimum applies to those APKs.

<p align="left">
  <a href="https://github.com/0cwa/collieim-android/blob/v8.33.2-1-rc3/molly-build.json"><img src="https://img.shields.io/badge/Signal%20base-8.33.2-96390C?style=flat&amp;labelColor=5A4033" alt="Signal base 8.33.2 in the published release"></a>
  <a href="https://github.com/0cwa/collieim-android/blob/v8.33.2-1-rc3/molly-build.json"><img src="https://img.shields.io/badge/Molly%20base-8.19.2--4-96390C?style=flat&amp;labelColor=5A4033" alt="Molly base 8.19.2-4 in the published release"></a>
  <a href="https://github.com/0cwa/collieim-android/blob/v8.33.2-1-rc3/gradle/libs.versions.toml"><img src="https://img.shields.io/badge/Kotlin-2.2.20-687E59?style=flat&amp;labelColor=5A4033" alt="Kotlin 2.2.20 in the published release"></a>
  <a href="https://github.com/0cwa/collieim-android/releases/tag/v8.33.2-1-rc3"><img src="https://img.shields.io/badge/release%20APK%20assets-5-687E59?style=flat&amp;labelColor=5A4033" alt="Five APK assets in the published release"></a>
</p>

_[Throw the dog a bone? 🐶🦴](https://liberapay.com/Collie)_

## Features

The releases retain Signal's messaging and calling features and Molly's additions, including:

- Passphrase encryption for the local message database and automatic locking.
- Clearing sensitive data from memory and the option to disable debug logging.
- Linked devices, including Android tablets.
- Blocking messages and calls from unknown contacts.
- Disappearing call history and scheduled local backups.
- Proxy and Tor support.
- WebSocket and UnifiedPush notifications as alternatives to Google's push service.

> [!CAUTION]
> ***If you use Unifiedpush only do so with a server you entrust your entire account and all the metadata of your account.*** (I found, reported, and verified this new major security risk to the Mollysocket developer - more details will be disclosed at a later date.)

Collie also offers four optional controls: keep received view-once media, keep incoming disappearing messages after they expire, ignore a sender's request to delete a message, and remove media without removing its message. All four are off by default. Content you choose to keep may appear in backups or exports. (this feature is experimental)

## Planned release names and direction

These changes are planned; the current downloads and names are listed above.

- **Mollie** will be the new name for Molly Minimal: Minimal Collie, or Minimal Molly Continued. Its aim is Molly, up to date, with fast bug and security fixes: Molly's minimalist feature set, patched quicker.
- **Collie** will mean Continued Molly. Molly Continued is planned to adopt the current Collie changes before taking the Collie name.
- **Collie+** will evolve from today's Collie into an edition for power users and community feedback, with cutting-edge features while remaining secure and stable. It is not intended as a beta.

## Free and open source

The code is open source. Standard builds include Google's Firebase service for push notifications; FOSS builds omit it. They can receive messages through WebSocket or [UnifiedPush](https://unifiedpush.org/) instead.

UnifiedPush lets you choose a notification provider. It requires access to a [MollySocket](https://github.com/mollyim/mollysocket) server, *use your own!* **Do not use a public one!** UnifiedPush has never been available on linked devices, [this is a planned Collie feature.](https://github.com/0cwa/collieim-android/issues/21) Android battery restrictions can affect notification delivery.

## Compatibility with Signal

Collie connects to Signal's servers and can communicate with Signal contacts. You can install Collie alongside Signal or Molly.

To use the same Signal account in two apps, one must be linked as a secondary device. Registering the same phone number independently in a second app will disconnect the first. You can also use separate numbers for separate accounts.

Signal's [Terms and Privacy Policy](https://signal.org/legal/) apply when using its service.

## Backups

The project retains Molly's support for local Signal-format backups. Restoring a backup requires an app version that supports the version of the backup being imported. Content kept using Collie's retention controls may also be included in backups.

## Feedback

<p align="left">
  <a href="https://github.com/0cwa/collieim-android/commits/main/"><img src="https://img.shields.io/github/last-commit/0cwa/collieim-android?style=flat&amp;labelColor=5A4033&amp;label=last%20commit" alt="Last commit on main"></a>
  <a href="https://github.com/0cwa/collieim-android/issues"><img src="https://img.shields.io/github/issues/0cwa/collieim-android?style=flat&amp;labelColor=5A4033&amp;label=issues" alt="Open GitHub issues"></a>
  <a href="https://github.com/0cwa/collieim-android/pulls"><img src="https://img.shields.io/github/issues-pr/0cwa/collieim-android?style=flat&amp;labelColor=5A4033&amp;label=PRs" alt="Open pull requests"></a>
  <a href="https://github.com/0cwa/collieim-android/security/advisories/new"><img src="https://img.shields.io/badge/security-report%20privately-687E59?style=flat&amp;labelColor=5A4033" alt="Report vulnerabilities privately"></a>
</p>

[Report bugs or suggest changes](https://github.com/0cwa/collieim-android/issues) in this repository.

## License

Licensed under the GNU Affero General Public License, version 3 only ([AGPL-3.0-only](LICENSE)). See [LEGAL.md](LEGAL.md) for legal and copyright information.

## Acknowledgements

Collie is independent of Signal and Molly and builds on the work of their contributors. Its retention controls are adapted from [Angus Turnbull's Signal FOSS fork](https://github.com/tw-hx/Signal-Android), including media deletion work by johanw.
