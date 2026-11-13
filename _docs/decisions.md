Decisions made while building. Newest last, one short entry each.

If a decision contradicts `_docs/spec.md`, edit the spec in the same
commit that records the decision here.

The entries dated before 2026-10-02 were reconstructed on that date
from commit history, code comments and the old `CLAUDE.md`, when this
file was created. Their dates are the dates of the commits that made
them. Every entry from 2026-10-02 on is written when the decision is.

---

2026-04-26 - JFR is the transport, not a custom format

Every JDK 26 ships a recorder that is cheap when off, safe on
virtual threads, controllable from `jcmd` on a running process, and
readable with `RecordingFile`. A custom log format would need its own
buffering, its own file handling, and its own way to be switched on
in production. Choosing JFR also means inheriting its one real
weakness - silent drops under per-thread buffer pressure - which §2
of the spec documents rather than works around.

2026-04-26 - Events form a sealed hierarchy in core

The analyzer pattern-matches over `TracedScopeEvent` exhaustively, so
a new event that the analyzer does not handle is a compile error
rather than a silently ignored record. Core carries no JFR-consumer
dependency to make this work.

2026-04-28 - Nesting is detected by JFR thread id and containment

A child scope is opened on its parent task's thread, inside that
task's lifetime. Thread names cannot be the key: unnamed virtual
threads have empty names. The JFR thread id is stable and present on
every event.

2026-04-28 - The agent instruments StructuredTaskScope itself

Wrapping is the cheap path but requires the user to change their
code, and the people most likely to need a trace are looking at code
they did not write. The agent puts its advice on the bootstrap
classpath via `Boot-Class-Path` because `StructuredTaskScope` is a
bootstrap class. ByteBuddy runs in experimental mode until it
officially supports Java 26 class files.

2026-04-30 - scopeId, not scopeName, identifies a scope

Two concurrent scopes with the same name - the normal case for a
request handler - were merged by a name-keyed parser. Every event now
carries a globally unique `scopeId`, and `ParentRef` carries the
parent's id.

2026-05-04 - Fields are only ever added, nullable, behind hasField

`exceptionMessage`, `exceptionStackTrace` and later `taskName` were
added after recordings already existed in the wild. Readers check
`event.hasField` so that a recording from any earlier release still
parses. Renaming or removing a field would break every recording
anyone has kept.

2026-05-06 - Auto-HTML on JFR.stop

People attach the agent to a service and drive JFR with `jcmd`. A
report that appears next to the `.jfr` the moment the recording stops
removes the step most of them would otherwise skip. It is on by
default and turned off with `html=false`.

2026-05-13 - The agent logs through AgentLog, not SLF4J

The agent runs on the bootstrap classloader of someone else's
application. An SLF4J binding there collides with theirs. `AgentLog`
writes `[scope-tracer]`-prefixed lines to stderr and nothing else.

2026-06-02 - Capture filters act at scope open, and drop everything

A filtered scope emits no events for its whole lifetime, rather than
emitting an open and suppressing the rest. Half a scope in a
recording is worse than none, and the existing null-state checks in
fork, close and `TracingCallable` make the excluded path free.
Exclude beats include; sampling runs last so filtered-out traffic
does not consume the sample.

2026-08-07 - The plugin runs the analyzer as a subprocess

The project is compiled with `--enable-preview` for 26, which stamps
every class file so it only loads on that JDK. The IDE runs on JBR,
pinned to 21. So the plugin can never load analyzer classes, not
because of what they use but because of how they were compiled. It
bundles the analyzer's executable jar, runs it on a user-configured
JDK 26 with `--format=json`, and parses the JSON in a plain Java 21
`:model` module. The alternative - a preview-free, lower-target copy
of the parser - would be a second implementation of the same thing.

2026-10-02 - Work is run as an orchestrated team, from historian

Adopted the process from the historian project: an orchestrator
session that dispatches PM, engineer and QA subagents per GitHub
issue, one worktree per issue, a human merging every pull request,
and a reviewer at milestone boundaries. The specification moved from
`CLAUDE.md` into `_docs/spec.md`; `CLAUDE.md` now only includes
`AGENTS.md`. `_docs/` rather than `docs/` because `docs/` is the
published GitHub Pages site.

Adapted where the projects differ: the oracle is the JDK, observed
through real JFR recordings, rather than SQLite; the conformance
count is the total test count; and the worktree hazard is the shared
`~/.m2` repository rather than a shared virtualenv, so `mvn -pl`
always takes `-am`.

2026-10-02 - Milestones: release, then plugin, then dynamic attach

0.3.0 comes first because finished work is sitting unreleased on
`main`, and nobody can adopt what has not shipped. The plugin comes
before dynamic attach because the IDE is where the agent is meant to
reach people, and today the plugin only opens recordings made by
hand. Dynamic attach is the largest agent change and the least
certain, so it goes last.

2026-11-08 - JfrParser stores every event, then assembles the model

`JfrParser` buffered completions and closes that arrived before their
`ScopeOpened` and dropped a `TaskForked` that did. Forks do arrive
first (issue #43; 25 of 60 local stress recordings lost 2 to 31
tasks), so extending the pending buffers to forks would have left the
same position-dependence one event kind at a time. The parser now
records each event under its `scopeId` as it reads it and builds the
model from scopes that have a `ScopeOpened` after the loop; nothing
depends on file order, and the pending buffers are gone. Events of a
scope without a `ScopeOpened`, and completions without a fork, are
still left out (counting them is #68).

2026-11-08 - The open advice acts once per scope instance

Every public static `StructuredTaskScope.open` is advised, and on
JDK 26 `open()` and `open(Joiner)` call `open(Joiner,
UnaryOperator)`, so one call ran `ScopeOpenAdvice` twice: two
`ScopeOpened`, an empty never-closed twin, and two sampling draws
(issue #51). Matching only the overload that does the work would
tie the agent to the JDK's delegation, which changes between
previews (`open(UnaryOperator)` arrives in 27). A per-thread depth
counter was rejected too: an exit that never ran would leave a
thread untraced for good, and a scope opened inside a configuration
function would be hidden. The advice instead claims the returned
instance in a weak identity set on its first exit, the innermost
frame; later exits for the same instance return. A failure after the
claim loses that scope's events, never more.

2026-11-08 - The agent fat-jar relocates SLF4J and ByteBuddy

The fat-jar has to be on the bootstrap class path for the advice, and
every class loader asks the bootstrap loader first, so a third-party
package in the jar replaces the application's copy (issue #50: the
application's SLF4J became the agent's, which looks for providers
only through the system class loader). Excluding SLF4J would not
work: the analyzer, which auto-HTML runs inside the agent, logs
through it, and from the bootstrap path it cannot see the
application's SLF4J. So SLF4J and ByteBuddy are relocated under
`com.ionutbanu.scopetracer.shaded`, and the jar exposes nothing
outside `com.ionutbanu.scopetracer`.

The relocated SLF4J finds no logging backend, and would print its own
"No SLF4J providers were found" warning; a small NOP provider in the
agent module, registered under the relocated service name, keeps it
quiet, so the analyzer's log lines are dropped (routing them to
`AgentLog` is for #68). Bundling `slf4j-nop` would have been a new
dependency for the same twenty lines. SLF4J's `slf4j.*` system
properties are renamed too, because the relocated copy would read the
application's `slf4j.provider`, fail to load that class from the
bootstrap path and break. Relocating ByteBuddy also renames its
`net.bytebuddy.experimental` property, so the agent sets the relocated
name and no longer changes the application's own ByteBuddy. A
two-jar layout (only the advice on the bootstrap path) would avoid
relocation altogether, but is a larger change than this fix needs.

2026-11-13 - The milestones after M2 are re-planned

On 2026-10-16 the milestones were re-planned, and the spec's three
milestones no longer matched them. JDK 27 shipped on 2026-09-15 with
structured concurrency in its seventh preview (JEP 533), and
`TracedScope` built for 26 does not load on it, so M1 became the
first release on JDK 27 (#54) and a new M3 watches JDK 28 early
access, because JEP 543 finalizes the API there without further
change. The project has no outside users yet, so M4 makes the first
minute work and M5 puts the plugin on the Marketplace, where the
IDE's audience is. M6 makes the report say what went wrong, not only
what happened. M7 is 1.0 on JDK 28, the first JDK where structured
concurrency needs no `--enable-preview` (GA March 2027). Dynamic
attach moved from M3 to M8: it is still the largest and least certain
agent change, and loading an agent into a running JVM is planned to
be disallowed by default (JEP 451), so it stays last. The spec's §6
now lists M1 to M8 in that order.

2026-11-13 - Correction: the 2026-08-07 reason was wrong

The 2026-08-07 entry says `--enable-preview` "stamps every class
file". It does not. Measured on the 0.3.0-SNAPSHOT build, bytes 4 to
7 of the class file (minor, then major version): `TracedScope.class`
in core has minor `ffff` (preview) and major `0046`;
`AnalyzerMain.class` in the analyzer and `ScopeTracerAgent.class` in
the agent have minor `0000` and major `0046`. Only `TracedScope`,
which uses the preview API, is stamped. What keeps the analyzer out
of the IDE's JBR 21 is `--release 26` (major version 70), which JBR
21 rejects. The decision stands, the plugin still runs the analyzer
as a subprocess and parses its JSON, and only the reason changes:
the class-file version, not preview stamping.

2026-10-16 - 0.3.0 is the first release on JDK 27; JDK 26 is dropped

JDK 27 ships structured concurrency in its seventh preview (JEP 533),
which changes the API (a third type parameter for the exception
`join()` throws, `ExecutionException` from the built-in joiners,
`Joiner.awaitAll()` gone) and loads preview-stamped class files only
at major 71, so `TracedScope` built for 26 does not load on 27. The
maintainer chose to move everything to 27 and drop 26 rather than
ship a 26 build and follow it with 0.4.0, or keep per-JDK artifacts:
JEP 543 finalizes the API in JDK 28 without further change, so 27 is
the shape 1.0 will have, and one JDK keeps one build, one CI job and
one `TracedScope` signature. The project has no outside users on 26;
0.1.0 and 0.2.0 stay the releases for it, and recordings they made
must still parse (a 0.1.0 fixture was added for that). Done in #54
on 2026-11-13.

maven-shade-plugin 3.6.2, the newest release, depends on ASM 9.9.1,
which cannot read class-file major 71, and the agent's relocation of
SLF4J and ByteBuddy rewrites every class through ASM. Its ASM is
overridden with 9.10.1 in the parent `<pluginManagement>`
(`shade.asm.version`), approved by the maintainer on #54: the version
JaCoCo 0.8.15 and SpotBugs 4.10.3 already use, so the build has one
ASM. An ASM that reads JDK 28 (major 72) is left to #55 together
with whatever JaCoCo and SpotBugs need for 28. ByteBuddy 1.18.11 reads
27 on its own, but `net.bytebuddy.experimental` stays set so a JDK
newer than the ByteBuddy release is still instrumented.

2026-11-13 - Plugin: which launch is traced, and what a repeated click does

Decided in #88. The state of one "Run with Scope Tracer" request lives
in `TracedRunLifecycle` (`:model`); `RunWithScopeTracerAction`,
`TracedRuns` and `ScopeTracerProgramPatcher` only forward the
platform's callbacks to it and carry out its answers.

How the traced launch is identified. `JavaProgramPatcher
.patchJavaParameters` receives the executor and the run profile, not
the `ExecutionEnvironment`, so at patch time the traced launch cannot
be told from another launch of the same configuration by anything but
the executor. The order of the callbacks was NOT observed in `runIde`
(no GUI where this was built; the maintainer's by-hand QA on the issue
is where it is observed). It was read from the bytecode of the
platform the plugin compiles against, IC 2025.3.6.1 (`lib/app.jar`,
`com.intellij.execution.impl.ExecutionManagerImpl`, `javap -c`):
`startRunProfile` publishes `processStartScheduled(executorId, env)`
first; the 2-argument `processStarting(executorId, env)` is published
inside the starter's lambda, before the runner executes (which is
where the Java command line state builds `JavaParameters` and the
patchers run); the 3-argument `processStarting`/`processStarted` with
the process handler come after the process exists; `processNotStarted`
is reachable from many places after `processStartScheduled`
(before-run failure or cancel, runner exception), so it can arrive
with or without the patcher having run; `processTerminated` comes
from the handler. The logic does not rely on that reading: it is
correct for any order of those six callbacks.

- Only the Run executor (`"Run"`, `DefaultRunExecutor.EXECUTOR_ID`) is
  patched, and only once. A Debug launch is never patched and does not
  consume the request.
- The traced launch is claimed by its execution id from the first
  callback that names a Run launch of the configuration. Callbacks
  with another execution id, executor or configuration change nothing,
  so an ordinary Run overlapping the traced one cannot take its
  recording, nor delete it.
- A second Run launch of the configuration that appears before the
  patcher has been asked cannot be told apart from the traced one at
  patch time, so the request gives up (releases everything, shows one
  warning, nothing is patched) rather than guess. After the patcher
  has run, a second launch is simply not patched.
- A token in the launch's user data was rejected: the patcher does not
  see the environment, so it could not read it.

A repeated click releases the old request and starts a new one; it
does not refuse. The earlier request's subscription, temp file and
agent jar are released, an information notification says its trace
will not be shown, and its process, if one is running, keeps running
untraced from the plugin's side. Refusing was rejected because a
request whose callbacks never arrive (a platform path we did not
read) would then block the action until the IDE restarts; replacing
cannot get stuck. Two live traced runs of one configuration remain
out of scope (they would need one `.jfr` file each).

2026-11-13 - Fork call sites: the agent stamps them, and the class is the binary name

Decided in #86 and #100. `TaskForked`'s `callSiteClassName` is
redefined from the simple class name to the JVM binary class name,
fully qualified and uncut: exactly what `StackFrame.getClassName()` or
`Class.getName()` reports (`com.acme.Plain$Named`, `com.acme.Plain$1`,
the hosting class for a lambda, no dot in the unnamed package).
Nothing is cut at `$` when recording: a reader can always cut, and a
recorded cut cannot be undone. Consumers decide; the plugin resolves a
nested class exactly and falls back to the enclosing named class only
for anonymous and local classes.

Redefining the field is allowed here because it has never been in a
release: 0.1.0 and 0.2.0 have no `callSite*` fields (checked with
`jfr print` on their fixtures), and 0.3.0 is the first release that
has them. The rule that events and fields are never renamed, removed
or redefined applies to them from 0.3.0 on. No fourth field for the
simple name was added; `taskName` keeps the simple name for display.

The agent's `ForkAdvice` now stamps `callSiteClassName`,
`callSiteMethodName` and `callSiteLine` by the same rule as
`TracedScope`, because §1 says a recording does not reveal which
producer made it. Before, agent recordings had the fields (they belong
to core's event) but left them null, so the plugin fell back to
parsing `taskName`. Those recordings were never released either and
still parse, with no call site.

The frame formatting now exists in three copies: core's
`TaskNameDeriver`, the agent's `TaskNameDeriver` and its
`ScopeNameDeriver`. They stay separate here; one shared formatter is
#97. A shared class in core would add a public type to the API and
move code onto the bootstrap classloader, more than this change needs.
`ProducerCallSiteIT` runs one program under each producer and fails
if the two derivers stop agreeing.

2026-11-13 - TracedScope mirrors StructuredTaskScope's public API

`TracedScope` has `StructuredTaskScope`'s type parameters
(`<T, R, R_X extends Throwable>`), its four public static `open`
factories with a leading `String name` and otherwise the same
signatures, and every one of its public instance methods with the same
signature (`fork(Callable)`, `fork(Runnable)`, `join()`,
`isCancelled()`, `close()`). The only additions are
`fork(String, Callable)`, which names a task, and `name()`. A
reflection test compares the two on the running JDK and fails on a
missing or an extra public method. Decided by the maintainer on #95,
#87 and #80, and done in one change for all three.

Why: spec §1 calls `TracedScope` a drop-in wrapper, and it was not
one. `T` was fixed to `Object`, so typed joiners did not compile
without casts (`Joiner.<String>allSuccessfulOrThrow()` was rejected)
and the Javadoc examples needed `(Response) scope.join()`. JEP 543
finalizes the API in JDK 28 without further change, so this is the
shape 1.0 ships, and adding `T` after 0.3.0 would break every declared
`TracedScope` type a second time. A rule settles the API once, rather
than one overload at a time at each review.

What follows from it:

- `T` is added: `TracedScope<R, R_X>` becomes
  `TracedScope<T, R, R_X>`, and `fork`'s method type parameter becomes
  `<U extends T>`, as in the JDK.
- The two public constructors are deleted, not deprecated. They let a
  caller pick an `R_X` the compiler trusted while `join()` threw
  `ExecutionException` past it (an undeclared checked exception, #87).
  A deprecated constructor would keep that hole, there are no outside
  users yet, and 0.3.0 already breaks source compatibility.
- `open(String, UnaryOperator<Configuration>)` and
  `open(String, Joiner, UnaryOperator<Configuration>)` are added
  (#80): under the rule no JDK factory is missing.
- `open(String, ThreadFactory)` and `open(String, Joiner,
  ThreadFactory)` are removed. The JDK has no such factories, and next
  to `open(String, UnaryOperator)` they make `open("x", c -> c)`
  ambiguous, because both parameters are functional interfaces. The
  replacement is `open(name, cfg -> cfg.withThreadFactory(factory))`.
- The caller's configuration function runs after scope-tracer's own
  defaults, `withName(name)` and a virtual-thread factory, so whatever
  it sets is honoured as on a plain scope. Events keep the `name`
  argument as `scopeName` even if the function calls `withName`.
- `fork(Runnable)` and `isCancelled()` are added. `fork(Runnable)` is
  traced like `fork(Callable)`, call site included; `isCancelled()`
  only delegates.
- `fork(Runnable)` takes its name and call site from the calling frame
  even for a named `Runnable` class, where `fork(Callable)` would use
  the class. A plain scope's `fork(Runnable)` wraps the task in a JDK
  lambda before the agent's advice sees it, so the agent records the
  calling frame, and §1 requires the two producers to agree.
  `ProducerCallSiteIT` showed the difference (`NamedRunnable` from
  `TracedScope`, `ProducerCallSiteSubject#forkRunnables:124` from the
  agent) and now checks it. Changing the agent instead was out of
  scope here.

Two extras were kept rather than removed. `fork(String, Callable)` is
how a task gets an explicit name, which the report and the plugin
use. `name()` returns the `name` argument; removing it would break
callers for no gain in the mirror, since an extra method does not stop
`TracedScope` being a drop-in replacement. Both are named in the
reflection test, so any other extra fails it.

The `fork` methods now check for a null task before emitting
`TaskForked`, as the JDK's `fork` does. Before, a null `Callable`
emitted `TaskForked`, was wrapped, and failed inside the task, so
`join()` threw `ExecutionException` where a plain scope's `fork`
throws `NullPointerException`.
