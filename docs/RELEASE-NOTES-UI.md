# In-app release notes

## Interaction

Collie's release notes start as a small **What's new** entry below the Chats toolbar. Tapping the entry expands an inline card. The card occupies layout space, so it cannot cover a conversation row, the compose button, or bottom navigation. It has no scrim, countdown, automatic focus request, or launch-time dialog.

The expanded card keeps **Minimize** at the top and **Got it** at the bottom. Its body scrolls independently when text or the window size requires it. In very short windows or at large font sizes, **Got it** moves into that scrollable body so fixed controls cannot consume the entire reading area; **Minimize** remains visible. All controls have at least a 48 dp touch target. Colors and type come from the existing theme, including dark and dynamic-color themes.

When a short window uses bottom navigation, the existing camera and compose buttons sit side by side while the notice is visible. This preserves their touch targets and leaves a readable strip for the notes instead of compressing a vertical button stack.

| Action or event | Result |
| --- | --- |
| First encounter with a bundled notes edition | Show the minimized entry. |
| Tap the entry or **What's new** in the Chats menu | Expand the notes. |
| Minimize or Back while the card is expanded | Return to the minimized entry. |
| Got it, or the entry's close button | Dismiss this edition and remember the choice. |
| Reopen dismissed notes from the Chats menu | Open temporarily; the saved dismissal remains in effect on a later launch. |
| Rotate the activity | Retain the current presentation in the activity's view model. |
| Start a new process | Start minimized, or remain dismissed if this edition was dismissed. |
| Publish a genuinely new notes edition | Make the new edition available without changing older dismissal choices. |

The host shows the card only in the normal Chats list. Search, selection, other tabs and conversation details take precedence. Passive notices yield to active megaphones; an explicit menu selection can still open the notes. While reading expanded notes, other inline megaphones are temporarily hidden and return unchanged when the notes are minimized or dismissed. Their saved state is never snoozed or acknowledged by this feature, and fullscreen flows retain their existing visibility callbacks. Leaving the eligible context minimizes expanded notes so returning to messaging does not reopen a large card.

The support invitation appears only within the expanded body, after the release highlights and full-notes link. **Support Collie** opens the existing `donate_collie_url` resource in the browser. There is no donation badge, recurring prompt, or additional network request from the notice itself.

## Publishing notes

The initial edition explains the new release-notes interaction. It does not claim that unrelated historical fixes belong to the next build.

When preparing a new edition:

1. Update the `CollieUpdates__...` title, summary and highlight resources in `app/src/main/res/values/collie_updates.xml`. Keep the highlights short, describe what users can do or what improved, and verify each claim against the release being prepared.
2. Give `CollieReleaseNotes.ID` a new stable, descriptive content identifier. Change it when publishing new editorial content, not for every rebuild, signing change, or APK version bump.
3. Keep the installed-version label separate from the notes' content identity. It comes from `ApkInfo.versionName` and explicitly says **Installed version**. A rebuild must not relabel old content as a new release or make dismissed notes reappear.
4. Review the full-notes destination. The initial edition links to the project's release history. For release-specific notes, use the exact published release URL once it is known rather than guessing a tag.
5. Check the native light and dark layouts, minimized state, large text and a short window. Open the changed screen on an API 35 emulator and capture screenshots before merging, as required by `AGENTS.md`.

Dismissals are local encrypted-store preferences keyed by content identifier. They are excluded from account/backup settings, so this UI does not become account state or interfere with the existing APK update checker.

## Reusing the presentation for update reminders

`UpdateNoticeCard` contains only presentation and callbacks. `UpdateNoticeViewModel` owns expansion and dismissal, while `UpdateNoticeValues` stores independent dismissal choices. A future update reminder can reuse the layout with its own content identifier and actions.

The card accepts separate compact-title and read-more labels, and its support footer is optional; a caller that omits `onSupport` has no donation copy, link, or divider.

Reminder eligibility, release discovery, scheduling, and installation should be explicit additions at the caller. Do not turn an activity restart or a periodic foreground event into a renewed prompt, reuse a release-notes identifier for a different notification, or erase an earlier dismissal to force another appearance. The current change intentionally schedules no reminders and sends no system notifications.

## Verification

The important state checks are minimizing and dismissing independently, reopening after dismissal, restarting the activity/process, and introducing another content identifier. UI checks should cover navigation away from Chats, Back, keyboard/search and selection, plus successful and unavailable-browser link handling.

For screenshots, install the app and instrumentation APKs produced by the same build. Disable screen security only for the capture and restore its previous value in `finally`; `FLAG_SECURE` otherwise blanks the result. A test invocation that reports zero collected tests is not a successful verification.
