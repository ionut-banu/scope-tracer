import java.util.Base64
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease
import org.jetbrains.intellij.platform.gradle.tasks.PublishPluginTask
import org.jetbrains.intellij.platform.gradle.tasks.SignPluginTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginSignatureTask

plugins {
  java
  id("org.jetbrains.intellij.platform") version "2.20.1"
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

// 2026.3 runs on JBR 25 and its jars are Java 25 class files, which a JDK 21 javac cannot read.
// Compile with the JDK 27 the repository already requires, for Java 25. Gradle itself still runs
// on JDK 21. No toolchain auto-provisioning: the JDK 27 must be installed.
java { toolchain { languageVersion = JavaLanguageVersion.of(27) } }

tasks.withType<JavaCompile>().configureEach { options.release = 25 }

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
    // The embedded browser for the "Timeline" view (#62). In 2026.3, unlike 2025.3, JCEF is the
    // bundled plugin "Web Browser (JCEF)", com.intellij.modules.jcef, not part of the core
    // platform, so it is declared. plugin.xml depends on it optionally: without it, as without
    // JBCefApp.isSupported(), the tool window shows the task table alone.
    bundledPlugin("com.intellij.modules.jcef")
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
            "changelog at v$analyzerVersion</a>.</p>"
      }
    }

intellijPlatform {
  pluginConfiguration {
    id.set("com.ionutbanu.scopetracer.plugin")
    name.set("Scope Tracer")
    version.set(analyzerVersion)
    changeNotes.set(changeNotesHtml)
    // No untilBuild, on purpose: JetBrains recommends against it (#120).
    ideaVersion { sinceBuild.set(pluginSinceBuild) }
  }
  pluginVerification {
    ides {
      // The lowest IDE in the range, and the newest build from it on, EAP included (what
      // printProductsReleases returns; on 2026-11-24 that was IU-263.8311.30, 2026.3.1 Preview).
      create(IntelliJPlatformType.IntellijIdea, platformVersion)
      select {
        types = listOf(IntelliJPlatformType.IntellijIdea)
        channels = listOf(ProductRelease.Channel.RELEASE, ProductRelease.Channel.EAP)
        sinceBuild = pluginSinceBuild
      }
    }
  }
}

// --- Signing and publishing (#42) ---
//
// The keys and the token come from the Gradle plugin's own environment variables
// (CERTIFICATE_CHAIN, PRIVATE_KEY, PRIVATE_KEY_PASSWORD, PUBLISH_TOKEN), which only release.yml's
// publish-plugin job sets, from repository secrets; CERTIFICATE_CHAIN and PRIVATE_KEY are Base64.
// Left to itself, 2.20.1 ships an unsigned zip in three ways, all closed here:
// - signPlugin skips itself without a chain and key, and verifyPluginSignature then passes as
//   NO-SOURCE: requireSignedZip fails instead, and both later tasks depend on it.
// - publishPlugin takes the signed zip only when signPlugin did work in the same run, and the
//   unsigned one when signPlugin was UP-TO-DATE: its archive is set to the signed zip, always.
// - verifyPluginSignature writes CERTIFICATE_CHAIN verbatim to a .pem file, so a Base64 chain is
//   "No certificate data found": it gets the decoded chain (a certificate, not a secret).
// - A -signed zip left by an earlier build would satisfy all of that when signPlugin skips itself
//   (#220). Two things stop it. deleteSignedZip removes that zip before every signPlugin; its
//   path is spelled out, because taking it from signPlugin would make it depend on the task it
//   runs before, so a drifted name would leave the old zip in place. That is why the second one
//   decides: signPlugin, only when it actually signs, writes a receipt naming the zip it wrote,
//   deleteSignedZip deletes the receipt (a path of our own, which cannot drift), and
//   requireSignedZip fails unless the receipt exists and names signPlugin's real output. A zip
//   that was not signed in this build is never verified or published.
val signPluginTask = tasks.named<SignPluginTask>("signPlugin")
val signedZip = signPluginTask.flatMap { it.signedArchiveFile }
val signReceipt = layout.buildDirectory.file("tmp/signPlugin/signed-in-this-build.txt")

val deleteSignedZip =
    tasks.register<Delete>("deleteSignedZip") {
      description = "Deletes an earlier build's signed plugin zip, and its receipt."
      delete(
          layout.buildDirectory.file(
              "distributions/${rootProject.name}-$analyzerVersion-signed.zip"),
          signReceipt)
    }

signPluginTask.configure {
  dependsOn(deleteSignedZip)
  val receipt = signReceipt
  val written = signedArchiveFile
  doLast {
    val file = receipt.get().asFile
    file.parentFile.mkdirs()
    file.writeText(written.get().asFile.absolutePath)
  }
}

val requireSignedZip =
    tasks.register("requireSignedZip") {
      description = "Fails unless signPlugin signed the plugin zip in this build."
      dependsOn(signPluginTask)
      val zip = signedZip
      val receipt = signReceipt
      doLast {
        val file = zip.get().asFile
        val receiptFile = receipt.get().asFile
        val signedNow = receiptFile.isFile && receiptFile.readText() == file.absolutePath
        if (!signedNow || !file.isFile) {
          throw GradleException(
              "No plugin zip signed in this build at $file: signPlugin needs " +
                  "CERTIFICATE_CHAIN, PRIVATE_KEY and PRIVATE_KEY_PASSWORD (see " +
                  "docs/jetbrains-marketplace-setup.md). An unsigned zip, or one signed by an " +
                  "earlier build, is never verified or published.")
        }
      }
    }

tasks.named<VerifyPluginSignatureTask>("verifyPluginSignature") {
  dependsOn(requireSignedZip)
  inputArchiveFile.set(signedZip)
  certificateChain.set(
      providers.environmentVariable("CERTIFICATE_CHAIN").map { value ->
        if (value.contains("-----BEGIN")) value
        else String(Base64.getMimeDecoder().decode(value.trim()), Charsets.UTF_8)
      })
}

tasks.named<PublishPluginTask>("publishPlugin") {
  dependsOn(requireSignedZip)
  archiveFiles.setFrom(signedZip)
  val uploads = archiveFiles
  doFirst { logger.lifecycle("publishPlugin uploads: ${uploads.files.joinToString { it.name }}") }
}

// No tests live directly in this module for this milestone (see :model's build.gradle.kts and
// the plan doc) — disabled outright rather than left to fail on the IntelliJ Platform Gradle
// plugin's automatic test-task instrumentation, which requires a running IDE sandbox.
tasks.test { enabled = false }

// --- Bundle the scope-tracer-analyzer executable jar as a plugin resource ---
//
// The analyzer is a Maven module compiled with --enable-preview (JDK 27) and can never be
// loaded inside the IDE's own JVM (JBR 25 in 2026.3). The plugin instead ships the analyzer's
// executable jar as a resource and runs it as an external subprocess against a real JDK 27 —
// see AnalyzerProcessRunner. This task copies that jar out of the Maven build's `target/`.
//
// Both bundling tasks are a Sync into their own directory under build/, added as a resource
// directory (#42): a Sync deletes whatever an earlier run left there, so a version bump cannot
// leave the previous version's jar in the zip. They used to Copy into src/main/resources/, which
// kept every version ever built. They are never up to date: Gradle's up-to-date check ignores a
// file it did not write, so a stray jar dropped into the directory would otherwise survive.
val bundledResources = layout.buildDirectory.dir("bundled-resources")

val copyAnalyzerJar =
    tasks.register<Sync>("copyAnalyzerJar") {
      outputs.upToDateWhen { false }
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
      from(analyzerJar) {
        into("analyzer")
        rename { "analyzer.jar" }
      }
      into(bundledResources.map { it.dir("analyzer") })
    }

// --- Bundle the agent fat-jar the same way, for "Run with Scope Tracer" ---
//
// The agent jar's manifest names itself in Boot-Class-Path as
// `scope-tracer-agent-<version>-agent.jar`, resolved next to the jar, so the plugin must extract it
// under exactly that file name. The name is written to a resource beside the jar.
val copyAgentJar =
    tasks.register<Sync>("copyAgentJar") {
      outputs.upToDateWhen { false }
      val agentJarName = "scope-tracer-agent-$analyzerVersion-agent.jar"
      val agentJar = rootDir.resolve("../scope-tracer-agent/target/$agentJarName")
      val outDir = bundledResources.map { it.dir("agent") }
      inputs.property("agentJarName", agentJarName)
      doFirst {
        if (!agentJar.exists()) {
          throw GradleException(
              "Agent jar not found at $agentJar — build it first from the repo root with " +
                  "`mvn -q -DskipTests package`.")
        }
      }
      from(agentJar) { into("agent") }
      into(outDir)
      doLast {
        outDir.get().file("agent/agent-jar-name.txt").asFile.writeText(agentJarName)
      }
    }

sourceSets { main { resources { srcDir(copyAnalyzerJar).srcDir(copyAgentJar) } } }

// A checkout built before #42 still has jars in src/main/resources/{analyzer,agent}/, now
// untracked rather than ignored. They are never bundled: only the Syncs above supply those paths.
tasks.named<ProcessResources>("processResources") {
  val legacyDirs =
      listOf("analyzer", "agent").map {
        layout.projectDirectory.dir("src/main/resources/$it").asFile
      }
  exclude { element -> legacyDirs.any { element.file.startsWith(it) } }
}
