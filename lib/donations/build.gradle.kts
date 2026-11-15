plugins {
  id("signal-library")
  id("kotlin-parcelize")
}

android {
  namespace = "org.signal.donations"
}

// Shared backup payment types and historical donation record decoding only.
dependencies {
  implementation(project(":core:util"))
  api(project(":core:network"))
  api(libs.arrow.core)
  implementation(libs.libsignal.android)
  testImplementation(testLibs.robolectric.robolectric) {
    exclude(group = "com.google.protobuf", module = "protobuf-java")
  }
  testImplementation(testFixtures(project(":lib:libsignal-service")))
}
