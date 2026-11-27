# Photo wallpaper Automatic chat colors

Automatic uses the active photo wallpaper for outgoing bubbles. Explicit chat
colors still take precedence, and built-in wallpaper mappings still apply.
This setting is independent of the app palette.

The wallpaper worker samples new photos before publishing the selection.
Derived colors stay on the device and are deleted with their wallpaper
attachments. Old and restored photos are sampled when a relevant conversation
or color picker loads. If the conversation opens before its sample is ready,
the next normal wallpaper reload picks up the derived color. Invalid or
transparent photos keep the default Automatic color.

## Focused checks

Run the color and wallpaper JVM tests:

```sh
./gradlew --no-daemon :app:testProdWebsiteDebugUnitTest \
  --tests org.thoughtcrime.securesms.wallpaper.PhotoWallpaperColorsTest \
  --tests org.thoughtcrime.securesms.conversation.colors.CollieChatColorsTest
```

Build matching APKs and run the API 35 fixture as described in the
[theme regression recipe](theme-propagation-83.md):

```sh
./gradlew --no-daemon :app:connectedProdWebsiteDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=org.thoughtcrime.securesms.wallpaper.crop.PhotoWallpaperAutomaticColorTest \
  -Pandroid.testInstrumentationRunnerArguments.clearPackageData=false
```

The fixture checks the saved photo's derived color against the picker, wallpaper
preview and rendered outgoing bubble. It checks actual text and link colors in
light and dark mode, exercises an explicit override, and removes the wallpaper
to verify the Automatic fallback.
