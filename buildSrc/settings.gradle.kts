pluginManagement {
  repositories {
    // Google's mirror of Maven Central answers first: Central itself rate-limits CI runners (HTTP 429)
    // and Gradle reports that as a missing plugin. Missing files fall through to Central, and
    // dependency verification checks every artifact whichever repository served it.
    maven("https://maven-central.storage-download.googleapis.com/maven2/")
    mavenCentral()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    maven("https://maven-central.storage-download.googleapis.com/maven2/")
    mavenCentral()
  }
  versionCatalogs {
    create("libs") {
      from(files("../gradle/libs.versions.toml"))
    }
  }
}
