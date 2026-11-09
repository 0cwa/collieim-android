plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.jetbrains.kotlin.android) apply false
  alias(libs.plugins.jetbrains.kotlin.jvm) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.licensee) apply false
  id("dependency-verification")
  id("mollyify")
}

buildscript {
  repositories {
    google {
      content {
        includeGroupByRegex("(com\\.(android|google)|androidx?)(\\..*)?")
      }
    }
    mavenCentral()
  }

  dependencies {
    classpath(libs.gradle)
    classpath(libs.androidx.navigation.safe.args.gradle.plugin)
    classpath(libs.protobuf.gradle.plugin)
    classpath("com.squareup.wire:wire-gradle-plugin:6.4.5") {
      exclude(group = "com.squareup.wire", module = "wire-swift-generator")
      exclude(group = "com.squareup.wire", module = "wire-grpc-client")
      exclude(group = "com.squareup.wire", module = "wire-grpc-jvm")
      exclude(group = "com.squareup.wire", module = "wire-grpc-server-generator")
      exclude(group = "io.outfoxx", module = "swiftpoet")
    }
    classpath(libs.androidx.benchmark.gradle.plugin)
    // MOLLY: wire-handler factory now in buildSrc, no manual classpath needed
    classpath(libs.com.google.devtools.ksp.gradle.plugin)
  }
}

tasks.withType<Wrapper> {
  distributionType = Wrapper.DistributionType.ALL
}

<<<<<<< MOLLY
allprojects {
  tasks.withType<Javadoc> {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
=======
subprojects {
  if (JavaVersion.current().isJava8Compatible) {
    allprojects {
      tasks.withType<Javadoc> {
        (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
      }
    }
  }

  tasks.withType<Test>().configureEach {
    if (name.contains("ScreenshotTest")) {
      // Compose preview screenshot testing does not support parallel execution.
      maxParallelForks = 1

      // The plugin creates this task for every module, but most have no previews to validate.
      failOnNoDiscoveredTests = false
    } else {
      maxParallelForks = (Runtime.getRuntime().availableProcessors() / 4).coerceAtLeast(1)
    }

    // Raised for robolectric
    maxHeapSize = "2g"
  }

  // The reference images aren't tracked as outputs, so without this the task is skipped after they change on disk.
  tasks.matching { it.name.startsWith("update") && it.name.endsWith("ScreenshotTest") }.configureEach {
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Reference images are not tracked outputs") { true }
>>>>>>> SIGNAL
  }
}

abstract class ConcurrencyConstraintService : BuildService<BuildServiceParameters.None>

fun limiter(name: String, max: Int) =
  gradle.sharedServices.registerIfAbsent(name, ConcurrencyConstraintService::class.java) {
    maxParallelUsages.set(max)
  }

// MOLLY: Limit concurrency of high-RAM tasks to avoid OOMs (especially in CI)
val kLimiter = limiter("CC-kotlin", max = 3)
val lintLimiter = limiter("CC-lint", max = 1)
val r8Limiter = limiter("CC-r8", max = 1)

<<<<<<< MOLLY
=======
tasks.register("validateScreenshots") {
  group = "Verification"
  description = "Validates Compose screenshot tests. Intended to run only on CI, not local builds."
}

tasks.register("updateScreenshots") {
  group = "Verification"
  description = "Regenerates Compose screenshot reference images."
}

tasks.register("qaRemote") {
  group = "Verification"
  description = "Full qa plus screenshot validation. Intended to run on CI, not local builds."
  dependsOn("qa")
  dependsOn("validateScreenshots")
}

tasks.register("ciRemote") {
  group = "Verification"
  description = "Faster PR verification (ci) plus screenshot validation. Intended to run on CI, not local builds."
  dependsOn("ci")
  dependsOn("validateScreenshots")
}

// Wire up QA dependencies after all projects are evaluated
>>>>>>> SIGNAL
gradle.projectsEvaluated {
  val kotlinCompiles = allprojects
    .flatMap { it.tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>() }

  kotlinCompiles.forEach { task ->
    task.usesService(kLimiter)
  }

<<<<<<< MOLLY
  allprojects {
    tasks.named { it.startsWith("lint") && it.contains("Analyze") }.configureEach {
      usesService(lintLimiter)
      mustRunAfter(kotlinCompiles)
    }

    tasks.named { it.startsWith("minify") }.configureEach {
      usesService(r8Limiter)
=======
  // All modules get screenshotTests enabled by default, but the validate task fails if there's no tests.
  // So we just filter out the modules that have no tests ourselves.
  val screenshotTestTargets = subprojects
    .filter { it.file("src/screenshotTest").isDirectory }
    .map { it to if (it.name == "Signal-Android") "PlayProdDebug" else "Debug" }

  tasks.named("validateScreenshots") {
    screenshotTestTargets.forEach { (subproject, variant) ->
      subproject.tasks.findByName("validate${variant}ScreenshotTest")?.let { dependsOn(it) }
    }
  }

  tasks.named("updateScreenshots") {
    screenshotTestTargets.forEach { (subproject, variant) ->
      subproject.tasks.findByName("update${variant}ScreenshotTest")?.let { dependsOn(it) }
>>>>>>> SIGNAL
    }
  }
}

// MOLLY: Add task `./gradlew allDeps` to list all dependencies for each configuration
allprojects {
  tasks.register<DependencyReportTask>("allDeps")
}

tasks.register("clean", Delete::class) {
  delete(rootProject.layout.buildDirectory)
}
