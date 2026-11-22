plugins {
  alias(libs.plugins.jetbrains.kotlin.jvm) apply false
}

buildscript {
  repositories {
    // Google's mirror of Maven Central answers first: Central itself rate-limits CI runners (HTTP 429)
    // and Gradle reports that as a missing plugin. Missing files fall through to Central, and
    // dependency verification checks every artifact whichever repository served it.
    maven("https://maven-central.storage-download.googleapis.com/maven2/")
    mavenCentral()
  }
}
