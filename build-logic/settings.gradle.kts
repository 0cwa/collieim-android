pluginManagement {
  repositories {
    // Resolve the kotlin-dsl plugin from its publishing repository; mirror other dependencies first.
    gradlePluginPortal {
      content {
        includeGroup("org.gradle.kotlin.kotlin-dsl")
        includeModule("org.gradle.kotlin", "gradle-kotlin-dsl-plugins")
      }
    }
    maven("https://maven-central.storage-download.googleapis.com/maven2/") {
      content {
        excludeGroup("org.gradle.kotlin.kotlin-dsl")
        excludeModule("org.gradle.kotlin", "gradle-kotlin-dsl-plugins")
      }
    }
    mavenCentral {
      content {
        excludeGroup("org.gradle.kotlin.kotlin-dsl")
        excludeModule("org.gradle.kotlin", "gradle-kotlin-dsl-plugins")
      }
    }
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
