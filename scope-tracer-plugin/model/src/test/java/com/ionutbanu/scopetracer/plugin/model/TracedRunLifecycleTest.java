package com.ionutbanu.scopetracer.plugin.model;

import static com.ionutbanu.scopetracer.plugin.model.TracedRunLifecycle.Resource.AGENT_JAR;
import static com.ionutbanu.scopetracer.plugin.model.TracedRunLifecycle.Resource.RECORDING_DIRECTORY;
import static com.ionutbanu.scopetracer.plugin.model.TracedRunLifecycle.Resource.SUBSCRIPTION;
import static org.assertj.core.api.Assertions.assertThat;

import com.ionutbanu.scopetracer.plugin.model.TracedRunLifecycle.Launch;
import com.ionutbanu.scopetracer.plugin.model.TracedRunLifecycle.Release;
import java.util.List;
import org.junit.jupiter.api.Test;

class TracedRunLifecycleTest {

  private static final Launch TRACED = new Launch("Run", 1, true);
  private static final Launch ORDINARY_RUN = new Launch("Run", 2, true);
  private static final Launch DEBUG = new Launch("Debug", 3, true);
  private static final Launch OTHER_CONFIGURATION = new Launch("Run", 4, false);

  /** The launch IntelliJ reports for a run it handed to a build tool: another profile, Run. */
  private static final Launch DELEGATED = new Launch("Run", 5, false);

  private final TracedRunLifecycle run = new TracedRunLifecycle();

  /** The traced launch as the platform reports it, up to and including the patcher. */
  private void startAndPatch() {
    assertThat(run.launchSeen(TRACED).isNothing()).isTrue();
    assertThat(run.shouldPatch("Run")).isTrue();
    run.agentJarExtracted();
    run.patchApplied();
  }

  @Test
  void patchesExactlyOnce() {
    assertThat(run.shouldPatch("Run")).isTrue();
    assertThat(run.shouldPatch("Run")).isFalse();
    assertThat(run.shouldPatch("Run")).isFalse();
  }

  @Test
  void onlyTheRunExecutorIsPatchedAndADebugLaunchDoesNotConsumeTheRequest() {
    assertThat(run.shouldPatch("Debug")).isFalse();
    assertThat(run.shouldPatch("Run")).isTrue();
  }

  @Test
  void terminationOpensTheRecordingAndReleasesTheRest() {
    startAndPatch();

    var release = run.terminated(TRACED);

    assertThat(release.openRecording()).isTrue();
    assertThat(release.resources()).containsExactly(SUBSCRIPTION, AGENT_JAR);
    assertThat(release.notice()).isNull();
  }

  @Test
  void terminationWithoutAnExtractedAgentJarHasNothingToCleanUp() {
    run.launchSeen(TRACED);
    run.shouldPatch("Run");
    run.patchApplied();

    assertThat(run.terminated(TRACED).resources()).containsExactly(SUBSCRIPTION);
  }

  @Test
  void notStartedBeforeThePatcherReleasesTheSubscriptionAndFileAndOpensNothing() {
    run.launchSeen(TRACED);

    var release = run.notStarted(TRACED);

    assertThat(release.openRecording()).isFalse();
    assertThat(release.resources()).containsExactly(SUBSCRIPTION, RECORDING_DIRECTORY);
  }

  @Test
  void notStartedAfterThePatcherAlsoCleansUpTheAgentJar() {
    startAndPatch();

    var release = run.notStarted(TRACED);

    assertThat(release.openRecording()).isFalse();
    assertThat(release.resources()).containsExactly(SUBSCRIPTION, RECORDING_DIRECTORY, AGENT_JAR);
  }

  @Test
  void notStartedReleasesTheSameWhetherOrNotThePatcherRanWhenNoJarWasExtracted() {
    var before = new TracedRunLifecycle();
    before.launchSeen(TRACED);
    var after = new TracedRunLifecycle();
    after.launchSeen(TRACED);
    after.shouldPatch("Run");

    assertThat(before.notStarted(TRACED).resources())
        .isEqualTo(after.notStarted(TRACED).resources());
  }

  @Test
  void notStartedWithoutAnyEarlierCallbackStillReleases() {
    // processNotStarted may be the only callback the action's subscription ever gets.
    assertThat(run.notStarted(TRACED).resources()).containsExactly(SUBSCRIPTION, RECORDING_DIRECTORY);
  }

  @Test
  void anOrdinaryRunAfterARunThatNeverStartedIsNotPatchedAndOpensNothing() {
    run.launchSeen(TRACED);
    run.notStarted(TRACED);

    assertThat(run.shouldPatch("Run")).isFalse();
    assertThat(run.launchSeen(ORDINARY_RUN).isNothing()).isTrue();
    assertThat(run.terminated(ORDINARY_RUN).isNothing()).isTrue();
  }

  @Test
  void anOrdinaryRunOverlappingThePatchedTracedRunIsNotPatchedAndItsEndChangesNothing() {
    startAndPatch();

    assertThat(run.launchSeen(ORDINARY_RUN).isNothing()).isTrue();
    assertThat(run.shouldPatch("Run")).isFalse();
    assertThat(run.terminated(ORDINARY_RUN).isNothing()).isTrue();
    assertThat(run.notStarted(ORDINARY_RUN).isNothing()).isTrue();

    var release = run.terminated(TRACED);
    assertThat(release.openRecording()).isTrue();
    assertThat(release.resources()).containsExactly(SUBSCRIPTION, AGENT_JAR);
  }

  @Test
  void callbacksForLaunchesThatAreNotTheTracedOneChangeNothing() {
    run.launchSeen(TRACED);

    for (var foreign : new Launch[] {ORDINARY_RUN, DEBUG, OTHER_CONFIGURATION}) {
      assertThat(run.notStarted(foreign).isNothing()).as("notStarted %s", foreign).isTrue();
      assertThat(run.terminated(foreign).isNothing()).as("terminated %s", foreign).isTrue();
    }
    // Still registered: the traced launch is patched and released as usual.
    assertThat(run.shouldPatch("Run")).isTrue();
    assertThat(run.notStarted(TRACED).resources()).containsExactly(SUBSCRIPTION, RECORDING_DIRECTORY);
  }

  @Test
  void aDebugLaunchOfTheConfigurationNeverClaimsOrReleasesAnything() {
    assertThat(run.launchSeen(DEBUG).isNothing()).isTrue();
    assertThat(run.notStarted(DEBUG).isNothing()).isTrue();
    assertThat(run.terminated(DEBUG).isNothing()).isTrue();

    run.launchSeen(TRACED);
    assertThat(run.terminated(DEBUG).isNothing()).isTrue();
    assertThat(run.shouldPatch("Run")).isTrue();
  }

  @Test
  void aSecondRunLaunchBeforeThePatcherHasBeenAskedGivesUpInsteadOfGuessing() {
    run.launchSeen(TRACED);

    var release = run.launchSeen(ORDINARY_RUN);

    assertThat(release.openRecording()).isFalse();
    assertThat(release.resources()).containsExactly(SUBSCRIPTION, RECORDING_DIRECTORY);
    assertThat(release.notice()).isNotBlank();
    assertThat(run.shouldPatch("Run")).isFalse();
    assertThat(run.terminated(TRACED).isNothing()).isTrue();
  }

  @Test
  void aTerminationWithoutAnAppliedPatchOpensNothing() {
    run.launchSeen(TRACED);

    var release = run.terminated(TRACED);

    assertThat(release.openRecording()).isFalse();
    assertThat(release.resources()).containsExactly(SUBSCRIPTION, RECORDING_DIRECTORY);
  }

  @Test
  void refusedPatchingReleasesEverythingAtOnceAndTheRunsTerminationOpensNothing() {
    run.launchSeen(TRACED);
    run.shouldPatch("Run");
    run.agentJarExtracted();

    var release = run.patchRefused();

    assertThat(release.openRecording()).isFalse();
    assertThat(release.resources()).containsExactly(SUBSCRIPTION, RECORDING_DIRECTORY, AGENT_JAR);
    assertThat(run.terminated(TRACED).isNothing()).isTrue();
    assertThat(run.notStarted(TRACED).isNothing()).isTrue();
  }

  @Test
  void failedPatchingBeforeTheJarWasExtractedReleasesOnlyWhatExists() {
    run.shouldPatch("Run");

    assertThat(run.patchRefused().resources()).containsExactly(SUBSCRIPTION, RECORDING_DIRECTORY);
  }

  @Test
  void aReleaseHappensOnlyOnce() {
    startAndPatch();
    assertThat(run.terminated(TRACED).isNothing()).isFalse();

    assertThat(run.terminated(TRACED).isNothing()).isTrue();
    assertThat(run.notStarted(TRACED).isNothing()).isTrue();
    assertThat(run.abandon().isNothing()).isTrue();
    assertThat(run.shouldPatch("Run")).isFalse();
  }

  @Test
  void abandoningARequestReleasesEverythingAndNeverOpensTheRecording() {
    startAndPatch();

    var release = run.abandon();

    assertThat(release.openRecording()).isFalse();
    assertThat(release.resources()).containsExactly(SUBSCRIPTION, RECORDING_DIRECTORY, AGENT_JAR);
    assertThat(run.terminated(TRACED).isNothing()).isTrue();
  }

  @Test
  void abandoningARequestThatNeverGotPatchedReleasesSubscriptionAndFile() {
    assertThat(run.abandon().resources()).containsExactly(SUBSCRIPTION, RECORDING_DIRECTORY);
    assertThat(run.shouldPatch("Run")).isFalse();
  }

  @Test
  void thePatcherMayRunBeforeTheLaunchIsAnnounced() {
    assertThat(run.shouldPatch("Run")).isTrue();
    run.patchApplied();
    assertThat(run.launchSeen(TRACED).isNothing()).isTrue();
    assertThat(run.launchSeen(TRACED).isNothing()).isTrue();

    assertThat(run.terminated(TRACED).openRecording()).isTrue();
  }

  /** The recordings of a run that is opened belong to the opening, never to this release. */
  @Test
  void aTracedRunsTerminationHandsTheWholeRecordingDirectoryToTheOpening() {
    startAndPatch();

    var release = run.terminated(TRACED);

    assertThat(release.openRecording()).isTrue();
    assertThat(release.resources()).doesNotContain(RECORDING_DIRECTORY);
  }

  /** Whatever the run wrote, one file or one per forked JVM, goes with the directory. */
  @Test
  void everyReleaseThatOpensNothingDeletesTheRecordingDirectory() {
    var refused = new TracedRunLifecycle();
    refused.shouldPatch("Run");
    var notStarted = new TracedRunLifecycle();
    var abandoned = new TracedRunLifecycle();
    abandoned.shouldPatch("Run");
    abandoned.patchApplied();
    var untraced = new TracedRunLifecycle();
    untraced.launchSeen(TRACED);

    assertThat(refused.patchRefused().resources()).contains(RECORDING_DIRECTORY);
    assertThat(notStarted.notStarted(TRACED).resources()).contains(RECORDING_DIRECTORY);
    assertThat(abandoned.abandon().resources()).contains(RECORDING_DIRECTORY);
    assertThat(untraced.terminated(TRACED).resources()).contains(RECORDING_DIRECTORY);
  }

  @Test
  void aRunLaunchOfAnotherProfileBeforeTheOwnLaunchIsDelegatedAndReleasesEverythingOnce() {
    var release = run.delegatedLaunchSeen(DELEGATED, DelegatedTool.GRADLE);

    assertThat(release.openRecording()).isFalse();
    assertThat(release.resources()).containsExactly(SUBSCRIPTION, RECORDING_DIRECTORY);
    assertThat(release.notice()).isEqualTo(DelegatedTool.GRADLE.notice());
    // The patcher is never asked about it, and nothing else of the request is left.
    assertThat(run.shouldPatch("Run")).isFalse();
    assertThat(run.delegatedLaunchSeen(DELEGATED, DelegatedTool.GRADLE).isNothing()).isTrue();
    var second = new Launch("Run", 6, false);
    assertThat(run.delegatedLaunchSeen(second, DelegatedTool.MAVEN).isNothing()).isTrue();
    assertThat(run.terminated(DELEGATED).isNothing()).isTrue();
    assertThat(run.notStarted(DELEGATED).isNothing()).isTrue();
    assertThat(run.abandon().isNothing()).isTrue();
  }

  @Test
  void aDelegatedReleaseAlsoCleansUpAnExtractedAgentJar() {
    run.agentJarExtracted();

    assertThat(run.delegatedLaunchSeen(DELEGATED, DelegatedTool.MAVEN).resources())
        .containsExactly(SUBSCRIPTION, RECORDING_DIRECTORY, AGENT_JAR);
  }

  @Test
  void aLaunchThatIsNotARunOfAnotherProfileIsNotDelegated() {
    for (var launch :
        new Launch[] {TRACED, ORDINARY_RUN, DEBUG, new Launch("Debug", 7, false)}) {
      assertThat(run.delegatedLaunchSeen(launch, DelegatedTool.GRADLE).isNothing())
          .as("%s", launch)
          .isTrue();
    }
    // Still armed: the request patches and traces as usual.
    assertThat(run.shouldPatch("Run")).isTrue();
  }

  @Test
  void anotherProfileAfterTheOwnLaunchWasSeenOrPatchedIsNotDelegated() {
    run.launchSeen(TRACED);
    assertThat(run.delegatedLaunchSeen(DELEGATED, DelegatedTool.GRADLE).isNothing()).isTrue();
    run.shouldPatch("Run");
    run.patchApplied();

    assertThat(run.delegatedLaunchSeen(DELEGATED, DelegatedTool.GRADLE).isNothing()).isTrue();
    assertThat(run.terminated(TRACED).openRecording()).isTrue();

    var patchedFirst = new TracedRunLifecycle();
    patchedFirst.shouldPatch("Run");
    assertThat(patchedFirst.delegatedLaunchSeen(DELEGATED, DelegatedTool.GRADLE).isNothing())
        .isTrue();
  }

  @Test
  void aFinishedRequestIgnoresADelegatedLaunch() {
    run.abandon();

    assertThat(run.delegatedLaunchSeen(DELEGATED, DelegatedTool.GRADLE).isNothing()).isTrue();
  }

  @Test
  void theNoticeNamesTheToolAndTheSettingToChange() {
    assertThat(DelegatedTool.GRADLE.notice())
        .contains(
            "Gradle", "not traced", "Build and run using", "Run tests using", "IntelliJ IDEA");
    assertThat(DelegatedTool.MAVEN.notice())
        .contains("Maven", "not traced", "Delegate IDE build/run actions to Maven");
    assertThat(DelegatedTool.OTHER.notice()).contains("not traced");
  }

  @Test
  void theToolIsToldFromTheProfileClassNames() {
    assertThat(
            DelegatedTool.of(
                List.of(
                    "org.jetbrains.idea.maven.execution.MavenApplicationConfigurationExecutionEnvironmentProvider$MyExecRunConfiguration",
                    "com.intellij.execution.configurations.RunConfigurationBase")))
        .isEqualTo(DelegatedTool.MAVEN);
    assertThat(
            DelegatedTool.of(
                List.of(
                    "com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration")))
        .isEqualTo(DelegatedTool.GRADLE);
    assertThat(
            DelegatedTool.of(
                List.of(
                    "org.jetbrains.plugins.gradle.service.execution.GradleRunConfiguration",
                    "com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration")))
        .isEqualTo(DelegatedTool.GRADLE);
    assertThat(DelegatedTool.of(List.of("com.example.Other"))).isEqualTo(DelegatedTool.OTHER);
    assertThat(DelegatedTool.of(List.of())).isEqualTo(DelegatedTool.OTHER);
  }

  @Test
  void aReleaseOfNothingIsRecognised() {
    assertThat(Release.NOTHING.isNothing()).isTrue();
  }
}
