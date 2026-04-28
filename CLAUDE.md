# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

A library and analyzer for visualizing Java structured concurrency
(`java.util.concurrent.StructuredTaskScope`) task trees, lifetimes,
and cancellation propagation. Targets Java engineers adopting virtual
threads on JDK 26+.

## Modules

- `scope-tracer-core` — wraps StructuredTaskScope, emits JFR events
- `scope-tracer-analyzer` — reads .jfr files, renders HTML/SVG reports
- `scope-tracer-demos` — example programs (correct + buggy)

Code lives under `dev.scopetracer.{core,analyzer,demos}`. Maven coordinates:
`dev.scopetracer:scope-tracer-*:0.1.0-SNAPSHOT`.

## Architecture

End-to-end pipeline: user code wrapped in `TracedScope` → run under JFR recording →
`.jfr` file fed to `scope-tracer-analyzer` → HTML/SVG report.

**`scope-tracer-core`**

`TracedScope` (`dev.scopetracer.core`) wraps `StructuredTaskScope` using
`Joiner.awaitAllSuccessfulOrThrow()` (fail-fast on first subtask failure). It emits six
JFR events at every lifecycle moment:

| Event | When | `taskId` |
|---|---|---|
| `ScopeOpenedEvent` | constructor | 0 |
| `TaskForkedEvent` | `fork()` call, on caller thread | ≥1 |
| `TaskSucceededEvent` | task returns normally | same as fork |
| `TaskFailedEvent` | task throws; carries `exceptionType` FQN | same as fork |
| `TaskCancelledEvent` | task observes scope shutdown (`InterruptedException`) | same as fork |
| `ScopeClosedEvent` | `close()`, after all task threads finish | 0 |

All six event classes extend `jdk.jfr.Event` and implement the `TracedScopeEvent` sealed
interface (`dev.scopetracer.core.events`). This lets the analyzer exhaustively
pattern-match over event types with a `switch` without a JFR consumer dependency in core.

**JFR event fields** (on every event): `scopeName`, `taskId` (long), `threadName`.
`TaskFailedEvent` adds `exceptionType`.

**JFR testing pattern**

The only way to assert emitted JFR events in tests is:

```java
try (var recording = new Recording()) {
    recording.enable("dev.scopetracer.*");
    recording.start();
    // ... run TracedScope ...
    recording.stop();
    recording.dump(tempPath);
}
// read back:
try (var file = new RecordingFile(path)) {
    while (file.hasMoreEvents()) events.add(file.readEvent());
}
```

Filter events by `scopeName` to isolate test cases. **Cross-thread flush ordering is not
guaranteed**: `TaskSucceeded` (emitted on the task thread) may appear after `ScopeClosed`
(emitted on the caller thread) in the dump even though it logically precedes it. Use
`containsExactlyInAnyOrder` for presence checks; use `.getStartTime()` comparisons for
ordering assertions within the same thread.

**`scope-tracer-analyzer`**

`JfrParser` (`dev.scopetracer.analyzer`) reads a `.jfr` file and builds a `TraceModel`:

- `TraceModel` — root container; holds `List<ScopeRecord>` sorted by open time.
- `ScopeRecord` — one `TracedScope` lifetime: `name`, `ownerThreadName`, `openTime`,
  `closeTime`, `List<TaskRecord> tasks`, and `ParentRef parent` (null for root scopes).
- `TaskRecord` — one forked subtask: `taskId`, `threadName`, `forkTime`,
  `completionTime`, `TaskOutcome outcome`.
- `TaskOutcome` — sealed interface with three implementations: `Success`, `Failed(String
  exceptionType)`, `Cancelled`.

**Nesting detection** (`JfrParser.detectNesting`): scope B is a child of task T in scope
A when (1) `ScopeOpenedEvent` for B and the completion event for T share the same JFR
thread ID (`event.getThread().getJavaThreadId()`), and (2) B's `[openTime, closeTime]` is
contained within T's `[forkTime, completionTime]`. This uses JFR's stable thread ID, not
the thread name (which is empty for unnamed virtual threads). The result is stored as
`ScopeRecord.ParentRef(String scopeName, long taskId)`.

`HtmlRenderer.render(TraceModel)` produces a self-contained HTML string with inline CSS
and SVG timelines. Root scopes are rendered first; child scopes are indented inside a
`<div class="child-scope">` beneath their parent. Each task bar is a nested `<svg>` (for
natural overflow clipping) containing a coloured `<rect>` and a white `#N` label.

## Running demos

`exec:java` shares Maven's JVM and cannot receive `--enable-preview`, so demos must be
run with an explicit `java` invocation:

```bash
mvn -q package -DskipTests   # build all jars first
CP=$(mvn -pl scope-tracer-demos -q dependency:build-classpath -DforceStdout)
JARS="scope-tracer-core/target/scope-tracer-core-0.1.0-SNAPSHOT.jar:\
scope-tracer-analyzer/target/scope-tracer-analyzer-0.1.0-SNAPSHOT.jar:\
scope-tracer-demos/target/scope-tracer-demos-0.1.0-SNAPSHOT.jar:$CP"
java --enable-preview -cp "$JARS" dev.scopetracer.demos.ParallelFetchDemo
java --enable-preview -cp "$JARS" dev.scopetracer.demos.FailFastDemo
java --enable-preview -cp "$JARS" dev.scopetracer.demos.NestedScopesDemo
```

Each demo writes `<name>.jfr` and `<name>.html` to `target/` relative to the working
directory, then prints the absolute paths.

## Build & test

- `mvn -q verify` — full build, tests, and Spotless style check
- `mvn -pl scope-tracer-core -q verify` — single module
- `mvn -pl scope-tracer-core -Dtest=ClassName#method test` — single test
- `mvn spotless:apply` — auto-fix formatting (google-java-format + sortPom)
- `mvn -pl scope-tracer-core spotless:apply` — format a single module without touching other poms
- Java 26+, Maven 3.9+ (enforced by maven-enforcer-plugin). `--enable-preview` is intentionally enabled project-wide — `StructuredTaskScope` is a preview API. Do not disable it.

**Test locations:** `scope-tracer-{module}/src/test/java/dev/scopetracer/{module}/`. Core tests use the JFR recording pattern above. Analyzer tests split into `JfrParserTest` (integration, requires a live JFR recording) and `HtmlRendererTest` (unit, constructs model objects directly).

**Known pre-existing failures:** `JfrParserTest` tests fail with `UnsupportedOperationException` at runtime because `StructuredTaskScope` / `Joiner` are preview APIs whose runtime behaviour varies by JDK build. These failures pre-date any given change — do not attempt to fix them by disabling tests or catching the exception. `HtmlRendererTest` (15 tests) must always be green.

## Coding conventions

- No Lombok. Use records, sealed types, and pattern matching.
- Public APIs in `core` need Javadoc with a usage example.
- Tests: JUnit 5 + AssertJ. Use Awaitility for time-based waits.
- Never use `Thread.sleep` outside the demos module.
- Logging: SLF4J only. No `System.out.println` outside demos.
- New dependencies go in the parent `<dependencyManagement>` first; modules
  declare `<groupId>/<artifactId>` without `<version>`.

## Rules for Claude

- For any change touching more than one module, use Plan Mode first.
- Always run the full `mvn -q verify` (not `-pl <module>`) after edits that touch
  `scope-tracer-core` or that span modules. Running a single-module verify against a
  stale core JAR produces false-green results — the analyzer's integration tests will pass
  against old bytecode even when the real build would fail.
- Never silently add a Maven dependency — propose it in chat first.
- Never disable or @Ignore a failing test to make the build green.
- When adding a JFR event, also update `docs/jfr-events.md`. That file has a "Common fields" table and an "Event catalog" table — add a row to the catalog and, if you added a new field, a row to common fields.
- Never use `Thread.stop`, `Thread.suspend`, or other deprecated APIs.
