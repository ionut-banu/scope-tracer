package com.ionutbanu.scopetracer.plugin;

import com.intellij.execution.Executor;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.runners.JavaProgramPatcher;
import com.intellij.notification.NotificationType;
import com.ionutbanu.scopetracer.plugin.model.TracedLaunch;
import java.io.IOException;
import java.util.List;

/**
 * Adds the agent, {@code --enable-preview} and a JFR recording to the JVM arguments of the one
 * launch that {@link RunWithScopeTracerAction} asked for. Whether a launch is that one is decided
 * by {@link com.ionutbanu.scopetracer.plugin.model.TracedRunLifecycle}; every other launch carries
 * no request, or is told "do not patch", and is left untouched.
 */
public final class ScopeTracerProgramPatcher extends JavaProgramPatcher {

  @Override
  public void patchJavaParameters(
      Executor executor, RunProfile configuration, JavaParameters javaParameters) {
    var active = TracedRuns.get(configuration);
    if (active == null || !active.lifecycle().shouldPatch(executor.getId())) {
      return;
    }
    try {
      var sdk = javaParameters.getJdk();
      int jdkMajor = TracedLaunch.parseJavaMajor(sdk == null ? null : sdk.getVersionString());
      List<String> existing = javaParameters.getVMParametersList().getList();

      var agentJar = BundledAgent.extract();
      active.agentJar(agentJar);
      active.lifecycle().agentJarExtracted();

      var plan = TracedLaunch.plan(existing, jdkMajor, agentJar, active.jfrFile());
      if (plan instanceof TracedLaunch.Plan.Apply apply) {
        for (String arg : apply.extraVmArgs()) {
          javaParameters.getVMParametersList().add(arg);
        }
        active.lifecycle().patchApplied();
      } else if (plan instanceof TracedLaunch.Plan.Refuse refuse) {
        // The run still starts, untraced and with its own arguments unchanged.
        refused(configuration, active, refuse.reason());
      }
    } catch (IOException | RuntimeException e) {
      refused(configuration, active, String.valueOf(e.getMessage()));
    }
  }

  private static void refused(RunProfile configuration, TracedRuns.Active active, String reason) {
    ScopeTracerNotifier.notify(
        active.project(),
        "Scope Tracer: running without tracing",
        reason,
        NotificationType.WARNING);
    TracedRuns.apply(configuration, active, active.lifecycle().patchRefused());
  }
}
