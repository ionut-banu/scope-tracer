package com.ionutbanu.scopetracer.analyzer.model;

import java.util.List;

/**
 * The complete trace parsed from a single {@code .jfr} recording. Scopes are sorted by {@link
 * ScopeRecord#openTime()}.
 *
 * @param scopes all {@code TracedScope} lifetimes found in the recording; unmodifiable.
 */
public record TraceModel(List<ScopeRecord> scopes) {}
