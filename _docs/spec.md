# scope-tracer

Visualise Java structured concurrency task trees, lifetimes and
cancellation propagation.

This document describes what scope-tracer is, currently. It is edited
whenever a decision changes it, and it is always true of the code as
it stands. Issues name the section they implement.

Why a thing was decided, and what it replaced, belongs in
`_docs/decisions.md` - dated entries, never edited.

§1 to §5 describe the code as it is on `main`. §6 is the direction
and the milestone order.

---

## §1 Scope and definition of done

### What scope-tracer is

A tracer and report generator for
`java.util.concurrent.StructuredTaskScope` on JDK 27+. It records
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
- No support for JDKs before 27. `StructuredTaskScope` is a preview
  API there and its shape has changed between releases (JDK 27's
  JEP 533 added the exception type parameter and removed
  `Joiner.awaitAll()`). Everything is built with `--release 27`, so
  nothing built here runs on 26, and there are no per-JDK artifacts.

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
| `ScopeOpenedEvent` | scope opened | 0 |
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

File order is not time order even for the events of a single thread:
a `ScopeClosed`, a `TaskForked` or a completion can precede the
`ScopeOpened` of the same scope, and `JfrParser` keeps every event
under its `scopeId` and assembles the model after the whole file has
been read. A reader of a raw `.jfr` meets the same inversion.

### Event loss

JFR silently drops events when a per-thread buffer overflows -
empirically around 5,000 events/s per thread on default settings.
The analyzer must tolerate a missing completion or close event: a task
without one is rendered as incomplete, not as an error.
`docs/jfr-events.md` documents the buffer flags; the stress tests run
with them.

---

## §3 Architecture

Java 27 with `--enable-preview` project-wide, Maven 3.9+, JUnit 5,
AssertJ, Awaitility. The plugin is a separate Gradle build: Gradle runs
on JDK 21, and the plugin compiles for Java 25 on a JDK 27 toolchain.

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
| `scope-tracer-analyzer` | Maven | core, SLF4J API (Logback optional, CLI only) | `.jfr` → `TraceModel` → HTML/JSON, the CLI |
| `scope-tracer-agent` | Maven | core, analyzer, ByteBuddy | bytecode instrumentation, auto-HTML |
| `scope-tracer-demos` | Maven | core, analyzer | runnable examples, correct and buggy |
| `scope-tracer-stress-tests` | Maven, `-Pstress` | agent fat-jar | high-volume ITs |
| `scope-tracer-plugin` | Gradle | analyzer jar (as a resource) | IntelliJ tool window |

Code lives under `com.ionutbanu.scopetracer.{core,analyzer,agent,demos,plugin}`.
Maven coordinates are `com.ionutbanu:scope-tracer-*`.

### Core

`TracedScope<T, R, R_X extends Throwable>` wraps `StructuredTaskScope`
(JEP 533) and mirrors its public API exactly. The mirror rule:

- the same type parameters, with the same bounds;
- the same public static factories, each with one addition, a leading
  `String name`, and otherwise the same type parameters, bounds,
  parameter types and return type: `open(name)`,
  `open(name, Joiner)`, `open(name, UnaryOperator<Configuration>)`
  and `open(name, Joiner, UnaryOperator<Configuration>)`; no other
  public static method;
- every public instance method of `StructuredTaskScope`, with the
  same signature: `fork(Callable)`, `fork(Runnable)`, `join()`,
  `isCancelled()` and `close()`;
- two additions, and only these: `fork(String, Callable)`, which
  takes an explicit task name, and `name()`;
- no public constructor.

A reflection test in core compares the two classes on the running JDK
and fails on a missing or an extra public method, so a JDK that
changes `StructuredTaskScope` fails the build until `TracedScope`
follows. Replacing `StructuredTaskScope.open(...)` with
`TracedScope.open("name", ...)` therefore compiles and behaves the
same; only the events differ.

`open(name)` and `open(name, UnaryOperator)` use
`Joiner.awaitAllSuccessfulOrThrow()` (fail-fast). `join()` returns the
joiner's `R` and throws its `R_X`: `ExecutionException` for the
built-in joiners, whose cause is the failed task's exception, exactly
as a plain scope throws. The underlying scope is configured with
`withName(name)` and a virtual-thread factory first, and the caller's
configuration function is applied after them, so a thread factory, a
timeout or a different `withName` it sets takes effect as on a plain
scope. Every event carries the `name` argument as `scopeName`,
whatever the function does. A null or blank `name` throws
`IllegalArgumentException` before anything is opened or emitted.

`fork(Callable)` and `fork(Runnable)` derive a task name and stamp the
call site; `fork(String, Callable)` takes an explicit name and still
stamps the call site. `fork(Runnable)` emits the same events as
`fork(Callable)`, and its subtask's result is `null`, as on a plain
scope. Its name and call site always come from the calling frame, also
for a named `Runnable` class: a plain scope's `fork(Runnable)` hands
the agent only the JDK's wrapper around the `Runnable`, so that is
what the agent records, and both producers must stamp the same.
`isCancelled()` delegates and emits nothing.

`TaskNameDeriver` names a task by, in order: the explicit name, the
`Callable`'s simple class name when it is not a lambda, or
`SimpleClass#method:line` from the call site.
It also gives the fork's call site, stamped on `TaskForked` as
`callSiteClassName`, `callSiteMethodName` and `callSiteLine` whatever
the name, an explicit one included: for a named `Callable` its class
(no method, line `0`), otherwise the first caller frame outside
`TracedScope`, the deriver and `StructuredTaskScope`.
`callSiteClassName` is the JVM binary class name, fully qualified and
uncut (`com.acme.Plain$Named`, `com.acme.Plain$1`). One stack walk
gives both. No usable frame leaves the three fields null / `0`.

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
**Task identity:** every task's bar (its outer `<svg>`) and its table
row (`<tr>`) carry `data-scope-id` and `data-task-id`, the task's
`scopeId` and `taskId` as plain numbers, in root scopes, child scopes
and grouped scopes alike; nothing else carries them (#190). The page
itself does nothing with them; they are what the plugin's Timeline
click reads.
**Themes:** the stylesheet has a light and a dark palette as CSS custom
properties (the same set in both). The dark one applies under
`@media (prefers-color-scheme: dark)` on `:root:not([data-theme="light"])`
and under `:root[data-theme="dark"]`; an explicit `data-theme` on `<html>`
always beats the media query. The outcome fills (success, failed,
cancelled, incomplete, scope bar) and the critical-path stroke are the
same literal colours in both themes; everything drawn on the page
background (text, borders, tracks, ticks, links) uses the palette, and
text meets WCAG AA (4.5:1). Bars and strokes reach 3:1 against the page
in the dark palette; in the light palette only failed, the scope bar and
the critical stroke do, and the light fills stay unchanged by decision
(success, cancelled and incomplete measure 2.66, 2.27 and 2.57; #191).
**Critical path:** in a scope where every task succeeded and has a
completion time, the latest-completing task is outlined amber (`#d97706`)
and its row reads `← critical path +Xms`. Any failure, cancellation or
missing data turns highlighting off for that scope.

`TraceModelJson.toJson` is the JSON wire format consumed by the
plugin. `AnalyzerMain` is the CLI: `analyzer <in.jfr> <out>
[--format=json] [--verbose]`. Options may come before, between or after the two positional
arguments, each at most once; anything else is usage on stderr and
exit 1. An argument starting with `-` (other than `-` alone) is an
option, so an input path starting with `-` is written `./-x.jfr`,
and `--help` with other arguments is usage and exit 1.

Exit codes:

| Code | Meaning |
| --- | --- |
| 0 | success (warnings do not change it) |
| 1 | wrong arguments, usage on stderr |
| 2 | a file or directory was not found: the input, or the directory the output goes in |
| 3 | an I/O error from the JDK reading or writing: input that is not a JFR recording or is cut short in its header (a text file, an empty file), an input that is a directory, an output that cannot be written (a directory, not writable) |
| 4 | the JDK's JFR parser threw on content it had started to read, such as a recording cut short inside a chunk |

Which of 3 and 4 a damaged recording gets depends on where the
damage is and on `jdk.jfr.consumer.RecordingFile`, so a script treats
3 and 4 alike as "the recording could not be read". The input is read
first, so a non-JFR input with a missing output directory is 3, not
2. Nothing after the `Report written` line can change the exit code.
These exit codes are stable from 0.4.0: a later change to any code
needs a dated `_docs/decisions.md` entry and a CHANGELOG "Changed"
line.

- stdout is the one line `Report written to <absolute path>` (or the
  usage for `-h`/`--help` alone), whatever the recording.
- stderr holds the existing one-line errors and, after the report is
  written, warnings a user can act on, printed by `AnalyzerMain` from
  the `TraceModel`, never by reformatting log output. Each is one line
  starting `analyzer: warning: ` and names the input file: one for
  scopes with no `closeTime` and tasks with no completion (counted by
  scope instance, nested ones included, with both causes - still
  running when the recording was written, or dropped by JFR - and a
  link to the buffer-tuning section of `docs/jfr-events.md`), and,
  when the model has no scopes, one of two lines from a count of the
  six event types made while the file is read for the model (no second
  read, so nothing is read after "Report written"): with zero events,
  "contains no scope-tracer events" (no agent or `TracedScope`, or
  the events were not enabled); otherwise "holds N scope-tracer
  events but no ScopeOpened event" (scopes opened before the
  recording started, or events dropped). A model with scopes gets
  neither, orphan events or not. Warnings do not change the exit
  code.
- A fourth warning, `analyzer: warning: could not check <input> for
  problems: <exception>` (the input path, then the exception's
  `toString`), is printed when computing the warnings above throws
  after the report is written. It replaces them, and the exit code
  stays 0. No recording makes it happen today; it is there so a
  failure after `Report written` is never an exit code. It is not
  escaped or bounded, like the analyzer's other stderr lines (#149).
- The library's SLF4J output is hidden. `--verbose` shows it on
  stderr at DEBUG and up, as `LEVEL logger - message`, and adds the
  stack trace after the error line of a run that exits 3 or 4. It is
  the only switch: no system property or environment variable,
  Logback's own included (below). With
  it `JfrParser` logs one DEBUG line per parse: the file, the events
  read per type, the scopes and tasks in the model and the time taken.

Logback is the CLI's backend and an optional dependency of the
analyzer, so a library user brings its own binding. The CLI's
`logback.xml` (`src/cli/`, not a resource directory) is added to the
`-executable` jar only, by Shade; the plain jar carries no logging
configuration, and its classes do not need Logback. That file reads
the root level from a system property that `AnalyzerMain.main` always
sets before the first logger exists (`DEBUG` with `--verbose`, `OFF`
otherwise), so a `-D` on the command line is overwritten. In the same
step `main` removes every `logback.*` system property, whether from
`-D` or `JAVA_TOOL_OPTIONS`: `logback.configurationFile` would replace
the jar's configuration, and `logback.debug` or
`logback.statusListenerClass` print Logback's status lines on stdout.

### Agent

Attached with `-javaagent:scope-tracer-agent-<v>-agent.jar[=args]`.
The fat-jar (shade classifier `agent`) lists itself in
`Boot-Class-Path`, because `StructuredTaskScope` is loaded by the
bootstrap classloader and advice must be visible there.
When the jar is not under the name its manifest lists, `premain`
adds the jar it was loaded from to the bootstrap search path itself,
so the jar works under any name. (The JVM resolves that name beside
the `-javaagent` jar and ignores a missing file.) The application
loader's copy of `ScopeTracerAgent` does only that, in
`BootstrapEntry`: it prints one `[scope-tracer]` line naming the jar
and enters the bootstrap copy, which does everything else, handing
it the jar's path as an argument of a package-private method (no
system property, no public method). The jar it appends is the
`-javaagent` jar, not necessarily the one it was loaded from: the
system class loader searches `-cp` first, so a scope-tracer jar
there (the thin jar, or another copy of the fat-jar) supplies the
running `ScopeTracerAgent`. `BootstrapEntry` takes the last copy of
`ScopeTracerAgent.class` that `ClassLoader.getSystemResources`
lists, because the JVM appends the `-javaagent` jar after every
`-cp` entry and the jars their `Class-Path` attributes name. When
that is not the running copy it prints one `[scope-tracer] ignoring
<jar> on the class path: the -javaagent jar is <jar>` line, both by
real path, before the `added` line. The JVM appends the
`-javaagent` jar only when it is not already reachable from the
class path, so when the last copy is not the running copy and is
reachable from `java.class.path` (compared by real path), the agent
cannot tell which jar is its own. Reachable means an entry of
`java.class.path` (which already holds a `-cp` wildcard's jars), or
a jar listed by an entry's manifest `Class-Path`, directly or
through a chain. `BootstrapEntry` follows `Class-Path` as the JVM
does, breadth first, each file once, so a cycle ends. Entries are
whitespace-separated, and each is `new URL(<listing jar's URL>,
entry)` with its path decoded, never `java.net.URI`. So `sub/x.jar`,
`./x`, `../x`, an absolute path, `file:/abs`, `file:x.jar` and
`FILE:x.jar` (relative to the listing jar), `file://localhost/abs`,
`%20` for a space and `%5E` for `^` all resolve. So do names that
`URI` rejects (`^ [ ] { }`), a fragment (dropped) and a query (part
of the file name). The same decoding reads the copies' `jar:file:`
URLs and the running jar's code source, whose path may hold the same
characters. A raw space, a backslash, a directory, `*`, an `http:`
or `jar:` URL, a `file:` URL on another host, a missing file, a jar
that is not a zip and a manifest that cannot be read are skipped
without a word. An entry that cannot be resolved at all (`%00`,
which `Path.of` rejects, or a malformed escape such as `%zz`) counts
as reachable, so the agent refuses rather than guesses, unless
another jar lists the last copy for certain. The JVM skips such
entries. It reads no manifest when the running copy is the
last copy, nor when the last copy is a `-cp` entry. That is the case
whenever the `-javaagent` jar is reachable from `-cp` together with
any other scope-tracer jar, in either order: `-cp
agent.jar:thin.jar`, `-cp thin.jar:agent.jar`, `-cp
a.jar:agent.jar:b.jar`, or `-cp app.jar` whose manifest has
`Class-Path: agent.jar thin.jar`, `Class-Path: agent.jar
file:thin.jar` or `Class-Path: thin.jar agent.jar`, with
`-javaagent:agent.jar`. It prints one `WARN not tracing` line naming
both copies and both fixes, the rename one with the file name read
from the running jar's manifest (or the last copy's when the running
jar lists none). It adds `(listed by the Class-Path of <jar>)` when
a manifest listed the last copy, or `(the Class-Path of <jar> has an
entry that cannot be resolved)`, and traces nothing. The `ignoring`
line is therefore only printed when the last copy was appended by
the JVM. A `Class-Path` that lists only the thin jar, or only the
`-javaagent` jar, still traces with the `-javaagent` jar. Copies are
compared by real path, while the JVM drops a duplicate only when its
URL string is the same. So the agent over-refuses in one accepted
case: `Class-Path: thin.jar /tmp/.../agent.jar`, with
`-javaagent:/private/tmp/.../agent.jar` reached through the `/tmp`
symbolic link. Main traced it, because the JVM did append the
`-javaagent` jar; the agent now refuses, which is conservative. If
the copies cannot be listed or read, it appends the jar it was
loaded from; if the manifests cannot be
read, the last copy is taken as appended by the JVM. No method `ScopeTracerAgent` declares names a
ByteBuddy type: the JVM resolves every one of their signatures when
it looks `premain` up, and a copy from the thin jar would abort the
JVM there; the ByteBuddy code lives in a nested class. This holds
for a scope-tracer jar of 0.4.0 or later on `-cp`; a 0.3.x or
earlier jar there still takes over, because its own `premain` is the
one the JVM calls. The `Advice.to` calls read advice
bytes from the advice class's loader and then from that jar, because
the bootstrap loader serves no resources from a jar appended at
runtime. The system class loader is never asked for them under a
renamed jar: it searches `-cp` first, and an advice class there would
be inlined instead of the agent's, or, with another method name or
descriptor, make the JDK reject `StructuredTaskScope` with a
`ClassFormatError`. The four `Advice` objects are built once, before
`installOn`, and the jar handle is closed after it; a failing close
is ignored and prints nothing. If the jar cannot
be read then, the agent prints one `WARN not tracing` line naming the
jar and the reason and instruments nothing. Under the original name,
or with `-Xbootclasspath/a:`, the second source stays the system
class loader, as before. The JVM prints
its own class-data-sharing warning then, which the original name or
`-Xbootclasspath/a:` avoids. A jar that cannot be found, opened or
appended gives one `WARN not tracing` line naming both fixes.
The bootstrap classloader is asked first by every other loader, so a
package in the fat-jar replaces the application's own copy of it.
The jar therefore exposes no top-level entry (package, resource or
native library) outside `META-INF/` and `com.ionutbanu.scopetracer`
(`AgentIsolationIT` checks every entry): SLF4J (which the analyzer needs for
auto-HTML) and ByteBuddy are relocated under
`com.ionutbanu.scopetracer.shaded`, along with SLF4J's `slf4j.*`
system property names, and Logback is not bundled. The relocated
SLF4J finds only the agent's NOP provider, so the analyzer's log
lines go nowhere and the application's logging, whatever its layout
or SLF4J version, is the same with the agent as without.

- `premain` only; there is no `agentmain` (no dynamic attach).
- `premain` never throws: an exception out of it aborts JVM startup.
  Each step (configuration, instrumentation, the auto-HTML listener,
  the agent's own recording) catches `Throwable`, logs one warning
  line and loses only what it would have added.
- A transformation of `StructuredTaskScope` or
  `StructuredTaskScopeImpl` that ByteBuddy cannot apply, when the
  class loads or is retransformed, gives one `[scope-tracer] WARN
  cannot instrument <class>, the trace will be incomplete: <reason>`
  line per class, at most once per JVM, also without `verbose`
  (`TransformFailureListener`, added beside the `verbose` listeners).
  It only prints. A scope class in which an advice matched no method
  with code (`no method matched <advice>`, several on one line), or a
  scope returned by `open` whose class is not
  `java.util.concurrent.StructuredTaskScopeImpl` (`StructuredTaskScope.open
  returned it, and the agent instruments only
  java.util.concurrent.StructuredTaskScopeImpl`), gives one `cannot
  instrument` line for that class, with the same prefix and the same
  once-per-class rule as the line above, also without `verbose`
  (#130). The first is checked when ByteBuddy reports the
  transformation (`MatcherMissListener`), the second on the first
  `open` in the JVM (`FirstOpenCheck`); `premain` does no work for
  either, and one class never gets two `cannot instrument` lines.
  Not covered: a JDK change that every matcher still matches but that
  changes what the advice sees (`fork(Runnable)` no longer delegating
  to `fork(Callable)`, the constructor's third `String` no longer
  being the configured name), which only the agent ITs on that JDK
  catch (#55), and a transformed class the JVM rejects (#131).
- Every `[scope-tracer] WARN` line that names an exception ends in
  the same `<reason>`, built by one formatter in `AgentLog`: `Type:
  message`, then `; caused by Type: message` for each cause, at most
  four entries (the throwable and three causes) and then `; caused by
  ...`. `Type` is the simple class name, or the binary name when that
  is empty (an anonymous class). A `null` or blank message is left out
  with its `: `, and so is one that only repeats `cause.toString()`.
  Each type and message is escaped as an argument value is (`\t`, `\r`,
  `\n`, `\uXXXX` for other control characters) and cut at 300
  characters with `...`. The chain stops at a throwable already
  printed. Suppressed exceptions and stack traces are never printed,
  and `toString()` and `getLocalizedMessage()` of the throwable are
  never called, so a hostile throwable gives a shorter reason, never
  an exception. The paths in a WARN prefix are escaped and never cut;
  `INFO` lines are not changed (#135). The JVM gives no cause for a
  class the bootstrap loader cannot find (`NoClassDefFoundError`),
  so those lines have none.
- `premain` sets ByteBuddy's experimental property to `true`.
  The pinned ByteBuddy (`<bytebuddy.version>`) reads Java 27 class
  files without it; it is kept so a JDK newer than the ByteBuddy
  release is still instrumented rather than refused. In the fat-jar
  the property is relocated with ByteBuddy
  (`com.ionutbanu.scopetracer.shaded.net.bytebuddy.experimental`), so
  the application's own `net.bytebuddy.experimental` is untouched.
- The agent's type pool (`PreviewClassFileReader.POOL`, ByteBuddy's
  `PoolStrategy.Default.FAST` with its own class reader) reads a class
  file whose minor version is `0xFFFF` as minor 0, on a copy of the
  bytes, to describe it; every other version reaches ByteBuddy's ASM
  reader unchanged. It is never used for the class being transformed,
  which the `ByteBuddy` instance reads and writes with its own
  version, so the JVM never receives a class file whose version the
  agent changed. Why: on JDK 28 with `--enable-preview` the JVM serves
  the `java.base/META-INF/preview` variants of JDK classes, such as
  `java.time.Duration` (a parameter of
  `StructuredTaskScopeImpl.scheduleTimeout`), as 72.65535, and ASM
  rejects that on purpose until it supports value classes (ASM !473).
  The experimental property above does not help, because ByteBuddy
  compares the minor version as a signed `short`, so 72.65535 reads as
  older than its latest version and is never patched (#176).
- Three matcher chains: every public static
  `StructuredTaskScope.open` overload (`ScopeOpenAdvice`); the private
  3-arg constructor of `StructuredTaskScopeImpl`
  (`ScopeConstructorAdvice`, captures `Configuration.withName()` into
  a `ThreadLocal`); and `fork(Callable)` and `close()` on both.
  `fork(Callable)` is matched by parameter type to avoid
  `fork(Runnable)`.
- One `open` call is one decision and at most one `ScopeOpened`.
  `open()`, `open(Joiner)` and `open(UnaryOperator)` call
  `open(Joiner, UnaryOperator)`, so the same scope leaves two advised
  frames.
  `ScopeOpenAdvice` claims the returned instance in
  `AgentState.OPENED_SCOPES` (weak, by identity) on the first exit,
  which filters, names and emits; every later exit for that
  instance returns at once. Which overloads exist and which one the
  JDK delegates to does not matter.
- `TracingCallable<T>` wraps the user's callable and emits completion
  events.
- `ForkAdvice` stamps `taskName` and the three `callSite*` fields by
  the same rule as core's `TaskNameDeriver`, from one stack walk in
  the agent's own `TaskNameDeriver` (skipping the JDK scope machinery,
  ByteBuddy and itself). The two derivers are separate copies;
  `ProducerCallSiteIT` runs one program under each producer and
  requires equal fields.
- `ScopeNameDeriver` names unnamed scopes `SimpleClassName#methodName`
  via `StackWalker`, skipping only `java.util.concurrent.StructuredTaskScope*`,
  `net.bytebuddy.*`, the agent's relocated ByteBuddy and itself -
  user code may live in the agent's own package.
- Logging goes through `AgentLog` (stderr, `[scope-tracer]` prefix),
  never SLF4J: unrelocated SLF4J or a binding on the bootstrap path
  would take the place of the host application's logging.
- Combining the agent with `TracedScope` emits every event twice.

Agent arguments, parsed by `AgentConfig`:

| Argument | Effect |
| --- | --- |
| `record` / `record=<path>` | start the agent's own recording (below); `record=true` is `record`, `record=false` is as if absent |
| `html` / `html=false` | `html=false`: no auto-HTML on `JFR.stop` or at exit |
| `verbose` / `verbose=false` | print each instrumented class (the ByteBuddy listing) and why an auto-HTML report was skipped (`skipping auto-HTML: ...`); `verbose=false` turns all of it off, beating `-Dscopetracer.agent.verbose=true` |
| `output.dir=<path>` | write auto-HTML here (created if missing) |
| `output.suffix=<ext>` | replaces `.jfr` (default `.html`) |
| `min.scopes=<N>` | skip auto-HTML below N scopes |
| `include.name=` / `exclude.name=<glob>` | filter by scope name |
| `include.package=` / `exclude.package=<glob>` | filter by opening class's package |
| `sample.rate=<0.0-1.0>` | keep this fraction of surviving scopes |

A mistyped or meaningless argument is never silent. Keys are exact
and case-sensitive; an unknown key (`recrod`, `RECORD`) or a token
with a value and no key (`=x`) gives one `[scope-tracer] WARN` line
(the unknown-key line lists the known keys, from one list that the
parser's `switch` also uses) and is ignored; the other arguments
still apply. `record`, `html` and `verbose` take `true` or `false`
ignoring case, and the bare key (or an empty value) means `true`;
`record=false` records nothing of the agent's own and prints nothing;
a file really called `true` or `false` is `record=./true`. Any other
value for `html` or `verbose` warns and keeps the default (report on,
verbose as `-Dscopetracer.agent.verbose` says). A key that needs a
value (`output.dir`, `output.suffix`, `min.scopes`, `sample.rate`, the
four globs) given none warns and keeps the default; `record=` stays
`record`. `min.scopes` below 1 and `sample.rate` outside 0.0-1.0 warn
and are clamped; `sample.rate=NaN` is malformed and keeps 1.0 (it
would otherwise drop every scope). An argument value is escaped and cut
to 100 characters, so a warning is always one line (a reason is cut at
300, see below). A key given twice keeps the last value.
Parsing never throws, and runs only in the bootstrap copy of the
agent, so each warning appears once, also under a renamed jar.

Filtering happens at scope open. A filtered scope emits no events at
all, for its whole lifetime. Exclude beats include; sampling runs
last. Globs support `*`, `**`, `?` and are not segment-aware: `*` and
`**` match any characters, dots included, and the whole name must
match. `com.acme.checkout*` matches `com.acme.checkout`, its
sub-packages and a sibling such as `com.acme.checkoutx`;
`com.acme.**` matches everything under `com.acme.` and not `com.acme`
itself; a literal matches one package. No glob means "this package
and below".

**Auto-HTML.** A `FlightRecorderListener` writes an HTML report next
to any recording that has a destination the moment it stops: on
`jcmd JFR.stop` or `Recording.stop()`, and when the JVM exits for a
`dumponexit` recording (`-XX:StartFlightRecording=...,dumponexit=true`,
with or without `filename=`, and the agent's own recording). At exit
JFR calls the listener from its shutdown hook, after the dump; the
agent sees that the JVM is shutting down (`addShutdownHook` refuses)
and writes the report on that thread, so exit waits for it - about
3 s for 10,000 scopes. Otherwise it writes on a thread of its own. A
recording without scope-tracer events, or with fewer than
`min.scopes`, gets no report. The report goes to a temporary file in
the target directory and is moved into place, so a JVM halted
mid-write leaves no partial `.html`; every failure is one
`[scope-tracer]` warning line. `kill -9` and `Runtime.halt` run no
shutdown hooks and get no report.

**The agent's own recording.** `record` makes `premain` start a JFR
recording (`AgentRecording`), so `-javaagent:...=record` alone leaves
a `.jfr` and its report at exit. It is named `scope-tracer`, enables
only the six scope-tracer events with their own defaults, has
`dumponexit`, is on disk and capped at `maxsize` 250 MB (JFR's own
default for `-XX:StartFlightRecording`). The destination is
`scope-tracer-<pid>-<yyyy_MM_dd_HH_mm_ss>.jfr` in `user.dir` for
`record` and `record=`; the same name inside `<path>` when it is an
existing directory; otherwise `<path>` itself, its parents created and
an existing file replaced. The default name is claimed by creating
the file, adding `-1`, `-2`, ... when it exists, so it never replaces
one. Both absolute paths are printed on stderr (`recording to ...` at
startup, `HTML report written to ...` at exit). A location that cannot
be written, or a value that is not a path, gives one warning and no
recording; instrumentation is unaffected. The agent cannot tell at
`premain` whether `-XX:StartFlightRecording` was also given (no
recording exists yet), so both together give two recordings, which
share JFR's buffers: each file holds the other's events too.

**Known JDK limitation (reported to OpenJDK; no bug ID yet).** On JDK 26 and later, a JVM
that gets SIGTERM during the first ~200 ms of the
agent's `premain` can hang until `kill -9`: the JVM's exit path waits
in `JvmtiExport::post_vm_death` for a JVMTI callback (`premain`) that
is itself waiting for a handshake the exiting thread must take part
in. Any agent that loads many classes in `premain` triggers it. Measured
on Temurin 27.0.1 and 26 (hangs) and Azul Zulu 21 (a JDK-only reproducer
does not hang; the agent itself needs JDK 27). SIGINT and SIGHUP were
not measured.
`premain` does not defend against it, and the agent's instrumentation
and timing are unchanged. A signal after `premain` has returned exits
normally, with and without `record`. `AgentStartupSignalIT` pins this
with a control child that has no scope-tracer code (see decisions,
2026-11-20).

### Plugin

The plugin supports IntelliJ IDEA 2026.3 and later: `since-build`
263 and no `until-build` (see decisions, 2026-11-24). 2026.3 is the
first release that both runs on a JBR the plugin targets and whose
Java plugin knows language level 27, without which the IDE cannot
import a `release 27` project. It is built against `platformVersion`
2026.3 with the IntelliJ Platform Gradle Plugin 2.20.1, and both
Gradle modules compile with `--release 25` (class-file major 69) on a
JDK 27 toolchain, because 2026.3's own jars are Java 25 class files;
Gradle itself runs on JDK 21. `verifyPlugin` checks 2026.3 and the
newest IntelliJ IDEA build from 263 on, EAP included.
The build fails on any class in the plugin jar or `model.jar` above
the class-file major of the lowest supported IDE's JBR
(`pluginMaxClassJava`, 25 for 2026.3, major 69; `checkClassFileVersions`,
run by `check` and `buildPlugin`); nested jars such as the analyzer
are not scanned.

The IDE runs on JBR 25 (2026.3 bundles 25.0.4.1) and cannot load
classes compiled with `--release 27` (class-file major version 71),
which JBR 25 rejects. So the plugin never loads analyzer classes:
it bundles the analyzer's `-executable` jar as a resource
(`copyAnalyzerJar` reads it from `../scope-tracer-analyzer/target/`),
runs it as a subprocess on a JDK 27 or newer with `--format=json`,
and parses the JSON with its own `:model` module.
For the timeline it runs the same jar a second time, after the JSON
run, with the default output, so the HTML it shows is byte for byte
what the CLI writes; it never reads the JSON embedded in the HTML.
Both runs use the same JDK. Neither action asks
for a path. The JDK is chosen (`AnalyzerJdkChooser` in `:model`)
inside the analysis's background task, never on the EDT, first usable
one wins: the JDK chosen explicitly on Settings → Tools → Scope Tracer
(an `applicationConfigurable`; the chosen `java` is saved as
`scopeTracer.jdk27JavaPath`, so a path 0.6.0 saved there is an
explicit choice, and "Automatic", the default, is that key's
absence); for "Run with Scope Tracer", the JDK the traced run itself
used (`JavaParameters.getJdk()`, read in the patcher and carried to
the analysis when the run ends, so nothing is looked up before the
run starts); the project SDK; then the registered JDKs, highest major
version first, then the newest full version, then by name, as
IntelliJ stored them. A candidate is usable when its home exists, its
`java` is executable and `java -version` (`AnalyzerJdk`, the bounded
probe) says 27 or newer; the probe's version decides, not the one
IntelliJ stored, and anything else is skipped and the next tried.
Candidates are probed lazily, in order. An explicit choice that is not
usable falls back to the automatic order with a warning naming it,
why it was skipped, and a "Configure…" link to the settings page; when
nothing is usable the analysis does not start, and an error says the
analyzer needs JDK 27 or newer, lists each candidate tried with its
reason, and links to the settings page. The page offers "Automatic",
showing which JDK it would use and what it skipped, a registered JDK
27+, or a JDK home or `java` from disk; Apply checks the choice with
`java -version` under a modal progress and refuses one older than 27,
or a path with no `java`, keeping the previous setting. The plugin does
not download a JDK (#186) or scan the disk for unregistered ones.
Every subprocess the plugin starts (the analyzer, `java -version`)
goes through `BoundedProcess` in `:model`: stdout and stderr go to
files or are discarded, so the child never blocks on a pipe, and the
call ends when the child exits, when its timeout passes (10 minutes
for the analyzer, `AnalyzerInvocation.TIMEOUT`, until #92; 30 s for
`java -version`) or when the background task is cancelled (which is
also what closing the project does), killing and reaping the child
first and deleting its temp files; a cancel is not reported as a
failure. A failure to delete a temp file never replaces how the call
ended (a cancel, a timeout or an exit stays what it was; an exception
already propagating gets it as a suppressed exception). An analyzer
that exits 0 but writes no usable JSON (empty, `null`, no `scopes`)
is a failed analysis saying so, not an empty trace.

- `:model` - plain Java (`--release 25`), no IntelliJ dependency, so
  its tests run as ordinary JUnit. Gson is `compileOnly` (the IDE ships it).
- The root module has no tests; the IntelliJ Gradle plugin's test
  instrumentation needs a sandbox.
- Features: Tools → "Open .jfr Recording (Scope Tracer)…", a "Scope
  Tracer" tool window with two views of the recording (#62):
  "Timeline", shown first, is the analyzer's HTML report in a
  `JBCefBrowser`, loaded from a temp file's `file:` URL and allowed to
  navigate only within that file (the overview's `#scope-N` links;
  `ReportNavigation` decides), and "Tasks" is the task table. A
  toolbar button "Open in Browser" opens the same file in the system
  browser. JCEF is the bundled plugin "Web Browser (JCEF)"
  (`com.intellij.modules.jcef`), an optional dependency; when it is
  absent or `JBCefApp.isSupported()` is false (checked before any JCEF
  object is made) only "Tasks" and "Open in Browser" are shown, with a
  notice once per IDE session. Both runs happen in the one background
  task, each with the analyzer timeout and cancellation; a failed JSON
  run is one error and replaces nothing, a failed HTML run keeps
  "Tasks", says so in "Timeline" and is one error (`ReportAnalysis`
  and `ReportViews` in `:model`). The temp HTML file is deleted when
  the next recording replaces it and when the project closes
  (`ShownReport`), and a temp recording of "Run with Scope Tracer" only
  after both runs (a tab of its own is disposed with its file when
  closed). The embedded report follows the IDE's UI theme
  (#191): once a page has loaded and whenever the theme changes
  (`LafManagerListener`), `TimelineView` runs the one line from
  `ReportTheme.script` that sets `data-theme` to `dark` or `light` on
  `<html>` (from `JBColor.isBright()`, never removing the attribute;
  the attribute name and its two values are the whole contract with the
  report, the plugin carries no colours) and sets the browser's page
  background to the IDE's. The file is never rewritten, so "Open in
  Browser" follows the OS, not the IDE. A scripting failure is logged
  at WARN only. A single click on a task's bar or table row in
  "Timeline" goes to its `fork` call site, exactly as double-clicking
  its row in "Tasks" does (#190): in the same `onLoadEnd`,
  `TimelineView` runs `TaskClick.script`, which installs a click
  handler on the page that sends the element's
  `"<scopeId>:<taskId>"` through a `JBCefJSQuery` (made with the
  browser, disposed with its client). The file on disk carries no
  bridge code, so "Open in Browser" is unaffected. A click inside a
  link, a button, a form control, a group heading or a failed task's
  stack trace, a click that ends a text selection, and a click on
  anything without both attributes (the scope bar, the axis, the
  overview) are left to the page; the second click of a double click
  (`detail > 1`) is ignored, so it navigates once. A click that
  arrives after its tab or the project closed does nothing.
  `TaskClick.parse` accepts only two non-negative `long`s in ASCII
  digits; anything else is ignored and logged at INFO. On the EDT the
  panel looks the pair up in its own recording (`TaskCallSites`, from
  the parsed JSON and `CallSiteParser.forTask`, so each JVM tab uses
  its own model and a stale click for unknown ids does nothing but log
  at INFO) and hands the call site to the table's
  `TaskSourceNavigator`, the chooser anchored on the browser. A task
  with neither a structured call site nor a parseable `taskName`
  shows one information notification, "Task #N (name) has no call
  site to go to."; the table's double-click on such a row stays
  silent. A bridge failure is logged at WARN and loses the click only.
  The table
  has click-to-source on call sites via Java PSI
  (a structured `callSite` is exact: its binary class name is looked
  up with `JavaPsiFacade.findClass`, a nested class exactly, an
  anonymous or local class as its enclosing named class, with no
  chooser; a recording without the fields falls back to parsing
  `taskName` and a short-name lookup with a chooser),
  and Tools → "Run with Scope Tracer", which runs the selected Java
  Application or JUnit run configuration (a class, a method, a
  package, a directory or a pattern; #64) with the bundled agent jar
  (with `html=false`: the plugin renders the trace itself),
  `--enable-preview` and a JFR recording added to its VM options
  (`TracedLaunch` decides which, and refuses for JDK < 27, an
  already-attached agent or an existing `-XX:StartFlightRecording`;
  one a Maven project's surefire `argLine` adds is expected to be
  refused too, since 2026.3's bytecode applies the JUnit patchers
  before the program patcher, but that is not yet verified in an
  IDE), then opens the run's
  recordings when it ends. The same item, "Run '<name>' with Scope
  Tracer", is in the run gutter icon's menu (`RunLineMarkerExtraActions`)
  and the editor's and project view's context menu
  (`RunContextGroupInner`, after Run and Debug) on a test class,
  method, package or directory. It is a `BaseRunConfigurationAction`
  restricted to `JUnitConfiguration`, so it offers exactly what the
  context's Run offers (an existing configuration, or a temporary one
  created as Run creates it, with Run's chooser when there are
  several) and is not shown on a plain `main` class or where nothing
  can be run; it takes the configuration from the context
  (`ConfigurationContext`), the Tools entry the selected one, and both
  ask `RunActionState`. The JUnit code is in an optional config file
  (`scope-tracer-junit.xml`, depending on the bundled plugin `JUnit`)
  and registers JUnit through the plugin's own
  `tracedConfigurationKind` extension point, so the plugin loads
  without it. Only test runs the IDE's own JUnit runner starts are
  traced: Gradle-delegated test runs are #193, Maven `mvn test`
  configurations #194, TestNG #195. A run IntelliJ hands to Gradle
  or Maven (an application in a Gradle project by default, with
  "Delegate IDE build/run actions to Maven" on in a Maven one, or a
  test Gradle runs) is not traced and is never patched: the build
  tool's provider makes its own `JavaParameters`, so the program
  patcher is not asked (read from the 2026.3 jars; not yet observed
  in an IDE). `TracedRunLifecycle.delegatedLaunchSeen` takes a Run
  launch of another run profile, seen before the configuration's own
  launch and before the patcher, as that case (`DelegatedTool` names
  the tool from the profile's class names, loading none of them),
  releases the request at once and shows one warning naming the tool
  and the setting to change ("Build and run using", and "Run tests using" for tests, IntelliJ IDEA
  for Gradle, "Delegate IDE build/run actions to Maven" off for Maven).
  The delegated run itself runs unchanged. Tracing it is #231. A
  Run launch of an unrelated configuration in that window is taken
  for it too (#196). The recording is
  `scope-tracer-%p.jfr` in a new temp directory per run, so every JVM
  of the run, a forked test JVM included, writes its own file; JFR
  expands `%p` to the pid. `RunRecordings` decides what happens to
  them: missing and empty files are ignored; at most 10, the oldest
  first, are analyzed, each once, in one background task with the
  analyzer JDK chosen as above, a failure being one error naming its
  file; one recording is shown in the main content as before, and of
  several the ones holding scopes are shown, one in the main content
  or each in a closable tab of its own named `<configuration name>
  (pid <pid>)` with the same two views, leaving the main content as it
  was. The next run of the same configuration closes those tabs.
  Several recordings without scopes give the "no StructuredTaskScope
  activity" notice once; no recording at all the "wrote no recording"
  warning once. Recordings of different JVMs are never merged
  (`scopeId` restarts in each JVM, #197). The directory is deleted
  once its recordings are analyzed or failed, and when the project
  closes; a run that left more than 10 keeps them all, and a
  notification names the directory. The agent jar is bundled like the
  analyzer's and extracted under its own file name, because its
  manifest's `Boot-Class-Path` names that file. Only the launch
  started by the action is traced (Run executor, once); an ordinary
  Run or Debug of the same configuration is not, nor is "Rerun" or
  "Rerun Failed Tests" from a traced run's test tree, and everything
  the action set up is released when that launch terminates or fails
  to start (`TracedRunLifecycle` decides). The plugin attaches only
  the agent, never `TracedScope`; code that uses `TracedScope` and is
  run this way records every event twice, which is not detected. The
  Tools entry is shown whenever a project is open, whatever is
  selected (`RunActionState` decides). When no configuration is
  selected, clicking it shows an information notification saying to
  select or create a Java Application or JUnit run configuration; when
  the selected one is of another type (Gradle, Remote JVM Debug,
  Kotlin, TestNG, ...), the notification says `"<name>" has run
  configuration type "<type>".`, with no article before the type
  (#212), and that "Run with Scope Tracer" runs Java Application and
  JUnit configurations. Neither starts a run.
- `build.gradle.kts` reads `analyzerVersion` from the parent POM, so
  the bundled jar always matches the Maven project version.
- The plugin's version is that same version: `version` and
  `plugin.xml`'s `<version>` come from the parent POM, and nothing in
  `scope-tracer-plugin/` is bumped by hand. A plugin-only fix ships as
  a patch release of every artifact. The Marketplace does not accept
  the same version twice, so a re-release needs a new patch version.
  `<change-notes>` is generated: a release links its GitHub Release and
  the tagged `CHANGELOG.md`, a `-SNAPSHOT` links `[Unreleased]`.

A change to `TraceModelJson`'s output is a change to the plugin's
input. Both sides change in the same issue. Golden JSON files, one
per fixture recording, in
`scope-tracer-analyzer/src/test/resources/contract/` enforce it: the
analyzer's `TraceModelJsonContractTest` (`mvn verify`) checks they
are byte for byte what the analyzer writes, and the plugin's
`AnalyzerJsonContractTest` (`./gradlew build`) parses the same files
in place, asserts every value, and fails on a key it does not map.

---

## §4 Test architecture

### Where tests live

`scope-tracer-{module}/src/test/java/com/ionutbanu/scopetracer/{module}/`.

| Suite | Runner | Count (2026-11-27, #190 on main f7fff76, JDK 27) |
| --- | --- | --- |
| core unit | surefire | 106 |
| analyzer unit | surefire | 181 |
| analyzer integration (`*IT`) | failsafe, needs the `-executable` jar | 28 |
| agent unit | surefire | 250 |
| agent integration (`*IT`) | failsafe, needs the fat-jar | 317 |
| demos unit | surefire | 67 |
| stress (`-Pstress`) | failsafe | 3 |
| plugin `:model` | Gradle | 251 |

`mvn -q verify` runs the first six: 949 tests (surefire plus
failsafe, measured on 2026-11-27 on the branch of #190 from `main` at f7fff76, on Temurin 27; the
`:model` row from `./gradlew build` in `scope-tracer-plugin`). The
figures are a snapshot, not a target; the stress row is the number of
test methods, as that profile needs an `install` to run. A change must leave the
count the same or higher than `main` had before it. CI runs `verify`
on every push and PR. The stress workflow (`stress.yml`) runs `-Pstress`
for only the stress module's own tests, on PRs to and pushes to `main` that
touch core, agent, analyzer, the stress module, a POM or the workflow, and weekly. A
weekly job (`jdk-ea.yml`, §5) runs the same `verify`, with the same
suites and count, on the newest JDK 28 early-access build under
`-Pjdk-ea`; it is not a required check.

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
  fat-jar, which is why they run in failsafe after `package`. The
  analyzer's ITs run its `-executable` jar in a child JVM the same
  way, because a logging backend is configured once per JVM.
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
- CI (`ci.yml`), the release (`release.yml`) and the stress run
  (`stress.yml`) use Temurin 27, one JDK and no matrix; the plugin
  job adds JDK 21, which Gradle runs on, and names the 27 to Gradle
  as the plugin's toolchain
  (`-Dorg.gradle.java.installations.fromEnv=JAVA_HOME_27_X64`, printed
  by `javaToolchains`; no auto-provisioning). The plugin compiles with
  `--release 25` for 2026.3's JBR 25. The Maven enforcer refuses a
  JDK older than 27 before anything is compiled.
- The JDK early-access run (`jdk-ea.yml`, "JDK 28 early access")
  runs `mvn -B -Pjdk-ea verify` on Temurin `28-ea`
  (`actions/setup-java@v5`) every Saturday at 06:17 UTC, on
  `workflow_dispatch`, and on pull requests to `main` that change the
  workflow file; never on push, and it is not a required check. It
  exists to catch a JDK change that stops the agent's matchers from
  matching (§3 "Agent"): the agent ITs assert the events, so a break
  is a red run. The `jdk-ea` profile, explicit only, builds for the
  running JDK (`maven.compiler.release` is
  `${java.specification.version}`) with no `--enable-preview` in
  javac, the surefire and failsafe `argLine`s, or the child JVMs and
  javac runs the tests start (they read `scopetracer.test.preview`,
  which the POM sets to `false` under the profile); its own enforcer
  rule refuses a JDK older than 28. The job writes `java -version` to
  the step summary, uploads the surefire and failsafe reports on
  failure, and times out after 30 minutes. A red run is reported by
  GitHub's mail to whoever last edited the `cron` line, and is the
  maintainer's to triage. When `28-ea` can no longer be resolved
  (after GA) the setup step is red; moving to the next EA is #70.
  A second job in the same workflow, "agent with `--enable-preview`",
  with the same triggers, setup, summary, report upload and limit,
  runs `mvn -B -Pjdk-ea -Dscopetracer.test.preview=true -pl
  scope-tracer-agent -am verify`: the build stays without the flag,
  and every child JVM the agent ITs start gets `--enable-preview`, as
  a user on 28 who keeps the flag runs (#176).
- The Plugin Verifier run (`plugin-verifier.yml`, "Plugin Verifier")
  runs `buildPlugin verifyPluginStructure verifyPlugin` as
  `release.yml`'s `check` job does (JDK 21 and 27, `mvn -q
  -DskipTests package` first), against the IDEs `pluginVerification`
  names, the newest 263+ build and EAP among them, so a run can go red
  with no commit of ours. It runs on pull requests to `main` and on
  pushes to `main` that change `scope-tracer-plugin/**` or the
  workflow file, weekly (Wednesday 05:43 UTC) and on
  `workflow_dispatch`. It is not a required check, has `contents:
  read` and no secret, one run per ref (`concurrency`, cancelling the
  older), and a 30-minute limit. The verifier's own downloads,
  `~/.pluginVerifier/loaded-plugins`, are restored on every run and
  saved only by a run on `main` that succeeded, at most one entry a
  week (key: the plugin's build files and the ISO week), so every pull
  request restores a cache that `main` saved (`actions/cache/restore`
  and `save`). The IDEs themselves are Gradle dependencies and are
  cached with the Gradle user home by `setup-gradle`; the verifier's
  `ides` directory stays empty. It fails when fewer than two IDEs were
  verified. `release.yml`
  keeps its own `verifyPlugin`: the release is the gate, this is the
  early warning (#215).
- maven-shade-plugin runs with the ASM named by `shade.asm.version`
  (9.11.1) in place of its own 9.9.1, which cannot read class-file
  major 71.
- Releases are cut by tagging `v*.*.*`; `.github/workflows/release.yml`
  deploys to Maven Central and creates a GitHub Release from the
  matching `CHANGELOG.md` section. Its `check` job (tag matches the
  POM, then `.github/scripts/check-release-docs.sh`: README, docs,
  CHANGELOG heading and link, the JBang catalog and demo, and the
  `Released:` line below name the release version) must pass before the `publish` job runs; a
  `check_only` dispatch input runs the check job alone. The script comes
  from the dispatched ref and reads the tag's docs, so a dry run from
  `main` can check an old tag. To Maven Central the release publishes
  the parent POM, core, analyzer and agent only: the `check` job also
  builds the bundle with `-DskipUpload=true`, and
  `.github/scripts/check-release-bundle.sh` fails it when it holds any
  other artifact (the demos and stress tests set `skipPublishing`).
  The IntelliJ plugin zip is never in the Central bundle; it goes to
  the GitHub Release and the JetBrains Marketplace (#42). Steps are in
  `CONTRIBUTING.md`, the one-time setup in
  `docs/jetbrains-marketplace-setup.md`.
- The plugin in `release.yml`. The `check` job, with no secret,
  installs Temurin 21 and 27 and Gradle, runs `./gradlew buildPlugin
  verifyPluginStructure verifyPlugin` in the tag's
  `scope-tracer-plugin/` after the Central bundle build has built the
  jars it bundles, then the dispatched ref's
  `.github/scripts/check-plugin-zip.sh <zip> <version> <tag's
  gradle.properties>`. The script exits 0 only when the plugin jar's
  `plugin.xml` has the plugin id, the version, the `pluginSinceBuild`
  of `gradle.properties` and non-empty change notes, the jar has
  `META-INF/pluginIcon.svg`, exactly one `agent/*.jar` named for the
  version and matching `agent/agent-jar-name.txt`, and an
  `analyzer/analyzer.jar` whose `pom.properties` says that version;
  it refuses a SNAPSHOT version and names each rule that fails.
  `check-plugin-zip-test.sh` is its self-test, in CI. A tag whose tree
  has no `.github/scripts/check-plugin-zip.sh` predates plugin
  releases (v0.6.0 and earlier, plugin 0.1.0): its plugin steps are
  skipped with a `::notice::`, so a `check_only` dry run of it passes.
  The `publish-plugin` job (`needs: publish`, skipped by `check_only`,
  JDK 21 and 27) first runs `.github/scripts/check-plugin-secrets.sh`
  (self-test `check-plugin-secrets-test.sh`, in CI), which is told only
  whether each secret is set: a missing `JETBRAINS_CERTIFICATE_CHAIN`,
  `JETBRAINS_PRIVATE_KEY` or `JETBRAINS_PRIVATE_KEY_PASSWORD` fails the
  job before anything is built, naming the secret. It then rebuilds the
  zip from the tag, runs `signPlugin` and `verifyPluginSignature`,
  re-runs `check-plugin-zip.sh` on the `-signed` zip and attaches it to
  the tag's GitHub Release. `publishPlugin` uploads the signed zip to
  the Marketplace only when the repository variable
  `PLUGIN_MARKETPLACE_PUBLISH` is `true` (set after the first, manual
  upload, #198); otherwise the step is skipped with a notice, and with
  the variable `true` a missing `JETBRAINS_MARKETPLACE_TOKEN` fails the
  job. The secrets reach Gradle only as the environment of the steps
  that sign or upload, as `CERTIFICATE_CHAIN`, `PRIVATE_KEY` (both
  Base64), `PRIVATE_KEY_PASSWORD` and `PUBLISH_TOKEN`, with the
  configuration cache off and the project cache (where Gradle records
  task inputs in plain text) under `$RUNNER_TEMP`, deleted by an
  `if: always()` step. In the build, `deleteSignedZip` removes any
  earlier `-signed` zip before every `signPlugin` (#220), `signPlugin`
  writes a receipt naming the zip only when it actually signs, and
  `requireSignedZip` fails unless that receipt exists and names
  `signPlugin`'s output, so a zip not signed in this build is never
  used, even if the deleted path drifts. `verifyPluginSignature` and
  `publishPlugin` depend on it, `verifyPluginSignature` checks that
  zip against the decoded chain, and `publishPlugin`'s archive is that
  zip and nothing else (2.20.1 would take the unsigned one whenever
  `signPlugin` is up to date) and is printed before the upload. The
  plugin's bundled jars come from `copyAnalyzerJar` and
  `copyAgentJar`, `Sync`s into
  `scope-tracer-plugin/build/bundled-resources/` that run every build,
  so a zip never carries an earlier version's jar. The Marketplace
  reviews each version before users see it.
- `jbang-catalog.json` at the repository root defines the JBang aliases
  `analyze` (the analyzer's `-executable` jar from the release assets)
  and `demo` (`scope-tracer-demos/jbang/ScopeTracerDemo.java`, which
  runs itself in a child JVM with the agent attached and `record`). Both
  name the latest release with a literal version and change only in the
  release commit; `check-release-docs.sh` reads the catalog and the demo
  source and fails when either is missing, when a scope-tracer
  coordinate in them has no literal version, on any `releases/latest`
  URL, and when the catalog itself names no pinned scope-tracer artifact
  (a coordinate, or a `https://github.com/ionut-banu/scope-tracer/releases/download/vX.Y.Z/`
  URL: the same path on another host does not count). The demo passes
  its command-line arguments on to its child JVM, and SIGTERM to the
  demo stops the child too.
- Released: 0.1.0 (2026-05-14), 0.2.0 (2026-05-24), 0.3.0
  (2026-11-14), 0.4.0 (2026-11-19), 0.5.0 (2026-11-23), 0.6.0
  (2026-11-24). `main` is 0.7.0-SNAPSHOT.
- GitHub Pages serves `docs/`: the landing page, the gallery at
  `gallery/` (a page and eight reports, one per join policy, plus the
  example report) and `example/order-processing-report.html`. The
  gallery and the example are generated by `GalleryMain` in the demos
  module with `TracedScope` (never the agent); `GalleryMain --check`
  runs in CI after `verify` and fails a pull request whose renderer
  change was not followed by regenerating them. `docs/` is published;
  `_docs/` is not.
- The README's quick start uses only released artifacts (the GitHub
  Release jars, Maven Central), with literal versions that
  `check-release-docs.sh` checks; its lead image is
  `docs/img/readme-report.png`, a screenshot of the committed gallery
  report `fail-fast.html`, and `.github/scripts/readme-image.sh --check`
  (a CI step beside `GalleryMain --check`) fails when that report
  changed and the image was not shot again. The landing page's trial
  command is the README's quick start block, checked byte for byte.
- Dependabot opens weekly PRs; they are merged by a human outside the
  issue loop.

---

## §6 Direction

The agent plus the IDE is the primary experience; distribution
(people actually finding and using the tool) matters as much as new
features.

Milestones, in order. Each is a GitHub milestone; its issues carry
the detail.

1. **M1 — Release 0.3.0** - the first release on JDK 27 (#54, decided
   2026-10-16).
2. **M2 — Plugin: beyond the viewer** - the plugin runs the user's
   code with the agent attached and opens the trace, instead of only
   opening a `.jfr` recorded by hand.
3. **M3 — Ready for JDK 28** - CI against JDK 28 early-access builds
   (#55); JDK 27 ships structured concurrency in its seventh preview
   and JEP 543 finalizes the API in JDK 28 without further change, so
   what remains is watching the JDK internals the agent instruments.
4. **M4 — Try it in one minute** - the project has no outside users
   yet, and the quick start's analyzer step needs a clone and a Maven
   build.
5. **M5 — Plugin on the Marketplace** - the IDE is where §1 says the
   tool should reach people, and the Marketplace is a discovery
   channel of its own.
6. **M6 — Answers, not just pictures** - the report should say what
   went wrong (a task that ignored cancellation, a timeout, the task
   that dominates across many requests), not only what happened.
7. **M7 — 1.0 on JDK 28** - JDK 28 GA in March 2027 is the first JDK
   where structured concurrency needs no `--enable-preview`, and when
   most Java developers will first look at it.
8. **M8 — Dynamic attach** - `agentmain`, so a running JVM can be
   traced without a restart; the largest and least certain agent
   change, and attaching to a running JVM is planned to be disallowed
   by default (JEP 451), so it stays last.

**v2 — Backlog** holds everything not in a current milestone.
