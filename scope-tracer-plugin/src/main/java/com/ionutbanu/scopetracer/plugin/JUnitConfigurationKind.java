package com.ionutbanu.scopetracer.plugin;

import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.junit.JUnitConfiguration;
import com.ionutbanu.scopetracer.plugin.model.RunActionState;
import org.jetbrains.annotations.Nullable;

/**
 * JUnit run configurations count as runnable (#64). Registered only in {@code
 * scope-tracer-junit.xml}, the optional config file loaded when the bundled JUnit plugin is
 * present, so this class, the one that names {@link JUnitConfiguration}, is never loaded without
 * it.
 */
public final class JUnitConfigurationKind implements TracedConfigurationKind {

  @Override
  public RunActionState.@Nullable Selection selectionOf(RunConfiguration configuration) {
    return configuration instanceof JUnitConfiguration ? new RunActionState.Selection.JUnit() : null;
  }
}
