plugins {
  alias(libs.plugins.jetbrains.kotlin.jvm)
  id("java-library")
}

java {
  sourceCompatibility = JavaVersion.toVersion(libs.versions.javaVersion.get())
  targetCompatibility = JavaVersion.toVersion(libs.versions.javaVersion.get())
}

kotlin {
  jvmToolchain {
    languageVersion = JavaLanguageVersion.of(libs.versions.kotlinJvmTarget.get())
  }
}

dependencies {
  implementation(gradleApi())

  implementation(libs.dnsjava)
  api(libs.square.okhttp3)

  testImplementation(testLibs.junit.junit)
  testImplementation(testLibs.mockk)
  testImplementation(testLibs.square.mockwebserver)
}
