plugins {
  java
  id("org.jetbrains.intellij.platform") version "2.18.1"
}

val pluginVersion = providers.gradleProperty("pluginVersion").get()
val platformVersion = providers.gradleProperty("platformVersion").get()
val pluginSinceBuild = providers.gradleProperty("pluginSinceBuild").get()
val analyzerVersion = providers.gradleProperty("analyzerVersion").get()

group = providers.gradleProperty("pluginGroup").get()

version = pluginVersion

repositories {
  mavenCentral()
  intellijPlatform { defaultRepositories() }
}

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

dependencies {
  // The JSON wire-format model/parser lives in :model, a plain Java module with no IntelliJ
  // Platform dependency — see model/build.gradle.kts for why it's split out.
  implementation(project(":model"))

  // No IntelliJ testFramework() here on purpose: this milestone has no IDE-sandboxed tests
  // (see plan doc). Adding it would force even plain JUnit tests through IntelliJ's own test
  // listener, which requires a running platform sandbox.
  intellijPlatform {
    intellijIdea(platformVersion)
    // Java PSI (PsiClass, PsiShortNamesCache) for click-to-source navigation.
    bundledPlugin("com.intellij.java")
    pluginVerifier()
  }
}

intellijPlatform {
  pluginConfiguration {
    id.set("com.ionutbanu.scopetracer.plugin")
    name.set("Scope Tracer")
    version.set(pluginVersion)
    ideaVersion { sinceBuild.set(pluginSinceBuild) }
  }
}

// No tests live directly in this module for this milestone (see :model's build.gradle.kts and
// the plan doc) — disabled outright rather than left to fail on the IntelliJ Platform Gradle
// plugin's automatic test-task instrumentation, which requires a running IDE sandbox.
tasks.test { enabled = false }

// --- Bundle the scope-tracer-analyzer executable jar as a plugin resource ---
//
// The analyzer is a Maven module compiled with --enable-preview (JDK 26) and can never be
// loaded inside the IDE's own JVM (JBR, JDK 21). The plugin instead ships the analyzer's
// executable jar as a resource and runs it as an external subprocess against a real JDK 26 —
// see AnalyzerProcessRunner. This task copies that jar out of the Maven build's `target/`.
val copyAnalyzerJar =
    tasks.register<Copy>("copyAnalyzerJar") {
      val analyzerJar =
          rootDir.resolve(
              "../scope-tracer-analyzer/target/scope-tracer-analyzer-$analyzerVersion-executable.jar")
      doFirst {
        if (!analyzerJar.exists()) {
          throw GradleException(
              "Analyzer executable jar not found at $analyzerJar — build it first from the " +
                  "repo root with `mvn -q install -DskipTests` (or if `analyzerVersion` in " +
                  "gradle.properties is stale relative to the Maven parent POM version, update it).")
        }
      }
      from(analyzerJar)
      into(layout.projectDirectory.dir("src/main/resources/analyzer"))
      rename { "analyzer.jar" }
    }

tasks.named("processResources") { dependsOn(copyAnalyzerJar) }
