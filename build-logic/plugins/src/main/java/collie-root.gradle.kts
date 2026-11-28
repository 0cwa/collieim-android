/*
 * Collie build settings for the root project. They live here so build.gradle.kts stays close to Molly's.
 */

// The production client JAR has no desktop JNI. Keep the separately built Linux
// library on host test classpaths only, so it can never become an APK input.
val hostJniDirectory = providers.gradleProperty("collieHostJniDir").orNull?.let { file(it) }

gradle.projectsEvaluated {
  allprojects {
    hostJniDirectory?.let { directory ->
      val library = directory.resolve("libsignal_jni_testing_amd64.so")
      val probe = rootProject.file("tooling/host-tests/InitializeNative.java")
      tasks.withType<Test>().configureEach {
        classpath = files(directory) + classpath
        // Security CI requires tests to execute on this candidate. Compilation
        // may use its normal caches, but stored test results cannot satisfy it.
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
        doFirst {
          check(library.isFile) {
            "Missing host libsignal JNI. Run python3 tooling/host-tests/build_libsignal.py first."
          }
          val java = javaLauncher.get().executablePath.asFile.absolutePath
          val command = listOf(java) + allJvmArgs + listOf("-cp", classpath.asPath, probe.absolutePath)
          val process = ProcessBuilder(command).apply {
            directory(workingDir)
            environment().clear()
            environment().putAll(environment.mapValues { it.value.toString() })
            redirectErrorStream(true)
          }.start()
          val output = process.inputStream.bufferedReader().use { it.readText() }
          check(process.waitFor() == 0) { "Host libsignal JNI failed to initialize in the test JVM environment:\n$output" }
        }
      }
    }
  }
}

// Collie native substitutions: ordinary tracked project configuration.
allprojects {
  configurations.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("im.molly:libsignal-android")).using(module("im.collie.native:libsignal-android:0.108.0-collie.1"))
        substitute(module("im.molly:libsignal-client")).using(module("im.collie.native:libsignal-client:0.108.0-collie.1"))
        substitute(module("im.molly:ringrtc-android")).using(module("im.collie.native:ringrtc-android:2.75.0-collie.1"))
        substitute(module("org.signal:libsignal-android")).using(module("im.collie.native:libsignal-android:0.108.0-collie.1"))
        substitute(module("org.signal:libsignal-client")).using(module("im.collie.native:libsignal-client:0.108.0-collie.1"))
        substitute(module("org.signal:ringrtc-android")).using(module("im.collie.native:ringrtc-android:2.75.0-collie.1"))
    }
  }
}
