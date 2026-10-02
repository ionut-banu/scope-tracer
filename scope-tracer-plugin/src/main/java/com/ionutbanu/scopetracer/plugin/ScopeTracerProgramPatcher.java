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
 * Adds the agent, {@code --enable-preview} and a JFR recording to the JVM arguments of a run that
 * was started by {@link RunWithScopeTracerAction}. Runs started any other way carry no pending
 * entry and are left untouched.
 */
public final class ScopeTracerProgramPatcher extends JavaProgramPatcher {

  @Override
  public void patchJavaParameters(
      Executor executor, RunProfile configuration, JavaParameters javaParameters) {
    var pending = TracedRuns.get(configuration);
    if (pending == null) {
      return;
    }
    try {
      var sdk = javaParameters.getJdk();
      int jdkMajor = TracedLaunch.parseJavaMajor(sdk == null ? null : sdk.getVersionString());
      List<String> existing = javaParameters.getVMParametersList().getList();

      var agentJar = BundledAgent.extract();
      pending.agentJarHolder()[0] = agentJar;

      var plan = TracedLaunch.plan(existing, jdkMajor, agentJar, pending.jfrFile());
      if (plan instanceof TracedLaunch.Plan.Apply apply) {
        for (String arg : apply.extraVmArgs()) {
          javaParameters.getVMParametersList().add(arg);
        }
      } else if (plan instanceof TracedLaunch.Plan.Refuse refuse) {
        // The run still starts, untraced and with its own arguments unchanged.
        ScopeTracerNotifier.notify(
            pending.project(),
            "Scope Tracer: running without tracing",
            refuse.reason(),
            NotificationType.WARNING);
        TracedRuns.remove(configuration);
        BundledAgent.cleanUp(agentJar);
      }
    } catch (IOException | RuntimeException e) {
      ScopeTracerNotifier.notify(
          pending.project(),
          "Scope Tracer: running without tracing",
          String.valueOf(e.getMessage()),
          NotificationType.WARNING);
      TracedRuns.remove(configuration);
    }
  }
}
