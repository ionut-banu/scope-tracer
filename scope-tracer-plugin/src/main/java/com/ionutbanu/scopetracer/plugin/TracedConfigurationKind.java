package com.ionutbanu.scopetracer.plugin;

import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.ionutbanu.scopetracer.plugin.model.RunActionState;
import org.jetbrains.annotations.Nullable;

/**
 * A kind of run configuration that "Run with Scope Tracer" runs, besides Java Application, which
 * the plugin knows itself. A kind whose classes live in an optional plugin registers one in that
 * plugin's optional config file, so the plugin still loads without it: JUnit ({@code
 * scope-tracer-junit.xml}, #64).
 */
public interface TracedConfigurationKind {

  ExtensionPointName<TracedConfigurationKind> EP_NAME =
      ExtensionPointName.create("com.ionutbanu.scopetracer.plugin.tracedConfigurationKind");

  /** The selection {@code configuration} is, or {@code null} when it is not of this kind. */
  RunActionState.@Nullable Selection selectionOf(RunConfiguration configuration);
}
