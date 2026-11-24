plugins {
  java
  id("org.jetbrains.intellij.platform") version "2.18.1"
}

val platformVersion = providers.gradleProperty("platformVersion").get()
val pluginSinceBuild = providers.gradleProperty("pluginSinceBuild").get()

// The analyzer jar's version is the Maven project version; read it from the parent POM so a
// version bump there cannot leave this build pointing at a jar that no longer exists.
val analyzerVersion: String =
    javax.xml.parsers.DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(rootDir.resolve("../pom.xml"))
        .documentElement
        .childNodes
        .let { nodes -> (0 until nodes.length).map { nodes.item(it) } }
        .first { it.nodeName == "version" }
        .textContent
        .trim()

group = providers.gradleProperty("pluginGroup").get()

// The plugin's version is the Maven project version (#151): the zip bundles that exact analyzer
// and agent, so one number names one set of bits and there is nothing to bump here.
version = analyzerVersion

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

// Change notes are a link, not rendered text: a release links its GitHub Release and the tagged
// CHANGELOG.md, a -SNAPSHOT links CHANGELOG.md's [Unreleased] section. No changelog plugin.
val changeNotesHtml: String =
    "https://github.com/ionut-banu/scope-tracer".let { repo ->
      if (analyzerVersion.endsWith("-SNAPSHOT")) {
        "<p>Development build. See the <a href=\"$repo/blob/main/CHANGELOG.md#unreleased\">" +
            "[Unreleased] section of the changelog</a>.</p>"
      } else {
        "<p>See the <a href=\"$repo/releases/tag/v$analyzerVersion\">GitHub Release " +
            "$analyzerVersion</a> and the <a href=\"$repo/blob/v$analyzerVersion/CHANGELOG.md\">" +
            "[$analyzerVersion] section of the changelog</a>.</p>"
      }
    }

intellijPlatform {
  pluginConfiguration {
    id.set("com.ionutbanu.scopetracer.plugin")
    name.set("Scope Tracer")
    version.set(analyzerVersion)
    changeNotes.set(changeNotesHtml)
    ideaVersion { sinceBuild.set(pluginSinceBuild) }
  }
}

// No tests live directly in this module for this milestone (see :model's build.gradle.kts and
// the plan doc) — disabled outright rather than left to fail on the IntelliJ Platform Gradle
// plugin's automatic test-task instrumentation, which requires a running IDE sandbox.
tasks.test { enabled = false }

// --- Bundle the scope-tracer-analyzer executable jar as a plugin resource ---
//
// The analyzer is a Maven module compiled with --enable-preview (JDK 27) and can never be
// loaded inside the IDE's own JVM (JBR, JDK 21). The plugin instead ships the analyzer's
// executable jar as a resource and runs it as an external subprocess against a real JDK 27 —
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
                  "repo root with `mvn -q -DskipTests package`.")
        }
      }
      from(analyzerJar)
      into(layout.projectDirectory.dir("src/main/resources/analyzer"))
      rename { "analyzer.jar" }
    }

// --- Bundle the agent fat-jar the same way, for "Run with Scope Tracer" ---
//
// The agent jar's manifest names itself in Boot-Class-Path as
// `scope-tracer-agent-<version>-agent.jar`, resolved next to the jar, so the plugin must extract it
// under exactly that file name. The name is written to a resource beside the jar.
val copyAgentJar =
    tasks.register<Copy>("copyAgentJar") {
      val agentJarName = "scope-tracer-agent-$analyzerVersion-agent.jar"
      val agentJar = rootDir.resolve("../scope-tracer-agent/target/$agentJarName")
      val outDir = layout.projectDirectory.dir("src/main/resources/agent")
      doFirst {
        if (!agentJar.exists()) {
          throw GradleException(
              "Agent jar not found at $agentJar — build it first from the repo root with " +
                  "`mvn -q -DskipTests package`.")
        }
      }
      from(agentJar)
      into(outDir)
      doLast { outDir.file("agent-jar-name.txt").asFile.writeText(agentJarName) }
    }

tasks.named("processResources") { dependsOn(copyAnalyzerJar, copyAgentJar) }
