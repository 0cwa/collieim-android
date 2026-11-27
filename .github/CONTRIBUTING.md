# Contributing to Collie

Fork pull requests are welcome. This guide gets you from a local checkout to a
debug APK and a pull request.

## Set up the build

Install JDK 21 and point `JAVA_HOME` at it. If you use Android Studio, select
JDK 21 as the Gradle JDK. Set `ANDROID_HOME` to your Android SDK directory, or
set `sdk.dir` in your local `local.properties`.

Install Android SDK Platform 37.0, Build-Tools 36.0.0, Platform-Tools, and
NDK 28.0.13004108 through Android Studio's SDK Manager or with the Android SDK
command-line tools. For the commands below, add
`$ANDROID_HOME/cmdline-tools/latest/bin` to your `PATH`.

```sh
sdkmanager "platforms;android-37.0" "build-tools;36.0.0" "platform-tools" "ndk;28.0.13004108"
sdkmanager --licenses
```

The current versions are in [the version catalog](../gradle/libs.versions.toml)
and the [build image recipe](../tooling/reproducible-builds/Dockerfile).

## Build and test

From the repository root, use the Gradle wrapper:

```sh
./gradlew :app:assembleProdWebsiteDebug
```

Find the APK in `app/build/outputs/apk/prodWebsite/debug/`. For a focused unit
test run, select the class that covers your change. For example:

```sh
./gradlew :app:testProdWebsiteDebugUnitTest --tests '*CollieUpdateProfileTest'
```

The project defaults give Gradle a 4 GB heap (`-Xmx4g`) and the Kotlin daemon a
2 GB heap (`-Xmx2g`). For a smaller or larger machine, copy the
`org.gradle.jvmargs` and `kotlin.daemon.jvmargs` lines from
[gradle.properties](../gradle.properties) to `~/.gradle/gradle.properties`
and adjust only the `-Xmx` values. These properties replace the entire line, so
keep the other JVM flags. If memory is tight, try `--max-workers=2` as well.

## Open a pull request

1. Fork the repository, create a branch from `main`, and push your changes to
   your fork.
2. Open a pull request against `0cwa/collieim-android:main`. Describe what
   changed and which tests you ran. Screenshots are welcome for UI changes and
   optional for outside contributors.
3. GitHub Actions may wait for a maintainer to approve workflow runs from a
   first-time contributor. If the PR shows “Awaiting approval,” a maintainer
   can review the changes and approve the runs. See
   [GitHub's workflow approval guide](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/approve-runs-from-forks).

You can submit a fork PR without GPG or SSH commit-signing keys or `Layer:`
trailers. Maintainers handle GitHub-signed squash merges and add the
appropriate `Layer:` scope to the landed commit. While the existing Layer
trailers check is still enforced on fork PRs, a maintainer will handle that
check with you. The local Git hooks and preflight are maintainer tools.

## What CI runs

[PR checks](workflows/pr.yml) runs fast lint and Fastlane metadata checks.
Ready-for-review PRs also run unit tests and an unsigned production build.
`Security readiness` summarizes the unit-test and production-build results.
Other workflows cover debug APKs, CodeQL scans, and encrypted push regression
tests when their triggers apply.

Signing, publishing, and promotion use separate release workflows. The
[reproducible-build workflow](workflows/build-release.yml) compares independent
unsigned builds when a PR changes the build recipe. See the
[build guide](../docs/BUILD.md) and [release guide](../docs/RELEASING.md) for
release builds and the [source verification guide](../docs/security/VERIFY.md)
for provenance checks.
