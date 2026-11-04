# Collie

<p align="center">
  <img src="assets/collie-foreground.svg" alt="Collie icon" width="180">
</p>

Collie is an independent Android messenger for the [Signal](https://signal.org/) network. It builds on Signal's private messages and calls and on [Molly](https://molly.im/)'s protections for data stored on your phone, including passphrase encryption and automatic locking.

This project has two aims: to carry Molly forward as Signal changes, and to offer more control over what stays on your own device. Collie lets you choose to keep incoming disappearing messages and received view-once media, ignore a sender's request to delete a message, or remove an attachment without deleting its message. These settings are off by default. Content you keep may also appear in backups and exports.

We bring Molly's features to newer Signal releases, make the fixes needed along the way, and add Collie's retention options on top. The releases below share this work rather than being maintained as separate forks.

## Planned releases

| Release | What it includes |
| --- | --- |
| **Collie** | **Recommended.** Molly Continued with optional controls for keeping messages and media. |
| **Collie FOSS** | Collie without Firebase. |
| **Molly Continued** | Molly's features on a newer Signal version, with additional registration and notification fixes. |
| **Molly FOSS Continued** | Molly Continued without Firebase. |
| **Molly Minimal** | **Not recommended.** A basic Molly update without the later fixes. |

Molly Minimal lacks network settings during registration for people using proxies, visible errors and retry options when UnifiedPush registration fails, and updated donation completion text. All four other releases include these changes.

The FOSS releases omit Firebase, a Google service used to deliver notifications. The Molly releases keep Molly's app identity; Collie has its own, so it can be installed alongside Molly. These are planned releases, not currently available downloads.

## Credits

Collie is not affiliated with Signal or Molly. It builds on their work, and its retention settings are adapted from [Angus Turnbull's Signal FOSS fork](https://github.com/tw-hx/Signal-Android), including work on media deletion by johanw.

The project is licensed under [AGPL-3.0-only](LICENSE). See [LEGAL.md](LEGAL.md) for legal and copyright information.
