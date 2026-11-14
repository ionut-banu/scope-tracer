# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **Agent `record` argument.** `-javaagent:scope-tracer-agent-<v>-agent.jar=record` makes
  the agent start a JFR recording of its own in `premain`, so no `-XX:StartFlightRecording`
  is needed: when the program exits there is a `.jfr` and its HTML report on disk, and both
  absolute paths are printed on stderr. By default the file is
  `scope-tracer-<pid>-<yyyy_MM_dd_HH_mm_ss>.jfr` in the working directory, never written over
  an existing file; `record=<path>` names the file (or a directory for the default name).
  The recording is named `scope-tracer`, holds only scope-tracer events, and is capped at
  250 MB. A location that cannot be written gives one warning and no recording; the program
  runs and is instrumented as without the argument. Opt-in: without `record` nothing changes.

### Changed

- **The agent writes the HTML report when the JVM exits.** A recording with a destination
  that is stopped by JVM exit (`-XX:StartFlightRecording=filename=...,dumponexit=true`, or the
  agent's own `record`) now gets its report, as one stopped with `jcmd JFR.stop` always did.
  Before, the report was started on a thread the JVM did not wait for, and none appeared.
  This applies to the README's own 0.3.0 command: it now leaves an `.html` beside the `.jfr`,
  and exit waits while the report is written (about 3 s for 10,000 scopes). `html=false`
  restores the 0.3.0 behaviour; `output.dir`, `output.suffix` and `min.scopes` apply to the
  report at exit as on `JFR.stop`. A report is written to a temporary file and moved into
  place, so it is never left half-written.
- **The agent never aborts JVM startup.** A failure in `premain` (an `output.dir` that is
  not a valid path, for example) is one `[scope-tracer]` warning and loses only what that
  step would have added. Before, the exception left `premain`, which aborts JVM startup.
- **IntelliJ plugin:** "Run with Scope Tracer" attaches the agent with `html=false`. The
  plugin shows the trace itself, so no `.html` is written beside its recording and the IDE
  does not wait for one at exit.

### Fixed

- **A renamed agent jar traces.** The fat-jar's `Boot-Class-Path` names its original file,
  so a copy under another name (`agent.jar`) used to be instrumented but record nothing, with
  no warning. The agent now adds the jar it was loaded from to the bootstrap class path
  itself and prints one `[scope-tracer]` line saying so; arguments, `record`,
  `-XX:StartFlightRecording` and the report work as under the original name. The JVM prints
  its own `Sharing is only supported for boot loader classes because bootstrap classpath has
  been appended` warning in that case; the original name or `-Xbootclasspath/a:<jar>` avoids
  it. If the jar cannot be added, one `WARN not tracing` line names the reason and the fix.

## [0.3.0] - 2026-11-14

### Added

- **Agent capture filters & sampling.** Five new `-javaagent` arguments let operators
  scope tracing to a subset of `StructuredTaskScope` instances at scope-open time:
  `include.name=<glob>`, `exclude.name=<glob>`, `include.package=<glob>`,
  `exclude.package=<glob>`, and `sample.rate=<0.0-1.0>`. Filtered or sampled-out scopes
  emit **zero** JFR events for their entire lifetime (open, fork, completion, close);
  the existing null-state short-circuits in `ForkAdvice`, `ScopeCloseAdvice`, and
  `TracingCallable` make the hot fork/close paths cost-free for excluded scopes.
  Exclude rules win over include rules; sampling is applied last so out-of-scope
  traffic does not consume the sample budget. Glob dialect supports `*`, `**`, `?`
  and is not path-segment aware. Unblocks production deployments at scale where
  unconditional tracing would exhaust JFR per-thread buffers or balloon `.jfr` size.
- **Analyzer JSON output.** `--format=json` (third CLI argument) writes the parsed trace as
  JSON instead of an HTML report, for tools that want the model without loading any
  scope-tracer classes. The IntelliJ plugin is the first consumer.
- **Fork call sites.** `TaskForked` events carry `callSiteClassName`, `callSiteMethodName`
  and `callSiteLine`, captured independently of the task label (also when an explicit
  name is passed to `fork`). `TracedScope` and the agent both stamp them, by the same rule.
  `callSiteClassName` is fully qualified: the JVM binary class name, uncut
  (`com.acme.Plain$Named`). The fields are nullable and absent in recordings made before
  0.3.0; the analyzer's `TaskRecord` and the JSON output expose them as `callSite`. The
  IntelliJ plugin opens the exact class for them, with no chooser, and falls back to the
  task name for recordings without them.
- **IntelliJ plugin (first milestone).** `scope-tracer-plugin/` adds Tools → "Open .jfr
  Recording (Scope Tracer)…", a "Scope Tracer" tool window showing the parsed trace, and
  click-to-source from a task to its fork call site. It runs the bundled analyzer jar as
  a subprocess on a user-configured JDK 27 and parses its JSON, so it needs no
  preview-API classes in the IDE's JVM. Built from source in this release; see the README.
- **Run with Scope Tracer.** Tools → "Run with Scope Tracer" runs the selected Java application
  run configuration with the agent attached and opens the recording in the Scope Tracer tool
  window when the process exits, so no JVM flags need to be written by hand.

### Changed

- **Agent jar.** The agent fat-jar no longer ships `byte-buddy-agent` (the unused
  `shaded/net/bytebuddy/agent/` classes) or its Windows attach DLLs at the jar root, and
  `AgentIsolationIT` now fails on any entry outside `META-INF/` and `com/ionutbanu/scopetracer/`.
- **JDK 27 is required; JDK 26 is no longer supported.** Everything is built with
  `--release 27` (class-file major 71), so the core, agent and analyzer jars do not load on
  JDK 26, and `TracedScope` (the one preview-stamped class) loads only on 27. On JDK 26 the
  build stops at the Maven enforcer. There are no per-JDK artifacts; 0.1.0 and 0.2.0 stay the
  releases for JDK 26, and the recordings they made still parse. The plugin refuses a run
  configuration or an analyzer `java` older than 27 with a message naming 27; the analyzer
  path is saved under a new key, so a JDK 26 path saved by an earlier build is asked for again.
- **`TracedScope` mirrors JDK 27's `StructuredTaskScope` (JEP 533) — breaking.** It has the
  same type parameters, the same four `open` factories with a leading `String name`, and the same
  instance methods, so `StructuredTaskScope.open(...)` becomes `TracedScope.open("name", ...)`
  and nothing else changes:
  - `TracedScope<R>` becomes `TracedScope<T, R, R_X extends Throwable>`. `T` is the tasks'
    result type, so typed joiners compile without casts, and `R_X` is the exception `join()`
    throws: `R join() throws R_X, InterruptedException`. With the built-in joiners `join()` throws
    `java.util.concurrent.ExecutionException` (cause: the failed task's exception), as a plain
    `StructuredTaskScope` on 27 does; `StructuredTaskScope.FailedException` and
    `Joiner.awaitAll()` no longer exist (use `Joiner.allUntil(subtask -> false)`). `fork`'s
    type parameter is `<U extends T>`.
  - The factories are `open(String)`, `open(String, Joiner)`,
    `open(String, UnaryOperator<Configuration>)` (new) and
    `open(String, Joiner, UnaryOperator<Configuration>)` (new). The configuration function runs
    after scope-tracer's defaults (the name and virtual threads), so a thread factory, a timeout
    or a name it sets takes effect; events keep the `name` argument as `scopeName`.
  - `open(String, ThreadFactory)` and `open(String, Joiner, ThreadFactory)` are removed: the JDK
    has no such factories, and next to the new ones they make `open(name, cfg -> ...)`
    ambiguous. Pass the factory through the configuration function instead.
  - The public constructors `TracedScope(String)` and `TracedScope(String, ThreadFactory)` are
    removed, with no deprecation period, because they were a hole: the caller chose `R_X`, so
    `new TracedScope<Void, RuntimeException>("sneaky")` compiled with no `catch` and its `join()`
    threw a checked `ExecutionException` nothing declared. That no longer compiles.
    `new TracedScope<Void>("checkout")` (0.2.0) and `new TracedScope<Void,
    ExecutionException>("checkout")` (0.3.0 snapshots) become `TracedScope.open("checkout")`.
  - `fork(Runnable)` and `isCancelled()` are added, as on `StructuredTaskScope`. `fork(Runnable)`
    emits the same events as `fork(Callable)`, with the calling frame as its name and call site
    (also for a named `Runnable` class, as the agent records it). All three `fork` methods now
    throw `NullPointerException` for a null task before emitting anything, as the JDK's do.

  ```java
  // 0.2.0 (0.3.0 snapshots: new TracedScope<Void, ExecutionException>("checkout"))
  try (var scope = new TracedScope<Void>("checkout")) { ... }
  try (var scope = TracedScope.open("race", Joiner.anySuccessfulOrThrow(), factory)) {
    Response first = (Response) scope.join();
  }

  // 0.3.0
  try (var scope = TracedScope.open("checkout")) { ... }   // join() throws ExecutionException
  try (var scope = TracedScope.open("race", Joiner.<Response>anySuccessfulOrThrow(),
                                    cfg -> cfg.withThreadFactory(factory))) {
    Response first = scope.join();
  }
  ```

### Fixed

- **IntelliJ plugin: "Run with Scope Tracer" traced launches the user did not ask for and leaked
  what it set up.** A run that never started (failed before-run build, cancelled task) left its
  configuration registered, so later ordinary Runs and Debug launches of it got `-javaagent`,
  `--enable-preview` and the JFR options and opened a trace; it also left the message-bus
  subscription, the temp `.jfr` file and the extracted agent jar behind. Now only the Run launch
  started by the action is patched, once, and everything is released when it terminates, fails to
  start or is not patched. Clicking the action again replaces the earlier request.

- **Agent recorded two scopes for `open()` and `open(Joiner)`.** Both overloads call
  `open(Joiner, UnaryOperator)`, and the agent advised all three, so each call emitted two
  `ScopeOpened`: the forks and the close went to the second, and the first stayed in the report
  as an empty scope that never closed (counted as "truncated" by the analyzer). Sampling also
  drew twice for these scopes, so `sample.rate=0.5` kept about 75% of them. The agent now makes
  one decision and emits one `ScopeOpened` per scope, whichever overload is called. 0.1.0 and
  0.2.0 are affected; recordings they made still contain the empty scopes.
- **Analyzer dropped tasks whose `TaskForked` came before `ScopeOpened` in the `.jfr` file.**
  JFR does not write the events of a scope in time order, even those of one thread, and
  `JfrParser` discarded a fork it read before its scope's open event. A report built from a
  real recording could silently lack tasks (and nested scopes could lose their `parent`),
  in 0.1.0 and 0.2.0 too. The parser no longer depends on the position of any event.
- **Attaching the agent silenced the application's SLF4J logging.** The agent fat-jar is on
  the bootstrap class path, which every class loader asks first, and it carried SLF4J (for the
  analyzer's auto-HTML) and ByteBuddy unrelocated, so they replaced the application's own
  copies. Logging loaded by a child class loader (Spring Boot executable jars, servlet
  containers) and SLF4J 1.7 on the class path fell back to `NOPLoggerFactory` and printed
  nothing, SLF4J 2.x on the class path ran on the agent's copy, and an application that sets
  `slf4j.provider` failed at its first log call. SLF4J and ByteBuddy are now relocated under
  `com.ionutbanu.scopetracer.shaded`, the jar exposes no package outside
  `com.ionutbanu.scopetracer`, and the analyzer's own log lines no longer reach the
  application's output.

### Dependencies

- ByteBuddy 1.18.8 → 1.18.17
- Logback 1.5.32 → 1.6.6; SLF4J 2.0.18 → 2.0.20
- JUnit 6.1.0 → 6.1.3
- google-java-format 1.27.0 → 1.36.1; Spotless maven-plugin 3.5.1 → 3.11.0
- maven-surefire-plugin 3.5.5 → 3.6.1; maven-compiler-plugin 3.15.0 → 3.16.0;
  maven-enforcer-plugin 3.6.3 → 3.7.0; maven-jar-plugin 3.5.0 → 3.5.1
- JaCoCo 0.8.14 → 0.8.16; SpotBugs maven plugin 4.9.8.3 → 4.10.5.0
- central-publishing-maven-plugin 0.10.0 → 0.12.0
- maven-shade-plugin now runs with ASM 9.11.1 (`shade.asm.version`) in place of its own
  ASM 9.9.1, which cannot read class-file major 71 (JDK 27); the agent's relocation rewrites
  every class through ASM
- actions/checkout GitHub Action v6 → v7
- New with the IntelliJ plugin's separate Gradle build: IntelliJ Platform Gradle plugin 2.18.1
  (building against IntelliJ IDEA 2025.3.6.1), Gson 2.10.1, JUnit 5.11.0 and AssertJ 3.27.7 for
  its `:model` tests, and the gradle/actions/setup-gradle v4 and actions/setup-java v5 actions in
  the CI job that builds it

## [0.2.0] - 2026-05-24

### Fixed

- Median task-duration calculation in HTML reports was incorrect for even-length
  task lists (it always selected the upper-middle element instead of averaging
  the two middle values). Both the per-scope summary bar and the repeating-scope
  group row are corrected.

### Dependencies

- JUnit 6.0.3 → 6.1.0
- Spotless maven-plugin 3.4.0 → 3.5.1
- central-publishing-maven-plugin 0.9.0 → 0.10.0
- maven-javadoc-plugin 3.11.2 → 3.12.0; maven-source-plugin 3.3.1 → 3.4.0;
  maven-enforcer-plugin 3.6.2 → 3.6.3; maven-gpg-plugin 3.2.7 → 3.2.8
- softprops/action-gh-release GitHub Action v2 → v3

## [0.1.0] - 2026-05-14

### Added
- CI hardening: Jacoco code coverage, SpotBugs static analysis, and Dependabot
  weekly updates for Maven and GitHub Actions.
- `NOTICE` file with attribution required by Apache 2.0.
- Maven Central publishing infrastructure: `release` profile with sources,
  javadoc, GPG signing, and `central-publishing-maven-plugin`.
- `.github/workflows/release.yml` — tag-triggered automated publish to Maven
  Central with a GitHub Release.
- JPMS `module-info.java` descriptors for `scope-tracer-core` and
  `scope-tracer-analyzer` — both modules now declare their `requires` and
  `exports` and work on the module path.
- New `scope-tracer-stress-tests` module with high-concurrency integration tests
  (`HighConcurrencyTracedScopeIT`, `LongRunningScopeIT`, `AgentParallelStressIT`)
  and a dedicated `.github/workflows/stress.yml` CI job.
- `CONTRIBUTING.md` documenting the development workflow and coding conventions.

### Changed
- Surefire `argLine` now prepends `@{argLine}` so the Jacoco agent is attached
  during unit tests.
- **License changed from PolyForm Noncommercial 1.0.0 to Apache 2.0.** The
  library is now free for any use, including commercial.

## [0.1.0-SNAPSHOT]

Initial pre-release. The project is still under active development and the
public API may change without notice until the first tagged release.

### Added

#### `scope-tracer-core`
- `TracedScope<R>` — wraps `java.util.concurrent.StructuredTaskScope` and emits
  six JFR events covering every lifecycle moment: scope opened/closed and task
  forked/succeeded/failed/cancelled.
- Static factories `TracedScope.open(name)` (fail-fast default) and
  `TracedScope.open(name, joiner)` for custom joiners (race / collect-all).
- Globally unique `scopeId` on every event for reliable correlation across
  scopes with the same name and across parent/child nesting.
- `TaskFailedEvent` carries `exceptionType`, `exceptionMessage`, and
  `exceptionStackTrace` for failure analysis.
- Auto-derived task names (`fork(Callable)`) inferred from the callable's class
  or lambda call-site.

#### `scope-tracer-analyzer`
- `JfrParser` reads `.jfr` recordings and builds a `TraceModel` of scopes,
  tasks, outcomes, and parent/child nesting.
- Nesting detection via stable JFR thread IDs (works for unnamed virtual
  threads).
- `HtmlRenderer` produces a self-contained HTML report with inline CSS and SVG
  Gantt timelines per scope.
- Critical-path highlighting: the latest-completing task in an all-successful
  scope is rendered amber with a `← critical path +Xms` annotation.
- Width-aware bar labels — task indices shown only when the rendered bar is
  wide enough to fit them.
- Filtering and summary statistics on the rendered report.
- `AnalyzerMain` CLI entry point and shaded `-executable.jar` artifact.

#### `scope-tracer-agent`
- ByteBuddy-based Java agent that instruments `StructuredTaskScope` at the
  bytecode level, so any JDK 26+ application can be traced without source
  changes (`-javaagent:scope-tracer-agent-*-agent.jar`).
- Auto-HTML generation: any JFR recording started with `filename=` produces an
  HTML report alongside the `.jfr` file when `JFR.stop` is called.
- `AgentConfig` parses agent arguments: `html=false`, `verbose`, `output.dir`,
  `output.suffix`, `min.scopes`.
- Scope-name resolution: `Config.withName(...)` first, falling back to a
  `StackWalker`-based call-site deriver.

#### `scope-tracer-demos`
- Runnable examples: `ParallelFetchDemo`, `FailFastDemo`, `NestedScopesDemo`,
  `OrderProcessingDemo`, `LiveOrderProcessingDemo`, `LiveServiceDemo`,
  `AgentDemo`.
- Each demo writes a `.jfr` recording and an `.html` report to `target/`.

#### Build & docs
- Maven multi-module project under `com.ionutbanu:scope-tracer-*:0.1.0-SNAPSHOT`.
- GitHub Actions CI: build + tests + Spotless style check on every push and PR.
- Spotless with google-java-format 1.27.0 and sortPom.
- maven-enforcer-plugin requires Maven 3.9+ and Java 26+.
- `--enable-preview` enabled project-wide.
- License: PolyForm Noncommercial 1.0.0.
- `docs/jfr-events.md` documents the JFR event schema.

[Unreleased]: https://github.com/ionut-banu/scope-tracer/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/ionut-banu/scope-tracer/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/ionut-banu/scope-tracer/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/ionut-banu/scope-tracer/compare/v0.1.0-SNAPSHOT...v0.1.0
[0.1.0-SNAPSHOT]: https://github.com/ionut-banu/scope-tracer/releases/tag/v0.1.0-SNAPSHOT
