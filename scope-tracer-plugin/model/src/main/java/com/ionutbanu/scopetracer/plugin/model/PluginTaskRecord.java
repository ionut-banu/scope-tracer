package com.ionutbanu.scopetracer.plugin.model;

import java.time.Instant;

public record PluginTaskRecord(
    long taskId,
    String taskName,
    String threadName,
    long threadId,
    Instant forkTime,
    Instant completionTime,
    PluginTaskOutcome outcome,
    CallSite callSite) {}
