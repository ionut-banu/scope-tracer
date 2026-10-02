package com.ionutbanu.scopetracer.plugin;

import com.intellij.execution.configurations.RunProfile;
import com.intellij.openapi.project.Project;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hands a "Run with Scope Tracer" request from the action to {@link ScopeTracerProgramPatcher},
 * which only sees the run profile. An entry exists from the moment the action starts the run until
 * the process terminates.
 */
final class TracedRuns {

  /** Everything the patcher and the termination handler need for one run. */
  record Pending(Project project, Path jfrFile, Path analyzerJava, Path[] agentJarHolder) {

    Path agentJar() {
      return agentJarHolder[0];
    }
  }

  private static final Map<RunProfile, Pending> PENDING = new ConcurrentHashMap<>();

  private TracedRuns() {}

  static void register(RunProfile profile, Pending pending) {
    PENDING.put(profile, pending);
  }

  static Pending get(RunProfile profile) {
    return PENDING.get(profile);
  }

  static Pending remove(RunProfile profile) {
    return PENDING.remove(profile);
  }
}
