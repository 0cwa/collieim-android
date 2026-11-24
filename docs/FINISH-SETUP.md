# Finish setup, status line and What's new

Chats has one place to say that something needs attention: a line under "Collie" in the toolbar. It shows one thing at a time and is empty when nothing needs saying. Only problems that stop messages from arriving on time also get a banner above the chats. A new account's empty chat list shows the setup checklist instead of "No chats yet".

## What the status line says

The first rule that applies wins (`SetupRules.evaluate`):

| Rule | Status line | Tap opens | Banner |
| --- | --- | --- | --- |
| Notifications are blocked by Android | Notifications are turned off (red) | Turn-on-notifications sheet | Yes |
| UnifiedPush is on but no push app acknowledges it | Push app isn't available (red) | UnifiedPush settings | Yes |
| Messages arrive late (`SlowNotificationHeuristics`) and Android limits the app | Messages may arrive late (red) | Battery exemption prompt | Yes |
| Push health fell back to the app's own connection | Push isn't working (red) | UnifiedPush settings | Yes |
| The bundled notes carry an action the person hasn't taken | Action needed after update | What's new | Yes, until acted on or dismissed |
| Bundled notes unseen, update less than 3 days old | Updated · See what's new | What's new | No |
| Setup items left that aren't hidden | Finish setup · N left | Setup sheet | No |

Each wording has shorter fallbacks in `collie_setup.xml`. `FittingText` picks the longest one that fits on one line, so the line is never cut off with "…". When large text leaves no room for both lines, the toolbar shows only the status line.

Dismissing a problem banner hides it for 3 days; the red status line stays until the problem is fixed. Turning message notifications off inside Collie is a choice, not a problem.

## Setup items

| Item | Shown when | Done when |
| --- | --- | --- |
| Notifications | Always | Android allows Collie's message notifications |
| How messages reach you | Always | UnifiedPush works, Google push is in use, or the person chose to stay connected (or allowed background use) |
| Background use | Collie keeps its own connection open | Battery optimization is off for Collie. Dismissing the old battery reminder counts as skipped |
| Update checks | Website builds (`MANAGE_COLLIE_UPDATES`) | Checks are on |
| Backups | Always | On-device backups are on |

**Hide for now** (setup sheet or empty list) hides the items left at that moment. A different item becoming due shows the prompts again. Finish setup stays in the Chats menu while anything is left.

This replaces the battery-restriction banner (`DozeBanner`) and the notifications and app-updates prompts at the bottom of Chats.

## Publishing release notes

The notes ship inside the APK. No network request is made to show them.

1. Write translated highlights in `app/src/main/res/values/collie_setup.xml` (`CollieWhatsNew__…`) and register their content edition in `CollieReleaseNotes`. Each claim must be true for the release that selects it; the current Signal version comes from `ApkInfo`.
2. Select that edition with the release record's `highlights` field (see [PROJECT-LOG.md](PROJECT-LOG.md)). Its ID names the content, not the APK version: change it only when the news changes. Missing highlights produce no update announcement, including in development builds.
3. Give an edition an `action` only when people must do something after updating, for example re-select a push app. It shows as a banner until they act or dismiss it. Leave it `null` for feature news.
4. New installs never see "Updated": `CollieSetupValues.onFirstEverAppLaunch` marks the bundled notes as seen.

What's new is always reachable from the Chats menu and from Settings → Help. Both tabs use the same selected release. Showing the highlights marks that content edition seen; opening only the Project log does not.
