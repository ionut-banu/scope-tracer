package com.ionutbanu.scopetracer.plugin.model;

public record PluginParentRef(long parentScopeId, String scopeName, long taskId) {}
