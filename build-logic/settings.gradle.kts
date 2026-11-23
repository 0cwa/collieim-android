pluginManagement {
  repositories {
    // The kotlin-dsl plugin and its Kotlin libraries otherwise come from the Gradle plugin portal,
    // which forwards to Maven Central and fails with HTTP 429 on CI runners. Mirror first.
    maven("https://maven-central.storage-download.googleapis.com/maven2/")
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
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
  versionCatalogs {
    create("libs") {
      from(files("../gradle/libs.versions.toml"))
    }
    create("testLibs") {
      from(files("../gradle/test-libs.versions.toml"))
    }
  }
}

rootProject.name = "build-logic"

include(":plugins")
include(":tools")
