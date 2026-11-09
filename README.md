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
<a href="https://matrix.to/#/#collieim:worldethicaldataforum.org">
  <img height="50" alt="matrix-banner" src="assets/collie-matrix-invite.svg">
</a>
</p>

<p align="center">
  <a href="https://github.com/0cwa/collieim-android/releases/latest"><img src="https://img.shields.io/github/downloads/0cwa/collieim-android/total?style=flat&amp;label=release%20downloads&amp;color=687E59&amp;labelColor=5A4033" alt="Total release asset downloads"></a>
  <a href="https://github.com/0cwa/collieim-android/releases/tag/v8.35.1-1-rc1"><img src="https://img.shields.io/badge/Android-8.1%2B-2F4F3A?style=flat&amp;labelColor=5A4033" alt="Android 8.1 or newer"></a>
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

Collie builds on the work of [Molly](https://github.com/mollyim-android), supporting newer Signal releases, and develops new functionality independently.

## Download

Use the Obtainium link above to stay updated.  Our apk `collie.apk` is on the [Releases page](https://github.com/0cwa/collieim-android/releases)  All APK files in the latest release contain the same Collie app; the older names are available for existing Obtainium filters. All are FOSS and all use the package `im.collie.app` and can be installed alongside Signal or Molly.  *F-droid-compatible repo coming soon.*

_[Throw the dog a bone? 🐶🦴](https://liberapay.com/Collie)_ - It helps me speed up development - I want to work on a desktop client too.

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

## Calling libraries and proxy safety

For the **8.35.1-2-rc1 reconstructed source**, Collie uses its own builds of Signal's **libsignal 0.106.1**, **RingRTC 2.74.0**, and the WebRTC code used for calls—not unmodified Signal binaries or unchanged Molly forks. They carry **Molly's proxy protections**, with **Collie adaptations** for newer Signal versions and fixes for call proxy credentials, unknown proxy types, and SOCKS IPv6. The [dependency catalog](gradle/libs.versions.toml) and [native lock](build-inputs/native-lock.json) identify the exact versions, source revisions, patches, and binary hashes; [native attribution](https://github.com/0cwa/collie-release-engineering/blob/main/tools/native/ATTRIBUTION.md) explains who contributed each change.

**Why this matters:** When you choose a SOCKS proxy, Collie's networking and libsignal code are designed to **refuse connections rather than silently bypass a failed proxy**, with proxy-aware DNS. When call proxy settings reach RingRTC, the Molly-derived WebRTC code routes TCP through HTTPS or SOCKS5 proxies and disables direct UDP/STUN call candidates. **Android system proxies are different:** a failed optional system proxy may fall back to a direct connection.

These safeguards have [application proxy regression tests](app/src/test/java/org/thoughtcrime/securesms/net/MollyProxyFailClosedTest.kt) and [libsignal policy tests](core/network/src/test/java/org/signal/network/config/LibSignalProxyPolicyTest.kt), plus bounded native tests detailed in the attribution record. However, the exact-source **local unsigned APK build** in [PR #45](https://github.com/0cwa/collieim-android/pull/45) and [native input acceptance](build-inputs/native-publication.json) **do not prove** that all live call traffic stays in the proxy. End-to-end proxy/call testing, runtime and MollySocket acceptance, and independently reproducible native builds remain unverified for this candidate.

## Free and open source

Collie is free and open-source software. Optional FCM notifications use an open-source client and Google's FCM service; WebSocket and [UnifiedPush](https://unifiedpush.org/) are alternatives.

UnifiedPush lets you choose a notification provider. It requires access to a [MollySocket](https://github.com/mollyim/mollysocket) server, *use your own!* **Do not use a public one!** UnifiedPush has never been available on linked devices, [this is a planned Collie feature.](https://github.com/0cwa/collieim-android/issues/21) Android battery restrictions can affect notification delivery.

## Compatibility with Signal

Collie connects to Signal's servers and can communicate with Signal contacts. You can install Collie alongside Signal or Molly.

To use the same Signal account in two apps, one must be linked as a secondary device. Registering the same phone number independently in a second app will disconnect the first. You can also use separate numbers for separate accounts.

Signal's [Terms and Privacy Policy](https://signal.org/legal/) apply when using its service.

## Backups

Collie supports local Signal-format backups. Restoring a backup requires an app version that supports the version of the backup being imported.

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

Collie is independent of Signal and Molly and builds on the work of their contributors.
