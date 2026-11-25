package com.ionutbanu.scopetracer.plugin.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.ionutbanu.scopetracer.plugin.model.AnalyzerJdkChooser.Candidate;
import com.ionutbanu.scopetracer.plugin.model.AnalyzerJdkChooser.Choice;
import com.ionutbanu.scopetracer.plugin.model.AnalyzerJdkChooser.Inputs;
import com.ionutbanu.scopetracer.plugin.model.AnalyzerJdkChooser.Skipped;
import com.ionutbanu.scopetracer.plugin.model.AnalyzerJdkChooser.Source;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnalyzerJdkChooserTest {

  @TempDir Path temp;

  private static final String V26 = "openjdk version \"26\" 2026-03-17";
  private static final String V27 = "openjdk version \"27.0.1\" 2026-10-21";
  private static final String V28_EA = "openjdk version \"28-ea\" 2027-03-16";
  private static final String V17 = "openjdk version \"17.0.17\" 2025-10-21";

  /** A probe faked from java path to {@code java -version} output, recording every call. */
  private final Map<Path, String> outputs = new HashMap<>();

  private final List<Path> probed = new ArrayList<>();

  private final Function<Path, String> probe =
      java -> {
        probed.add(java);
        return outputs.getOrDefault(java, "");
      };

  /** A JDK home on disk with an executable {@code bin/java}, which the fake probe answers. */
  private Candidate jdk(Source source, String name, String stored, String probeSays)
      throws IOException {
    var home = Files.createDirectories(temp.resolve(name));
    var java = Files.createDirectories(home.resolve("bin")).resolve("java");
    Files.writeString(java, "#!/bin/sh\n");
    Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwxr-xr-x"));
    outputs.put(java, probeSays);
    return new Candidate(source, name, home, java, stored);
  }

  private Candidate registered(String name, String stored, String probeSays) throws IOException {
    return jdk(Source.REGISTERED, name, stored, probeSays);
  }

  private Choice choose(
      Candidate explicit, Candidate tracedRun, Candidate projectSdk, Candidate... registered) {
    return AnalyzerJdkChooser.choose(
        new Inputs(
            Optional.ofNullable(explicit),
            Optional.ofNullable(tracedRun),
            Optional.ofNullable(projectSdk),
            List.of(registered)),
        probe);
  }

  private static String chosenName(Choice choice) {
    return choice.chosen().orElseThrow().candidate().name();
  }

  @Test
  void anExplicitChoiceWinsOverTheTracedRunTheProjectSdkAndTheRegisteredJdks() throws IOException {
    var choice =
        choose(
            jdk(Source.EXPLICIT, "explicit-27", null, V27),
            jdk(Source.TRACED_RUN, "run-28", "28", V28_EA),
            jdk(Source.PROJECT_SDK, "project-27", "27", V27),
            registered("temurin-28", "28", V28_EA));

    assertThat(chosenName(choice)).isEqualTo("explicit-27");
    assertThat(choice.skipped()).isEmpty();
    assertThat(choice.explicitWarning()).isEmpty();
  }

  @Test
  void withNoExplicitChoiceTheTracedRunsOwnJdkComesBeforeTheProjectSdkAndTheRegisteredJdks()
      throws IOException {
    var choice =
        choose(
            null,
            jdk(Source.TRACED_RUN, "run-27", "27", V27),
            jdk(Source.PROJECT_SDK, "project-28", "28", V28_EA),
            registered("temurin-28", "28", V28_EA));

    assertThat(chosenName(choice)).isEqualTo("run-27");
  }

  @Test
  void withNoTracedRunTheProjectSdkComesBeforeTheRegisteredJdks() throws IOException {
    var choice =
        choose(
            null,
            null,
            jdk(Source.PROJECT_SDK, "project-27", "27", V27),
            registered("temurin-28", "28", V28_EA));

    assertThat(chosenName(choice)).isEqualTo("project-27");
  }

  @Test
  void aProjectSdkOlderThan27IsSkippedForARegistered27() throws IOException {
    var choice =
        choose(
            null,
            null,
            jdk(Source.PROJECT_SDK, "temurin-26", "26", V26),
            registered("temurin-26-again", "26", V26),
            registered("temurin-27", "27.0.1", V27));

    assertThat(chosenName(choice)).isEqualTo("temurin-27");
    assertThat(choice.skipped()).extracting(Skipped::line).first().isEqualTo("temurin-26: JDK 26");
  }

  @Test
  void theRegisteredJdkWithTheHighestMajorVersionIsChosen() throws IOException {
    var choice =
        choose(
            null,
            null,
            null,
            registered("temurin-27", "Eclipse Temurin 27.0.1 - aarch64", V27),
            registered("temurin-28", "Eclipse Temurin 28-ea - aarch64", V28_EA),
            registered("temurin-26", "Eclipse Temurin 26 - aarch64", V26));

    assertThat(chosenName(choice)).isEqualTo("temurin-28");
    assertThat(probed).hasSize(1);
  }

  @Test
  void registeredJdksOfTheSameMajorAreOrderedByTheNewestFullVersion() throws IOException {
    var choice =
        choose(
            null,
            null,
            null,
            registered("a-27.0.1", "Eclipse Temurin 27.0.1 - aarch64", V27),
            registered("b-27.0.2", "Eclipse Temurin 27.0.2 - aarch64", V27),
            registered("c-27", "Eclipse Temurin 27 - aarch64", V27));

    assertThat(chosenName(choice)).isEqualTo("b-27.0.2");
  }

  @Test
  void registeredJdksOfTheSameVersionAreOrderedByName() throws IOException {
    var choice =
        choose(
            null,
            null,
            null,
            registered("zulu-27", "27.0.1", V27),
            registered("corretto-27", "27.0.1", V27),
            registered("temurin-27", "27.0.1", V27));

    assertThat(chosenName(choice)).isEqualTo("corretto-27");
  }

  @Test
  void aCandidateWhoseHomeDoesNotExistIsSkippedWithoutBeingProbed() throws IOException {
    var gone =
        new Candidate(
            Source.REGISTERED,
            "azul-17",
            temp.resolve("azul-17"),
            temp.resolve("azul-17/bin/java"),
            "28");
    var choice = choose(null, null, null, gone, registered("temurin-27", "27", V27));

    assertThat(chosenName(choice)).isEqualTo("temurin-27");
    assertThat(choice.skipped()).extracting(Skipped::line).containsExactly("azul-17: home not found");
    assertThat(probed).doesNotContain(gone.java());
  }

  @Test
  void aCandidateWhoseJavaIsNotExecutableIsSkipped() throws IOException {
    var broken = registered("broken-28", "28", V28_EA);
    Files.setPosixFilePermissions(broken.java(), PosixFilePermissions.fromString("rw-r--r--"));
    var noLauncher = new Candidate(Source.REGISTERED, "no-launcher", temp, null, "28");

    var choice = choose(null, null, null, broken, noLauncher, registered("temurin-27", "27", V27));

    assertThat(chosenName(choice)).isEqualTo("temurin-27");
    assertThat(choice.skipped())
        .extracting(Skipped::line)
        .containsExactlyInAnyOrder("broken-28: no executable java", "no-launcher: no executable java");
  }

  @Test
  void aCandidateWhoseVersionCannotBeReadIsSkipped() throws IOException {
    var choice =
        choose(
            null,
            null,
            null,
            registered("garbled-28", "28", "Error: could not open `lib/jvm.cfg'"),
            registered("temurin-27", "27", V27));

    assertThat(chosenName(choice)).isEqualTo("temurin-27");
    assertThat(choice.skipped())
        .extracting(Skipped::line)
        .containsExactly("garbled-28: could not read its java -version");
  }

  @Test
  void theProbesVersionCountsNotTheOneIntellijStored() throws IOException {
    var replacedInPlace = registered("temurin-27", "Eclipse Temurin 27.0.1 - aarch64", V26);
    var older = registered("temurin-27-old", "Eclipse Temurin 27 - aarch64", V27);

    var choice = choose(null, null, null, replacedInPlace, older);

    assertThat(chosenName(choice)).isEqualTo("temurin-27-old");
    assertThat(choice.skipped()).extracting(Skipped::line).containsExactly("temurin-27: JDK 26");
  }

  @Test
  void jdk28EarlyAccessIsAccepted() throws IOException {
    var choice = choose(null, null, null, registered("temurin-28-ea", "28-ea", V28_EA));

    assertThat(choice.chosen()).hasValueSatisfying(c -> assertThat(c.major()).isEqualTo(28));
  }

  @Test
  void anUnusableExplicitChoiceFallsBackToTheAutomaticOrderWithAWarningNamingItAndWhy()
      throws IOException {
    var explicit = jdk(Source.EXPLICIT, "my-jdk-26", null, V26);

    var choice = choose(explicit, null, null, registered("temurin-27", "27", V27));

    assertThat(chosenName(choice)).isEqualTo("temurin-27");
    assertThat(choice.explicitSkipped()).hasValueSatisfying(s -> assertThat(s.reason()).isEqualTo("JDK 26"));
    assertThat(choice.explicitWarning())
        .hasValueSatisfying(
            w ->
                assertThat(w)
                    .contains("my-jdk-26")
                    .contains("JDK 26")
                    .contains("Settings → Tools → Scope Tracer")
                    .contains("temurin-27"));
  }

  @Test
  void whenNothingIsUsableTheFailureNamesJdk27AndListsEachCandidateWithItsReason()
      throws IOException {
    var gone =
        new Candidate(Source.REGISTERED, "azul-17", temp.resolve("gone"), temp.resolve("gone/bin/java"), "17");
    var choice =
        choose(
            null, null, null, registered("temurin-26", "26", V26), gone, registered("zulu-17", "17", V17));

    assertThat(choice.chosen()).isEmpty();
    assertThat(choice.explicitWarning()).isEmpty();
    assertThat(choice.failureLines())
        .containsExactly(
            "The scope-tracer analyzer needs JDK 27 or newer, and none was found. Tried:",
            "temurin-26: JDK 26",
            "azul-17: home not found",
            "zulu-17: JDK 17");
  }

  @Test
  void withNoCandidateAtAllTheFailureSaysNoJdkIsRegistered() {
    var choice = choose(null, null, null);

    assertThat(choice.chosen()).isEmpty();
    assertThat(choice.failureLines())
        .containsExactly(
            "The scope-tracer analyzer needs JDK 27 or newer, and none was found (no JDK is registered in the IDE).");
  }

  @Test
  void candidatesAreProbedLazilyInOrderAndNothingAfterTheFirstUsableOneIsProbed()
      throws IOException {
    var explicit = jdk(Source.EXPLICIT, "explicit-26", null, V26);
    var run = jdk(Source.TRACED_RUN, "run-garbled", "27", "nonsense");
    var project = jdk(Source.PROJECT_SDK, "project-27", "27", V27);
    var later1 = registered("temurin-28", "28", V28_EA);
    var later2 = registered("temurin-27", "27", V27);

    var choice = choose(explicit, run, project, later1, later2);

    assertThat(chosenName(choice)).isEqualTo("project-27");
    assertThat(probed).containsExactly(explicit.java(), run.java(), project.java());
  }

  @Test
  void aJavaAlreadyTriedUnderAnotherNameIsNotProbedTwice() throws IOException {
    var project = jdk(Source.PROJECT_SDK, "jdk-26", "26", V26);
    var sameJava = new Candidate(Source.REGISTERED, "jdk-26 (registered)", project.home(), project.java(), "26");

    // The duplicate is the only registered JDK, so it is the next one tried after the project SDK.
    var choice = choose(null, null, project, sameJava);

    assertThat(choice.chosen()).isEmpty();
    assertThat(probed).containsExactly(project.java());
    assertThat(choice.skipped()).extracting(Skipped::line).containsExactly("jdk-26: JDK 26");
  }

  @Test
  void aRegisteredJdkWhosePathCannotBeReadIsSkippedWithAReasonAndTheNextOneIsTried()
      throws IOException {
    // A NUL character is an invalid path on every platform.
    var bad = Candidate.of(Source.REGISTERED, "broken-jdk", "/jdks/a\u0000b", "/jdks/a\u0000b/bin/java", "28");
    var good = registered("temurin-27", "27", V27);
    var fromStrings =
        Candidate.of(Source.REGISTERED, "temurin-27", good.home().toString(), good.java().toString(), "27");

    var choice = choose(null, null, null, bad, fromStrings);

    assertThat(fromStrings).isEqualTo(good);
    assertThat(chosenName(choice)).isEqualTo("temurin-27");
    assertThat(choice.skipped()).singleElement()
        .satisfies(s -> assertThat(s.reason()).startsWith("invalid path"));
    assertThat(probed).containsExactly(good.java());
  }

  @Test
  void aJavaThatNeverAnswersIsSkippedAfterTheBoundedProbesTimeout() throws IOException {
    // A "java" that never exits, then the test JVM's own java (a JDK 27+ under this build).
    var hangs = temp.resolve("hangs/bin/java");
    Files.createDirectories(hangs.getParent());
    Files.writeString(
        hangs,
        "#!/bin/sh\nexec '" + Children.JAVA + "' '" + Children.source("NeverExits") + "'\n");
    Files.setPosixFilePermissions(hangs, PosixFilePermissions.fromString("rwxr-xr-x"));
    var hanging = new Candidate(Source.EXPLICIT, "hangs", hangs.getParent().getParent(), hangs, null);
    var real = realJdk(Source.REGISTERED);

    var choice =
        assertTimeoutPreemptively(
            Duration.ofSeconds(30),
            () ->
                AnalyzerJdkChooser.choose(
                    new Inputs(Optional.of(hanging), Optional.empty(), Optional.empty(), List.of(real)),
                    AnalyzerJdk.probe(temp, Duration.ofSeconds(1))));

    assertThat(choice.explicitSkipped())
        .hasValueSatisfying(s -> assertThat(s.reason()).isEqualTo("could not read its java -version"));
    assertThat(chosenName(choice)).isEqualTo("this JVM");
    assertThat(Children.aliveChildren()).isEmpty();
  }

  @Test
  void theTestJvmsOwnJavaIsChosenWithTheRealProbe() {
    var choice =
        assertTimeoutPreemptively(
            Duration.ofSeconds(30),
            () ->
                AnalyzerJdkChooser.choose(
                    new Inputs(
                        Optional.empty(), Optional.empty(), Optional.empty(), List.of(realJdk(Source.REGISTERED))),
                    AnalyzerJdk.probe(temp)));

    assertThat(choice.chosen())
        .hasValueSatisfying(c -> assertThat(c.major()).isEqualTo(Runtime.version().feature()));
  }

  private static Candidate realJdk(Source source) {
    var home = Path.of(System.getProperty("java.home"));
    return new Candidate(source, "this JVM", home, Children.JAVA, System.getProperty("java.version"));
  }

  @Test
  void theSettingsPageRefusesAJavaOlderThan27OrAPathWithNoJavaNamingJdk27() throws IOException {
    var jdk26 = registered("temurin-26", "26", V26);
    var jdk27 = registered("temurin-27", "27", V27);

    assertThat(AnalyzerJdkChooser.refusalOfExplicit(jdk26.java(), probe))
        .hasValueSatisfying(r -> assertThat(r).contains("JDK 26").contains("JDK 27 or newer"));
    assertThat(AnalyzerJdkChooser.refusalOfExplicit(temp.resolve("nothing/bin/java"), probe))
        .hasValueSatisfying(r -> assertThat(r).contains("No java launcher").contains("JDK 27 or newer"));
    assertThat(AnalyzerJdkChooser.refusalOfExplicit(jdk27.java(), probe)).isEmpty();
  }

  @Test
  void aJdkHomeChosenFromDiskMeansItsBinJavaAndAJavaFileMeansItself() throws IOException {
    var jdk = registered("temurin-27", "27", V27);

    assertThat(AnalyzerJdkChooser.launcherFor(jdk.home(), false)).isEqualTo(jdk.java());
    assertThat(AnalyzerJdkChooser.launcherFor(jdk.java(), false)).isEqualTo(jdk.java());
    assertThat(AnalyzerJdkChooser.launcherFor(jdk.home(), true))
        .isEqualTo(jdk.home().resolve("bin").resolve("java.exe"));
  }
}
