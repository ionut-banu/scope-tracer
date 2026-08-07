package com.ionutbanu.scopetracer.plugin.model;

/** Mirrors {@code com.ionutbanu.scopetracer.analyzer.model.TaskOutcome}'s JSON shape. */
public sealed interface PluginTaskOutcome
    permits PluginTaskOutcome.Success, PluginTaskOutcome.Failed, PluginTaskOutcome.Cancelled {

  record Success() implements PluginTaskOutcome {}

  record Failed(String exceptionType, String exceptionMessage, String stackTrace)
      implements PluginTaskOutcome {}

  record Cancelled() implements PluginTaskOutcome {}
}
