# JFR events emitted by `scope-tracer-core`

All events are emitted by [`TracedScope`](../scope-tracer-core/src/main/java/com/ionutbanu/scopetracer/core/TracedScope.java)
and live in [`com.ionutbanu.scopetracer.core.events`](../scope-tracer-core/src/main/java/com/ionutbanu/scopetracer/core/events/).
They share the JFR category `scope-tracer` and the prefix `com.ionutbanu.scopetracer.*`.

## Common fields

Every event carries:

| Field        | Type   | Notes                                                                          |
|--------------|--------|--------------------------------------------------------------------------------|
| `scopeId`    | long   | Globally unique monotonic ID per scope instance. Primary parser key; eliminates name-based collisions between concurrent scopes that share the same name. |
| `scopeName`  | String | Name supplied to the `TracedScope` constructor (or derived from the call site by the agent). |
| `taskId`     | long   | Per-scope monotonic id starting at 1; `0` on scope-level events.              |
| `threadName` | String | The thread that produced the event (typically a virtual thread).               |

JFR records the timestamp itself via `Event.startTime` — no explicit field.

## Event catalog

| JFR name                          | Class                | Extra fields           | When emitted                                                     |
|-----------------------------------|----------------------|------------------------|------------------------------------------------------------------|
| `com.ionutbanu.scopetracer.ScopeOpened`     | `ScopeOpenedEvent`   | —                      | Once, at scope construction. `taskId = 0`.                      |
| `com.ionutbanu.scopetracer.TaskForked`      | `TaskForkedEvent`    | `String taskName`      | When a subtask is submitted via `fork`. `taskName` is a human-readable label: either the explicit name passed to `TracedScope.fork(String, Callable)`, the `Callable`'s simple class name when not a lambda, or `SimpleClass#method:line` derived from the call site (the agent uses the same derivation). The line-number suffix disambiguates sibling forks in the same method. Nullable; absent in recordings made before this field was added — use `event.hasField("taskName")` before reading. |
| `com.ionutbanu.scopetracer.TaskSucceeded`   | `TaskSucceededEvent` | —                      | When a subtask returns normally.                                 |
| `com.ionutbanu.scopetracer.TaskFailed`      | `TaskFailedEvent`    | `String exceptionType`, `String exceptionMessage`, `String exceptionStackTrace` | When a subtask escapes by throwing. `exceptionType` is the FQN; `exceptionMessage` is `Throwable.getMessage()`, nullable. `exceptionStackTrace` is the formatted stack trace (capped at 4 096 characters); nullable and absent in recordings made before this field was added — use `event.hasField("exceptionStackTrace")` before reading. |
| `com.ionutbanu.scopetracer.TaskCancelled`   | `TaskCancelledEvent` | —                      | When a subtask observes scope shutdown before completing.        |
| `com.ionutbanu.scopetracer.ScopeClosed`     | `ScopeClosedEvent`   | —                      | Once, after all subtasks terminate. `taskId = 0`.               |

## Sealed hierarchy

All event classes implement the sealed marker interface
[`TracedScopeEvent`](../scope-tracer-core/src/main/java/com/ionutbanu/scopetracer/core/events/TracedScopeEvent.java),
so the analyzer can pattern-match exhaustively:

```java
switch (event) {
    case ScopeOpenedEvent e    -> ...;
    case TaskForkedEvent e     -> ...;
    case TaskSucceededEvent e  -> ...;
    case TaskFailedEvent e     -> ...;
    case TaskCancelledEvent e  -> ...;
    case ScopeClosedEvent e    -> ...;
}
```

## High-volume recordings: JFR buffer tuning

JFR writes events to a per-thread buffer (default ~16 KB) which is flushed to a small set
of global buffers and then to disk. When a single thread emits events faster than the
buffer can drain — for example, a tight `fork(...)` loop emitting thousands of
`TaskForkedEvent`s on the caller thread, or many virtual threads completing nearly
simultaneously — the buffer fills and JFR **silently drops events**. Empirically this
starts around ~5 000 events/second per thread on the default settings.

If a recording is missing events that scope-tracer should have emitted, raise the buffer
sizes via `-XX:FlightRecorderOptions` on the JVM that *produces* the recording:

```bash
java -XX:FlightRecorderOptions=threadbuffersize=1M,memorysize=64M,maxchunksize=128M \
     -XX:StartFlightRecording=filename=myapp.jfr,dumponexit=true \
     --enable-preview \
     -cp <classpath> \
     com.example.MyApp
```

These are JVM-level flags and must be set when the JVM starts — they cannot be changed
from the `Recording` API at runtime. The scope-tracer-stress-tests module uses the same
flags; see [scope-tracer-stress-tests/pom.xml](../scope-tracer-stress-tests/pom.xml) for
the canonical values.
