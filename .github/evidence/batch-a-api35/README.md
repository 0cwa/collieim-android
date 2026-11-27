# Batch A API 35 proof

The app and instrumentation APKs came from the same build in each edition. Both used the app source from the combined proof checkout a6362d7be72a92ef42169c100e5bda37ae779a6f and a temporary instrumentation fixture that seeded synthetic messages. The fixture disabled screen security for the capture and restored it afterward. Images were captured on the running Android 15 emulator through T3 Code.

| PR | Observed behavior |
| --- | --- |
| #284 | The reaction overlay exposed six labeled reaction buttons and a More reactions button. Tapping thumbs up applied the reaction. Reopening the overlay exposed Remove thumbs up reaction. |
| #287 | Three instrumentation cases passed. One accepted text within the grapheme limit despite its UTF-16 length. The other two rejected over-limit questions and options. |
| #288 | The geo URI rendered as a link and launched Google's Maps handler for the fixture location. The Linkifier suite passed 110 tests. |
| #289 | The 32-line fixture displayed 16 lines and Read More in the conversation. Read More opened the complete message. |
| #290 | Sharing an image from Android Files opened an empty caption and restored the existing composer draft after Send. The synthetic device had app networking disabled. The focused test also passed and verified the stored draft and captionless outgoing message. |
| #291 | The conversation-row instrumentation test passed. It checked the accessibility labels and invoked Select through the accessibility node. Screenshots show the same conversation row and selected state. |
| #292 | The seeded message contains a URL with no stored preview. On Stable, the Links tab showed No media. |
| #293 | The visible Collie settings entry and Web link preview images title used italics in Debug. Neighboring titles and the description remained in regular type. |

Debug APK SHA-256 hashes

- App `60aae683510849e237f123bfe35f9c330b5cddaae91f637e83a07719b03787dc`
- Instrumentation `23bc851b900169ac3f62abf91674ba8ae93a63be3eb6fd1857ebaa89331661d3`
