package com.ionutbanu.scopetracer.plugin.model;

import java.time.Instant;
import java.util.List;

public record PluginScopeRecord(
    long scopeId,
    String name,
    String ownerThreadName,
    long ownerThreadId,
    Instant openTime,
    Instant closeTime,
    PluginParentRef parent,
    List<PluginTaskRecord> tasks) {}
