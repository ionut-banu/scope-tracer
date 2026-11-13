package com.ionutbanu.scopetracer.plugin.model;

import java.util.ArrayList;
import java.util.List;

/**
 * The state of one "Run with Scope Tracer" request, from the click until everything it set up is
 * let go. It answers two questions for the IntelliJ glue: does this launch get patched, and what
 * is released (and is the recording opened) when a lifecycle callback arrives. Pure logic with no
 * IntelliJ dependency so it runs as ordinary JUnit.
 *
 * <p>The platform hands the program patcher only the executor and the run configuration, not the
 * {@code ExecutionEnvironment}, so at patch time a launch cannot be told apart from another launch
 * of the same configuration. The rules therefore do not depend on the order of {@code
 * processStartScheduled}, {@code processStarting}, the patcher, {@code processNotStarted}, {@code
 * processStarted} and {@code processTerminated}:
 *
 * <ul>
 *   <li>Only the Run executor is ever patched, and only once ({@link #shouldPatch}).
 *   <li>The first Run-executor launch of the configuration that any callback mentions is claimed as
 *       the traced one, by its execution id. Callbacks for any other execution id, any other
 *       executor or any other configuration change nothing.
 *   <li>If a second Run launch of the configuration shows up before the patcher has been asked,
 *       the patcher could be called for either of them, so the request gives up: it releases
 *       everything and nothing is patched (when in doubt, do not patch).
 *   <li>Every release is returned once; after it every method answers "nothing to do".
 * </ul>
 *
 * <p>Methods are synchronized: the platform delivers callbacks from several threads.
 */
public final class TracedRunLifecycle {

  /** The id of the platform's Run executor ({@code DefaultRunExecutor.EXECUTOR_ID}). */
  public static final String RUN_EXECUTOR_ID = "Run";

  /** Something the action set up for the run and must let go of. */
  public enum Resource {
    /** The message-bus connection listening for the run's lifecycle. */
    SUBSCRIPTION,
    /** The temporary {@code .jfr} file the run was told to write. */
    JFR_FILE,
    /** The agent jar extracted for the run. Only present if it was extracted. */
    AGENT_JAR
  }

  /**
   * What the glue must do now.
   *
   * @param resources what to release. {@link Resource#JFR_FILE} is absent when {@code
   *     openRecording} is true, because the recording takes over the file.
   * @param openRecording whether to open the recording in the tool window (or warn that the run
   *     wrote none).
   * @param notice a warning to show the user, or {@code null}.
   */
  public record Release(List<Resource> resources, boolean openRecording, String notice) {
    /** The answer to a callback that does not concern this request. */
    public static final Release NOTHING = new Release(List.of(), false, null);

    public boolean isNothing() {
      return resources.isEmpty() && !openRecording && notice == null;
    }
  }

  /**
   * Where one platform callback came from.
   *
   * @param executorId the callback's executor id.
   * @param executionId the {@code ExecutionEnvironment}'s execution id.
   * @param sameConfiguration whether the environment's run profile is the one this request is for.
   */
  public record Launch(String executorId, long executionId, boolean sameConfiguration) {}

  private boolean finished;
  private boolean patchHandedOut;
  private boolean patchApplied;
  private boolean agentJarExtracted;
  private Long claimedExecutionId;

  /**
   * Whether the patcher should patch this launch now. {@code true} at most once, and only for the
   * Run executor; any other call is "do not patch" and does not consume the request.
   */
  public synchronized boolean shouldPatch(String executorId) {
    if (finished || patchHandedOut || !RUN_EXECUTOR_ID.equals(executorId)) {
      return false;
    }
    patchHandedOut = true;
    return true;
  }

  /** The glue extracted the agent jar; it must be cleaned up on release. */
  public synchronized void agentJarExtracted() {
    agentJarExtracted = true;
  }

  /** Patching succeeded: the run is traced and its recording is opened when it ends. */
  public synchronized void patchApplied() {
    patchApplied = true;
  }

  /**
   * Patching was refused or failed. The run starts untraced, so everything is released now and the
   * run's termination opens nothing.
   */
  public synchronized Release patchRefused() {
    return finish(false, null);
  }

  /** {@code processStartScheduled}, {@code processStarting} or {@code processStarted}. */
  public synchronized Release launchSeen(Launch launch) {
    if (finished || !isRunOfConfiguration(launch)) {
      return Release.NOTHING;
    }
    if (claimedExecutionId == null) {
      claimedExecutionId = launch.executionId();
      return Release.NOTHING;
    }
    if (claimedExecutionId == launch.executionId() || patchHandedOut) {
      return Release.NOTHING;
    }
    return finish(
        false,
        "The configuration was started again before the traced run had been launched, so it"
            + " runs without tracing.");
  }

  /** {@code processNotStarted}: the launch failed or was cancelled before a process existed. */
  public synchronized Release notStarted(Launch launch) {
    if (finished || !isTheTracedLaunch(launch)) {
      return Release.NOTHING;
    }
    return finish(false, null);
  }

  /** {@code processTerminated}: the process ended. */
  public synchronized Release terminated(Launch launch) {
    if (finished || !isTheTracedLaunch(launch)) {
      return Release.NOTHING;
    }
    return finish(patchApplied, null);
  }

  /**
   * A new click for the same configuration replaces this request: everything is released and the
   * recording is not opened, even if a process is still running.
   */
  public synchronized Release abandon() {
    return finish(false, null);
  }

  private boolean isRunOfConfiguration(Launch launch) {
    return launch.sameConfiguration() && RUN_EXECUTOR_ID.equals(launch.executorId());
  }

  private boolean isTheTracedLaunch(Launch launch) {
    if (!isRunOfConfiguration(launch)) {
      return false;
    }
    return claimedExecutionId == null || claimedExecutionId == launch.executionId();
  }

  private Release finish(boolean openRecording, String notice) {
    if (finished) {
      return Release.NOTHING;
    }
    finished = true;
    var resources = new ArrayList<Resource>();
    resources.add(Resource.SUBSCRIPTION);
    if (!openRecording) {
      resources.add(Resource.JFR_FILE);
    }
    if (agentJarExtracted) {
      resources.add(Resource.AGENT_JAR);
    }
    return new Release(List.copyOf(resources), openRecording, notice);
  }
}
