plugins {
  id("signal-library")
}

android {
  namespace = "org.signal.billing"
}

dependencies {
  lintChecks(project(":lintchecks"))

  implementation(libs.android.billing)
  implementation(project(":core:util"))

  testImplementation(testLibs.junit.junit)
  testImplementation(testLibs.assertk)
}
