// Plain Java module: the JSON wire-format model and parser, with zero IntelliJ Platform
// dependency. Kept separate from the root plugin module so its tests run as ordinary JUnit —
// the org.jetbrains.intellij.platform Gradle plugin instruments the `test` task of any module
// it's applied to (custom system classloader, sandbox classpath) even without explicitly
// requesting an IDE test framework, which breaks plain unit tests. See root build.gradle.kts.
plugins { `java-library` }

val gsonVersion = providers.gradleProperty("gsonVersion").get()

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

repositories { mavenCentral() }

dependencies {
  // Gson ships inside the IntelliJ Platform itself; compileOnly avoids bundling a second copy
  // into the plugin jar (which would risk classloader conflicts with the IDE's own copy).
  compileOnly("com.google.code.gson:gson:$gsonVersion")
  testImplementation("com.google.code.gson:gson:$gsonVersion")
  testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
  testImplementation("org.assertj:assertj-core:3.27.7")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }
