package com.ionutbanu.scopetracer.agent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

/**
 * The scope-tracer events of one recording, read back with {@link RecordingFile} and kept as plain
 * values so the {@code .jfr} file can be deleted. Used by the agent ITs to count events directly,
 * without the analyzer in between.
 *
 * @param events every scope-tracer event in the recording, in file order (which is not time order).
 */
record ScopeEvents(List<Event> events) {

  static final String SCOPE_OPENED = "ScopeOpened";
  static final String SCOPE_CLOSED = "ScopeClosed";
  static final String TASK_FORKED = "TaskForked";

  private static final String PREFIX = "com.ionutbanu.scopetracer.";

  /**
   * One event, by the common fields every scope-tracer event has.
   *
   * @param type the event name without the {@code com.ionutbanu.scopetracer.} prefix.
   */
  record Event(String type, long scopeId, String scopeName, long taskId, String threadName) {}

  static ScopeEvents read(Path jfr) throws IOException {
    return new ScopeEvents(
        RecordingFile.readAllEvents(jfr).stream()
            .filter(e -> e.getEventType().getName().startsWith(PREFIX))
            .map(ScopeEvents::toEvent)
            .toList());
  }

  private static Event toEvent(RecordedEvent e) {
    return new Event(
        e.getEventType().getName().substring(PREFIX.length()),
        e.getLong("scopeId"),
        e.getString("scopeName"),
        e.getLong("taskId"),
        e.getString("threadName"));
  }

  /** Events of {@code type}. */
  List<Event> ofType(String type) {
    return events.stream().filter(e -> e.type().equals(type)).toList();
  }

  /** {@code ScopeOpened} events named {@code scopeName}. */
  List<Event> opened(String scopeName) {
    return ofType(SCOPE_OPENED).stream().filter(e -> scopeName.equals(e.scopeName())).toList();
  }

  /** Events of {@code type} carrying {@code scopeId}. */
  List<Event> ofScope(String type, long scopeId) {
    return ofType(type).stream().filter(e -> e.scopeId() == scopeId).toList();
  }

  /** Every {@code scopeId} any event named {@code scopeName} carries. */
  Set<Long> scopeIdsNamed(String scopeName) {
    return events.stream()
        .filter(e -> scopeName.equals(e.scopeName()))
        .map(Event::scopeId)
        .collect(Collectors.toSet());
  }
}
