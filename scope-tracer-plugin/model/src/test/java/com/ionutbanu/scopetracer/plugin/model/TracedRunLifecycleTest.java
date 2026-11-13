package com.ionutbanu.scopetracer.plugin.model;

import static com.ionutbanu.scopetracer.plugin.model.TracedRunLifecycle.Resource.AGENT_JAR;
import static com.ionutbanu.scopetracer.plugin.model.TracedRunLifecycle.Resource.JFR_FILE;
import static com.ionutbanu.scopetracer.plugin.model.TracedRunLifecycle.Resource.SUBSCRIPTION;
import static org.assertj.core.api.Assertions.assertThat;

import com.ionutbanu.scopetracer.plugin.model.TracedRunLifecycle.Launch;
import com.ionutbanu.scopetracer.plugin.model.TracedRunLifecycle.Release;
import org.junit.jupiter.api.Test;

class TracedRunLifecycleTest {

  private static final Launch TRACED = new Launch("Run", 1, true);
  private static final Launch ORDINARY_RUN = new Launch("Run", 2, true);
  private static final Launch DEBUG = new Launch("Debug", 3, true);
  private static final Launch OTHER_CONFIGURATION = new Launch("Run", 4, false);

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
    assertThat(release.resources()).containsExactly(SUBSCRIPTION, JFR_FILE);
  }

  @Test
  void notStartedAfterThePatcherAlsoCleansUpTheAgentJar() {
    startAndPatch();

    var release = run.notStarted(TRACED);

    assertThat(release.openRecording()).isFalse();
    assertThat(release.resources()).containsExactly(SUBSCRIPTION, JFR_FILE, AGENT_JAR);
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
    assertThat(run.notStarted(TRACED).resources()).containsExactly(SUBSCRIPTION, JFR_FILE);
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
    assertThat(run.notStarted(TRACED).resources()).containsExactly(SUBSCRIPTION, JFR_FILE);
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
    assertThat(release.resources()).containsExactly(SUBSCRIPTION, JFR_FILE);
    assertThat(release.notice()).isNotBlank();
    assertThat(run.shouldPatch("Run")).isFalse();
    assertThat(run.terminated(TRACED).isNothing()).isTrue();
  }

  @Test
  void aTerminationWithoutAnAppliedPatchOpensNothing() {
    run.launchSeen(TRACED);

    var release = run.terminated(TRACED);

    assertThat(release.openRecording()).isFalse();
    assertThat(release.resources()).containsExactly(SUBSCRIPTION, JFR_FILE);
  }

  @Test
  void refusedPatchingReleasesEverythingAtOnceAndTheRunsTerminationOpensNothing() {
    run.launchSeen(TRACED);
    run.shouldPatch("Run");
    run.agentJarExtracted();

    var release = run.patchRefused();

    assertThat(release.openRecording()).isFalse();
    assertThat(release.resources()).containsExactly(SUBSCRIPTION, JFR_FILE, AGENT_JAR);
    assertThat(run.terminated(TRACED).isNothing()).isTrue();
    assertThat(run.notStarted(TRACED).isNothing()).isTrue();
  }

  @Test
  void failedPatchingBeforeTheJarWasExtractedReleasesOnlyWhatExists() {
    run.shouldPatch("Run");

    assertThat(run.patchRefused().resources()).containsExactly(SUBSCRIPTION, JFR_FILE);
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
    assertThat(release.resources()).containsExactly(SUBSCRIPTION, JFR_FILE, AGENT_JAR);
    assertThat(run.terminated(TRACED).isNothing()).isTrue();
  }

  @Test
  void abandoningARequestThatNeverGotPatchedReleasesSubscriptionAndFile() {
    assertThat(run.abandon().resources()).containsExactly(SUBSCRIPTION, JFR_FILE);
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

  @Test
  void aReleaseOfNothingIsRecognised() {
    assertThat(Release.NOTHING.isNothing()).isTrue();
  }
}
