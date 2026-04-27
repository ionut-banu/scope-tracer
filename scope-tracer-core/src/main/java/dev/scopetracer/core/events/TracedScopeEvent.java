package dev.scopetracer.core.events;

/** Marker for all JFR events emitted by {@link dev.scopetracer.core.TracedScope}. */
public sealed interface TracedScopeEvent
    permits ScopeOpenedEvent,
        TaskForkedEvent,
        TaskSucceededEvent,
        TaskFailedEvent,
        TaskCancelledEvent,
        ScopeClosedEvent {}
