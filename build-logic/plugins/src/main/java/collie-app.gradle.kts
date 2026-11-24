/*
 * Collie build settings for :app. They live here so app/build.gradle.kts stays close to Molly's.
 *
 * Settings that replace a value Molly's script sets are applied in finalizeDsl, after the app script has run, so the
 * app script can keep Molly's lines unchanged.
 */

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension

// Publication migration gate: remove only with reviewed public native inputs and
// direct-source acceptance. The legacy operator route is retained in engineering.
val nativeAcceptanceFile = rootProject.file("build-inputs/native-publication.json")
val nativeAcceptanceText = if (nativeAcceptanceFile.isFile) nativeAcceptanceFile.readText() else ""
val nativeInputsAccepted = nativeAcceptanceText.contains("\"status\": \"ACCEPTED\"") &&
  nativeAcceptanceText.contains("\"evidence_sha256\": \"20b21855db0175409618156f0247aa25503fcb256f1c3075959d33c87244f3c4\"")
if (!nativeInputsAccepted) {
  throw GradleException("Direct Android builds BLOCKED: accepted native publication evidence is absent or differs; see docs/BUILD.md")
}

// Release identity. The release-prep PR sets these in gradle.properties (docs/RELEASING.md).
val releaseVersionCode = providers.gradleProperty("releaseVersionCode")
val releaseVersionName = providers.gradleProperty("releaseVersionName")
// The release recipe supplies this only after the existing preparation verifier succeeds.
// A direct Gradle/PR build is a development preview even when it retains the last release's version.
val releaseNotesPrepared = providers.gradleProperty("collieReleasePrepared").map { it.toBooleanStrict() }.orElse(false)

// Reproducible build identity: the commit time and revision are tracked properties, not read from Git.
val buildTimestamp = providers.gradleProperty("collieBuildTimestamp")
val buildRevision = providers.gradleProperty("collieBuildRevision")

val testShard = providers.gradleProperty("collieAppTestShard")
val isolatedPushTests = providers.gradleProperty("isolatedPushTests").isPresent
val imoTests = providers.gradleProperty("imoTests").isPresent

pluginManager.withPlugin("com.android.application") {
  extensions.getByType<ApplicationAndroidComponentsExtension>().finalizeDsl { android ->
    android.defaultConfig.apply {
      versionCode = releaseVersionCode.orNull?.toInt()
        ?.also { require(it > 177702) { "Release versionCode must exceed published RC1 and all later reservations" } }
        ?: versionCode
      // Source builds without a release identity are marked as unvalidated ports.
      versionName = releaseVersionName.orNull ?: "$versionName-port-unvalidated"
      buildConfigField("boolean", "COLLIE_RELEASE_PREPARED", releaseNotesPrepared.get().toString())

      buildConfigField("String", "FDROID_UPDATE_URL", "\"https://repo.collie.im/fdroid/repo\"")
      buildConfigField("long", "BUILD_TIMESTAMP_OR_ZERO", buildTimestamp.get() + "000L")
      buildConfigField("String", "GIT_HASH", "\"${buildRevision.get()}\"")

      require(!isolatedPushTests || imoTests) {
        "isolatedPushTests requires imoTests so the isolated runner and package remain test-only"
      }
      testInstrumentationRunner = if (isolatedPushTests) {
        "org.thoughtcrime.securesms.testing.SignalTestRunner"
      } else if (imoTests) {
        "org.thoughtcrime.securesms.testing.incomingmessageobserver.IncomingMessageObserverTestRunner"
      } else {
        "org.thoughtcrime.securesms.testing.SignalTestRunner"
      }
    }

    android.buildTypes.getByName("debug").apply {
      buildConfigField("boolean", "COLLIE_RELEASE_PREPARED", "false")
      // The IMO harness signs its synthetic messages with a publicly known test key.
      // Keep that trust root and package identity confined to an explicit debug test build.
      if (imoTests) {
        applicationIdSuffix = ".imo"
        buildConfigField("String[]", "UNIDENTIFIED_SENDER_TRUST_ROOTS", "new String[]{\"BVT/2gHqbrG1xzuIypLIOjFgMtihrMld1/5TGADL6Dhv\"}")
      }
      buildConfigField("String", "GIT_HASH", "\"abc123def456\"")
    }
  }

  // Unit tests run in two CI shards with a bounded heap.
  extensions.configure<ApplicationExtension> {
    testOptions.unitTests.all { task ->
      task.maxHeapSize = "1g"
      testShard.orNull?.toInt()?.let { shard ->
        require(shard in 0..1)
        task.include { element ->
          val className = element.relativePath.pathString.substringBefore('$').removeSuffix(".class")
          element.isDirectory || Math.floorMod(className.hashCode(), 2) == shard
        }
      }
    }
  }
}
