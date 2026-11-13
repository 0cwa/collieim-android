# Reproducible Builds

[![Reproducible build](https://github.com/mollyim/mollyim-android/actions/workflows/reprocheck.yml/badge.svg)](https://github.com/mollyim/mollyim-android/actions/workflows/reprocheck.yml)

Follow these instructions to verify that this source code is exactly the same code that was used to compile the APK distributed on the website.

The [reproducible-builds.org](https://reproducible-builds.org/) project has more information about this general topic.

## Prerequisites

- Docker
- Docker Compose
- Python 3

## Build and Verify

You can compile you own release of Molly inside a Docker container and compare the resulted APK to the APK that is officially distributed. To do so, execute the following:

```shell
# Set the release version you want to check
export VERSION=v5.42.8-2

# Clone the source code repository
git clone https://github.com/mollyim/mollyim-android.git

# Go to this directory
cd mollyim-android/tooling/reproducible-builds

# Check out the release tag
git checkout $VERSION

# The following steps might be different for the chosen version.
# Before proceeding, you should open this tutorial (README.md file) and review the instructions.

# Build the APK using the Docker environment
docker compose up --build

# Download the official APK
wget https://github.com/mollyim/mollyim-android/releases/download/$VERSION/Molly-$VERSION.apk

# Run the diff script to compare the APKs
python apkdiff/apkdiff.py Molly-$VERSION.apk outputs/apk/prodWebsite/release/Molly-unsigned-$VERSION.apk

# Clean up the Docker environment
docker compose down
```

If you get `APKs match`, you have **successfully verified** that the official release matches with your own self-built version of Molly. Congratulations!

If you get `APKs don't match`, please [report the issue](https://github.com/mollyim/mollyim-android/issues).

## Reproducibility settings and CI

The checked-in build uses `-Dcom.android.tools.r8.deterministicdebugging=true` and `android.r8.threadPoolSize=1`; AGP 9.4.0 bundles R8 9.4.14, which uses one thread in deterministic-debugging mode ([R8 source](https://r8.googlesource.com/r8/+/refs/tags/9.4.14/src/main/java/com/android/tools/r8/utils/InternalOptions.java)). The JVM settings are `-XX:+UnlockExperimentalVMOptions -XX:hashCode=3`; `hashCode=3` selects HotSpot's `Object.hashCode()` algorithm; it is not a `-D` property for R8. The build recipe does not use `-Dcom.android.tools.r8.threadCount=1` or `-DhashCode=3`.

Docker loads `tooling/gradle.properties.docker` as the Gradle user-home properties. User-home entries override matching project entries, while project entries with no user-home override still apply ([Gradle property precedence](https://docs.gradle.org/current/userguide/build_environment.html#sec:gradle_configuration_properties)). The Docker settings enable R8 deterministic-debugging mode; the project-level `org.gradle.jvmargs` and `android.r8.threadPoolSize=1` remain effective.

For a change under `tooling/reproducible-builds/`, the existing pull-request workflow builds the exact proposed commit twice in separate jobs and compares the unsigned APKs. A historical pair of independent Docker builds for source commit `10395d4d4d364d7fa8ab38cfc9895f4d32caad9e` produced SHA-256 `c5e6141cbe1a36f81e7e01863ae98d4b7f2cfeb1e7fb5d6b87873928765fe1a9`; [the comparison job reported a match](https://github.com/0cwa/collieim-android/actions/runs/42903894854/job/130159821020). This result is for that historical source commit; it does not compare the current source or guarantee every build will match.
