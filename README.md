# Collie

<p align="center">
  <img src="assets/collie-foreground.svg" alt="Collie icon" width="180">
</p>

Collie is an independent Android messenger built on [Signal](https://signal.org/) and [Molly](https://molly.im/). It uses the Signal network for private messages and calls, with Molly's protections for data stored on your device.

This project keeps Molly's features available as Signal develops, while offering Collie as a separate app for people who want more control over what stays on their phone. The releases share the same source and build on one another.

## Planned releases

| Release | What it offers |
| --- | --- |
| **Collie** | **Recommended.** Molly Continued plus optional controls for keeping messages and media. |
| **Collie FOSS** | Collie without Firebase. |
| **Molly Continued** | Molly on a newer Signal version, with further registration and notification improvements. |
| **Molly FOSS Continued** | Molly Continued without Firebase. |
| **Molly Minimal** | **Not recommended.** The updated Molly foundation, without the later improvements. |

Molly Minimal lacks network settings during registration for proxy users, visible errors and retry options for failed UnifiedPush registration, and updated donation confirmation text. The other releases include these changes.

The Molly releases retain Molly's app identity so they can replace an existing Molly installation. Collie has its own app identity and can be installed alongside it. These releases are planned; they are not yet available for download.

## Features

The releases retain Signal's messaging and calling features and Molly's additions, including:

- Passphrase encryption for the local message database and automatic locking.
- Memory protection and the option to disable debug logging.
- Linked devices, including Android tablets.
- Blocking messages and calls from unknown contacts.
- Disappearing call history and scheduled local backups.
- Proxy and Tor support.
- WebSocket and UnifiedPush notifications as alternatives to Google's push service.

Collie also offers four optional controls: keep received view-once media, keep incoming disappearing messages after they expire, ignore a sender's request to delete a message, and remove media without removing its message. All four are off by default. Content you choose to keep may appear in backups or exports. Turning a setting off does not erase content already kept.

## Free and open source

All releases are open source. The FOSS versions omit Firebase, Google's service for push notifications. They can receive messages through WebSocket or [UnifiedPush](https://unifiedpush.org/) instead.

UnifiedPush lets you choose a notification provider. It requires access to a [MollySocket](https://github.com/mollyim/mollysocket) server, either your own or a public one. Android battery restrictions can affect notification delivery.

## Compatibility with Signal

Collie connects to Signal's servers and can communicate with Signal contacts. You can install Collie alongside Signal or Molly.

To use the same Signal account in two apps, one must be linked as a secondary device. Registering the same phone number independently in a second app will disconnect the first. You can also use separate numbers for separate accounts.

Signal's [Terms and Privacy Policy](https://signal.org/legal/) apply when using its service.

## Backups

The project retains Molly's support for local Signal-format backups. Restoring a backup requires an app version that supports the version of the backup being imported. Content kept using Collie's retention controls may also be included in backups.

## Feedback

[Report bugs or suggest changes](https://github.com/0cwa/collieim-android/issues) in this repository.

## License

Licensed under the GNU Affero General Public License, version 3 only ([AGPL-3.0-only](LICENSE)). See [LEGAL.md](LEGAL.md) for legal and copyright information.

## Acknowledgements

Collie is independent of Signal and Molly and builds on the work of their contributors. Its retention controls are adapted from [Angus Turnbull's Signal FOSS fork](https://github.com/tw-hx/Signal-Android), including media deletion work by johanw.
