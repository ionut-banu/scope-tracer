# scope-tracer

Visualise Java structured concurrency task trees, lifetimes and
cancellation propagation.

This document describes what scope-tracer is, currently. It is edited
whenever a decision changes it, and it is always true of the code as
it stands. Issues name the section they implement.

Why a thing was decided, and what it replaced, belongs in
`_docs/decisions.md` - dated entries, never edited.

§1 to §5 describe the code as it is on 2026-10-02. §6 is the
direction and the milestone order.

---

## §1 Scope and definition of done

### What scope-tracer is

A tracer and report generator for
`java.util.concurrent.StructuredTaskScope` on JDK 26+. It records
every scope and every forked subtask as JFR events, and turns a
`.jfr` recording into a picture of what ran in parallel, what failed,
what was cancelled, and which nested scope belongs to which task.

There are two ways to produce events and one way to read them:

- `TracedScope` (core) - a drop-in wrapper the user writes in place
  of `StructuredTaskScope`.
- The Java agent - instruments `StructuredTaskScope` itself, so an
  unmodified application is traced.
- The analyzer - reads a `.jfr` file and writes a self-contained HTML
  report, or JSON for tools. The IntelliJ plugin is one such tool.

The agent and the IDE are the primary experience. `TracedScope` stays
supported, but a feature that only works through it is incomplete.

### Who it is for

Java engineers adopting virtual threads and structured concurrency,
who need to see why a scope took as long as it did, or why a task
they expected to finish was cancelled.

### Non-goals

- Not a general profiler or APM. It traces `StructuredTaskScope`
  only - not `ExecutorService`, `CompletableFuture`, or bare threads.
- Not a hosted service. Every output is a local file.
- No support for JDKs before 26. `StructuredTaskScope` is a preview
  API there and its shape has changed between releases.

### Invariants

These hold for every release. Work that breaks one is a bug, whatever
its acceptance criteria say.

- **Tracing never changes behaviour.** A traced scope returns the same
  result, throws the same exception and cancels the same tasks as an
  untraced one. Agent advice is declared with `suppress =
  Throwable.class` so that a tracer bug can lose events but never
  break the application.
- **The trace describes what the JDK did.** Where a report and the
  JDK's actual behaviour disagree, the report is wrong. See §4, "The
  oracle".
- **Recordings stay readable.** The analyzer reads `.jfr` files made
  by every earlier released version. Events and fields are never
  renamed or removed; a new field is nullable and readers check
  `event.hasField(...)` before reading it.
- **The agent and `TracedScope` emit the same events.** A recording
  does not reveal which one produced it, and the analyzer has one
  code path for both.
- **Reports are self-contained.** An HTML report is one file with
  inline CSS and SVG. It loads nothing from the network.

### Definition of done

A change is done when:

- `mvn -q verify` passes from the repository root
- the test count is the same or higher than before (§4)
- `docs/jfr-events.md` is updated if an event or field changed
- `README.md` is updated if user-visible behaviour changed
- `CHANGELOG.md` has an entry under `## [Unreleased]`
- for a plugin change, `./gradlew build` passes in
  `scope-tracer-plugin/`

---

## §2 The event model

The event model is the contract between the producers (core, agent)
and the consumers (analyzer, plugin, anyone reading the raw `.jfr`).

The field-level schema - every event, every field, its type and when
it is emitted - is published in `docs/jfr-events.md`. That file is
user-facing and is kept exact; this section does not repeat it.

### Lifecycle

| Event | When | `taskId` |
| --- | --- | --- |
| `ScopeOpenedEvent` | scope constructed | 0 |
| `TaskForkedEvent` | `fork()`, on the caller thread | ≥1 |
| `TaskSucceededEvent` | task returns normally | same as fork |
| `TaskFailedEvent` | task throws | same as fork |
| `TaskCancelledEvent` | task observes shutdown (`InterruptedException`) | same as fork |
| `ScopeClosedEvent` | `close()`, after all task threads finish | 0 |

All six extend `jdk.jfr.Event` and implement the sealed interface
`TracedScopeEvent` (`com.ionutbanu.scopetracer.core.events`), so a
consumer can switch over them exhaustively.

`scopeId` is globally unique per scope instance and is the primary
key; `scopeName` is not unique and is never used as a key.

### Ordering

JFR does not guarantee cross-thread order in a dump. A
`TaskSucceededEvent` emitted on a task thread may appear after the
`ScopeClosedEvent` emitted on the owner thread, although it logically
precedes it. Consumers order by `getStartTime()`, never by position
in the file.

### Event loss

JFR silently drops events when a per-thread buffer overflows -
empirically around 5,000 events/s per thread on default settings.
The analyzer must tolerate a missing completion or close event: a task
without one is rendered as incomplete, not as an error.
`docs/jfr-events.md` documents the buffer flags; the stress tests run
with them.

---

## §3 Architecture

Java 26 with `--enable-preview` project-wide, Maven 3.9+, JUnit 5,
AssertJ, Awaitility. The plugin is a separate Gradle build on JDK 21.

### The pipeline

```text
user code ──TracedScope──┐
                         ├──► JFR events ──► .jfr ──► analyzer ──► HTML / JSON
plain STS ───agent───────┘                                        │
                                                    plugin ◄──JSON┘ (subprocess)
```

### Modules

| Module | Build | Depends on | Role |
| --- | --- | --- | --- |
| `scope-tracer-core` | Maven | JDK, SLF4J API | `TracedScope`, the six events |
| `scope-tracer-analyzer` | Maven | core | `.jfr` → `TraceModel` → HTML/JSON |
| `scope-tracer-agent` | Maven | core, analyzer, ByteBuddy | bytecode instrumentation, auto-HTML |
| `scope-tracer-demos` | Maven | core, analyzer | runnable examples, correct and buggy |
| `scope-tracer-stress-tests` | Maven, `-Pstress` | agent fat-jar | high-volume ITs |
| `scope-tracer-plugin` | Gradle | analyzer jar (as a resource) | IntelliJ tool window |

Code lives under `com.ionutbanu.scopetracer.{core,analyzer,agent,demos,plugin}`.
Maven coordinates are `com.ionutbanu:scope-tracer-*`.

### Core

`TracedScope<R>` wraps `StructuredTaskScope` and accepts any
`Joiner<Object, R>`. `TracedScope.open(name)` uses
`Joiner.awaitAllSuccessfulOrThrow()` (fail-fast);
`TracedScope.open(name, joiner)` takes a custom one. `join()` returns
the joiner's `R`. `fork(Callable)` derives a task name;
`fork(String, Callable)` takes an explicit one.

`TaskNameDeriver` names a task by, in order: the explicit name, the
`Callable`'s simple class name when it is not a lambda, or
`SimpleClass#method:line` from the call site.

### Analyzer

`JfrParser.parse(Path)` builds a `TraceModel` (records in
`com.ionutbanu.scopetracer.analyzer.model`):

- `TraceModel` - `List<ScopeRecord>` sorted by open time.
- `ScopeRecord` - `scopeId`, name, owner thread name and id,
  open/close time, tasks, and `ParentRef(parentScopeId, scopeName,
  taskId)` (null for root scopes).
- `TaskRecord` - task id, name, thread name and id, fork/completion
  time, `TaskOutcome`.
- `TaskOutcome` - sealed: `Success`, `Failed(exceptionType,
  exceptionMessage, stackTrace)`, `Cancelled`.

**Nesting.** Scope B is a child of task T in scope A when B's
`ScopeOpenedEvent` and T's completion event share a JFR thread id
(`event.getThread().getJavaThreadId()`), and B's lifetime lies inside
T's. Thread names are not used: unnamed virtual threads have none.

`HtmlRenderer.render(TraceModel)` produces the report: per scope, a
metadata line, a task table and an SVG Gantt timeline; child scopes
indented under their parent task; repeated root scopes grouped.
**Critical path:** in a scope where every task succeeded and has a
completion time, the latest-completing task is amber (`#f59e0b`) and
its row reads `← critical path +Xms`. Any failure, cancellation or
missing data turns highlighting off for that scope.

`TraceModelJson.toJson` is the JSON wire format consumed by the
plugin. `AnalyzerMain` is the CLI: `analyzer <in.jfr> <out>
[--format=json]`, exit codes 0-4 as documented in its Javadoc.

### Agent

Attached with `-javaagent:scope-tracer-agent-<v>-agent.jar[=args]`.
The fat-jar (shade classifier `agent`) lists itself in
`Boot-Class-Path`, because `StructuredTaskScope` is loaded by the
bootstrap classloader and advice must be visible there.

- `premain` only; there is no `agentmain` (no dynamic attach).
- `premain` sets `net.bytebuddy.experimental=true` so ByteBuddy can
  read Java 26 class files.
- Three matcher chains: `StructuredTaskScope.open()`
  (`ScopeOpenAdvice`); the private 3-arg constructor of
  `StructuredTaskScopeImpl` (`ScopeConstructorAdvice`, captures
  `Config.withName()` into a `ThreadLocal`); and `fork(Callable)` and
  `close()` on both. `fork(Callable)` is matched by parameter type to
  avoid `fork(Runnable)`.
- `TracingCallable<T>` wraps the user's callable and emits completion
  events.
- `ScopeNameDeriver` names unnamed scopes `SimpleClassName#methodName`
  via `StackWalker`, skipping only `java.util.concurrent.StructuredTaskScope*`,
  `net.bytebuddy.*` and itself - user code may live in the agent's
  own package.
- Logging goes through `AgentLog` (stderr, `[scope-tracer]` prefix),
  never SLF4J: a binding on the bootstrap path would collide with the
  host application's logging.
- Combining the agent with `TracedScope` emits every event twice.

Agent arguments, parsed by `AgentConfig`:

| Argument | Effect |
| --- | --- |
| `html=false` | no auto-HTML on `JFR.stop` |
| `verbose` | log each instrumented class |
| `output.dir=<path>` | write auto-HTML here (created if missing) |
| `output.suffix=<ext>` | replaces `.jfr` (default `.html`) |
| `min.scopes=<N>` | skip auto-HTML below N scopes |
| `include.name=` / `exclude.name=<glob>` | filter by scope name |
| `include.package=` / `exclude.package=<glob>` | filter by opening class's package |
| `sample.rate=<0.0-1.0>` | keep this fraction of surviving scopes |

Filtering happens at scope open. A filtered scope emits no events at
all, for its whole lifetime. Exclude beats include; sampling runs
last. Globs support `*`, `**`, `?` and are not segment-aware.

**Auto-HTML.** A `FlightRecorderListener` writes an HTML report next
to any recording started with `filename=` the moment it stops.

### Plugin

The IDE runs on JBR (JDK 21) and cannot load classes compiled with
`--enable-preview` for 26. So the plugin never loads analyzer classes:
it bundles the analyzer's `-executable` jar as a resource
(`copyAnalyzerJar` reads it from `../scope-tracer-analyzer/target/`),
runs it as a subprocess on a user-configured JDK 26 with
`--format=json`, and parses the JSON with its own `:model` module.

- `:model` - plain Java 21, no IntelliJ dependency, so its tests run
  as ordinary JUnit. Gson is `compileOnly` (the IDE ships it).
- The root module has no tests; the IntelliJ Gradle plugin's test
  instrumentation needs a sandbox.
- Features: Tools → "Open .jfr Recording (Scope Tracer)…", a "Scope
  Tracer" tool window, click-to-source on call sites via Java PSI,
  and Tools → "Run with Scope Tracer", which runs the selected Java
  application configuration with the bundled agent jar, `--enable-preview`
  and a JFR recording added to its VM options (`TracedLaunch` decides
  which, and refuses for JDK < 26, an already-attached agent or an
  existing `-XX:StartFlightRecording`), then opens the recording when
  the process exits. The agent jar is bundled like the analyzer's and
  extracted under its own file name, because its manifest's
  `Boot-Class-Path` names that file.
- `analyzerVersion` in `gradle.properties` is kept equal to the
  Maven project version by hand.

A change to `TraceModelJson`'s output is a change to the plugin's
input. Both sides change in the same issue.

---

## §4 Test architecture

### Where tests live

`scope-tracer-{module}/src/test/java/com/ionutbanu/scopetracer/{module}/`.

| Suite | Runner | Count (2026-10-02) |
| --- | --- | --- |
| core unit | surefire | 29 |
| analyzer unit | surefire | 73 |
| agent unit | surefire | 53 |
| agent integration (`*IT`) | failsafe, needs the fat-jar | 25 |
| stress (`-Pstress`) | failsafe | 3 |
| plugin `:model` | Gradle | 10 |

`mvn -q verify` runs the first four: 180 tests. CI runs it on every
push and PR; the stress profile runs on PRs to `main`.

### The oracle

Correctness here is decided by the JDK, not by opinion. A test that
asserts what scope-tracer emits must drive a real
`StructuredTaskScope` and read back a real JFR recording:

```java
try (var recording = new Recording()) {
    recording.enable("com.ionutbanu.scopetracer.*");
    recording.start();
    // ... run TracedScope, or a subject under the agent ...
    recording.stop();
    recording.dump(tempPath);
}
try (var file = new RecordingFile(tempPath)) {
    while (file.hasMoreEvents()) events.add(file.readEvent());
}
```

- Filter by `scopeName` to isolate a test's events.
- Use `containsExactlyInAnyOrder` for presence and `getStartTime()`
  for order within one thread - never file position (§2, Ordering).
- Agent ITs fork a child JVM with `-javaagent` pointing at the
  fat-jar, which is why they run in failsafe after `package`.
- `JfrParserTest` is an integration test against live recordings.
  `HtmlRendererTest` builds `TraceModel` objects directly - rendering
  is the one place a hand-built model is the right input.
- What a cancelled or failed task looks like is not reasoned about.
  It is produced by running one and recording it.

### Rules

- The test count never goes down. A change that lowers it fails,
  regardless of its acceptance criteria.
- No `Thread.sleep` outside demos. Wait with Awaitility, or
  coordinate with latches.
- A test is not disabled to make a build green.

---

## §5 Build, release, distribution

- Spotless (google-java-format, sortPom), Jacoco and SpotBugs run in
  `mvn verify`; CI fails on any of them.
- Releases are cut by tagging `v*.*.*`; `.github/workflows/release.yml`
  deploys to Maven Central and creates a GitHub Release from the
  matching `CHANGELOG.md` section. Steps are in `CONTRIBUTING.md`.
- Released: 0.1.0 (2026-05-14), 0.2.0 (2026-05-24). `main` is
  0.3.0-SNAPSHOT.
- GitHub Pages serves `docs/` (landing page and a real example
  report). `docs/` is published; `_docs/` is not.
- Dependabot opens weekly PRs; they are merged by a human outside the
  issue loop.

---

## §6 Direction

The agent plus the IDE is the primary experience; distribution
(people actually finding and using the tool) matters as much as new
features.

Milestones, in order. Each is a GitHub milestone; its issues carry
the detail.

1. **M1 — Release 0.3.0** - ship what is already on `main` (capture
   filters, `--format=json`, plugin milestone 1) and stop keeping the
   plugin's `analyzerVersion` in sync by hand.
2. **M2 — Plugin: beyond the viewer** - the plugin runs the user's
   code with the agent attached and opens the trace, instead of only
   opening a `.jfr` recorded by hand.
3. **M3 — Dynamic attach** - `agentmain`, so a running JVM can be
   traced without a restart.

**v2 — Backlog** holds everything not in a current milestone.
