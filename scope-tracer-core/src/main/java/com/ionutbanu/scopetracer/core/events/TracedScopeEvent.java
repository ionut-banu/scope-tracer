package com.ionutbanu.scopetracer.core.events;

/** Marker for all JFR events emitted by {@link com.ionutbanu.scopetracer.core.TracedScope}. */
public sealed interface TracedScopeEvent
    permits ScopeOpenedEvent,
        TaskForkedEvent,
        TaskSucceededEvent,
        TaskFailedEvent,
        TaskCancelledEvent,
        ScopeClosedEvent {}
