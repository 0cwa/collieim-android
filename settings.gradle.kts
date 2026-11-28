pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("(com\\.(android|google)|androidx?)(\\..*)?")
      }
    }
    // Google's mirror of Maven Central answers first: Central itself rate-limits CI runners (HTTP 429)
    // and Gradle reports that as a missing plugin. Missing files fall through to Central, and
    // dependency verification checks every artifact whichever repository served it.
    maven("https://maven-central.storage-download.googleapis.com/maven2/")
    mavenCentral()
  }
  includeBuild("build-logic")
}

buildCache {
  local {
    isEnabled = true
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    // Collie native inputs come only from the published v3 repository.
    exclusiveContent {
      forRepository {
        maven {
          name = "CollieNativeInputsV3RingRtc"
          url = uri("https://repo.collie.im/native-inputs/v3/maven/")
          metadataSources { mavenPom(); ignoreGradleMetadataRedirection() }
        }
      }
      filter {
        includeModule("im.collie.native", "ringrtc-android")
      }
    }
    exclusiveContent {
      forRepository {
        maven {
          name = "CollieNativeInputsV3Libsignal"
          url = uri("https://repo.collie.im/native-inputs/v3/maven/")
          metadataSources { mavenPom(); ignoreGradleMetadataRedirection() }
        }
      }
      filter {
        includeModule("im.collie.native", "libsignal-android")
        includeModule("im.collie.native", "libsignal-client")
      }
    }
    google {
      content {
        includeGroupByRegex("(com\\.(android|google)|androidx?)(\\..*)?")
      }
    }
    maven {
      url = uri("https://raw.githubusercontent.com/signalapp/maven/master/sqlcipher/release/")
      content {
        includeModule("org.signal", "sqlcipher-android")
      }
    }
    maven {
      url = uri("https://raw.githubusercontent.com/signalapp/maven/master/aesgcmprovider/release/")
      content {
        includeModule("org.signal", "aesgcmprovider")
      }
    }
    maven("https://maven-central.storage-download.googleapis.com/maven2/")
    mavenCentral()
  }
  versionCatalogs {
    // libs.versions.toml is automatically registered.
    create("testLibs") {
      from(files("gradle/test-libs.versions.toml"))
    }
    create("lintLibs") {
      from(files("gradle/lint-libs.versions.toml"))
    }
    // MOLLY: Play Services compatibility reference versions.
    create("playServices") {
      from(files("core/gms/play-services.versions.toml"))
    }
  }
}

// Native inputs use the pinned public repository; no external source includeBuild.

// Main app
include(":app")

// Core modules
include(":core:util")
include(":core:util-jvm")
include(":core:network")
include(":core:ui")
include(":core:serialization")
include(":core:database")

// FOSS GMS modules
include(":core-gms:base")
include(":core-gms:cloud-messaging")
include(":core-gms:safeparcel")
include(":core-gms:safeparcel-processor")
include(":core-gms:tasks")

// Lib modules
include(":lib:libsignal-service")
include(":lib:netcipher")
include(":lib:network")
include(":lib:glide")
include(":lib:photoview")
include(":lib:sticky-header-grid")
include(":lib:paging")
include(":lib:device-transfer")
include(":lib:donations")
include(":lib:contacts")
include(":lib:qr")
include(":lib:video")
include(":lib:image-editor")
include(":lib:debuglogs-viewer")
include(":lib:blurhash")
include(":lib:apng")
include(":lib:emoji")
include(":lib:archive")
include(":lib:ui-components")
include(":lib:signal-login")
include(":lib:password-manager")
include(":lib:storage")
include(":lib:remote-config")
include(":lib:locale")

// Feature modules
include(":feature:app-settings")
include(":feature:registration")
include(":feature:camera")
include(":feature:media-send")
include(":feature:chat-settings")
include(":feature:media-keyboard")
include(":feature:media-viewer")
include(":feature:media-overview")

// Testing/Lint modules
include(":screener")
include(":lintchecks")
include(":fast-lint")

// Stable Gradle project IDs backed by grouped source directories.
project(":core-gms").projectDir = file("core/gms")
project(":core-gms:base").projectDir = file("core/gms/base")
project(":core-gms:cloud-messaging").projectDir = file("core/gms/cloud-messaging")
project(":core-gms:safeparcel").projectDir = file("core/gms/safeparcel")
project(":core-gms:safeparcel-processor").projectDir = file("core/gms/safeparcel-processor")
project(":core-gms:tasks").projectDir = file("core/gms/tasks")
project(":screener").projectDir = file("tooling/screener")
project(":lintchecks").projectDir = file("tooling/lintchecks")
project(":fast-lint").projectDir = file("tooling/fast-lint")
val encryptedPushFuzzProject = file("tooling/fuzz-encrypted-push")
if (encryptedPushFuzzProject.resolve("build.gradle.kts").isFile) {
  include(":fuzz-encrypted-push")
  project(":fuzz-encrypted-push").projectDir = encryptedPushFuzzProject
}

rootProject.name = "Molly"
