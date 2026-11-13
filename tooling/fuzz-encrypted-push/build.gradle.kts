plugins {
  alias(libs.plugins.jetbrains.kotlin.jvm)
}

kotlin {
  jvmToolchain {
    languageVersion = JavaLanguageVersion.of(21)
  }
  sourceSets {
    val appMain = rootProject.file("app/src/main/java")
    main {
      kotlin.srcDir(appMain)
      kotlin.include(
        "im/molly/unifiedpush/enrollment/ControlKeyId.kt",
        "im/molly/unifiedpush/enrollment/EnrollmentFile.kt",
        "im/molly/unifiedpush/control/ControlFrame.kt",
        "im/molly/unifiedpush/receiver/EncryptedPush.kt",
      )
    }
    test {
      resources.srcDir(rootProject.file("app/src/test/resources"))
    }
  }
}

dependencies {
  implementation(libs.jackson.core)
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
  testImplementation(libs.jazzer.junit)
}

tasks.test {
  useJUnitPlatform()
  maxHeapSize = "1g"
}

tasks.register<Test>("jazzerFuzz") {
  group = "verification"
  description = "Runs the production encrypted push parser with bounded Jazzer discovery."
  dependsOn(tasks.testClasses)
  testClassesDirs = sourceSets.test.get().output.classesDirs
  classpath = sourceSets.test.get().runtimeClasspath
  useJUnitPlatform()
  filter { includeTestsMatching("*EncryptedPushFuzzTest*") }
  environment("JAZZER_FUZZ", "1")
  maxHeapSize = "1g"
}
