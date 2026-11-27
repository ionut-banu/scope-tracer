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

`byte-buddy-agent` is dropped from the agent fat-jar, and from the
parent `<dependencyManagement>`, on 2026-11-13 (#96). Nothing used it:
`premain` hands the JVM-supplied `Instrumentation` to `AgentBuilder`,
which is in `byte-buddy`. It brought 330 `shaded/net/bytebuddy/agent/`
entries and two `attach_hotspot_windows.dll` files at the jar root,
on the bootstrap path, which the old `AgentIsolationIT` check missed
because it only looked at Java package roots. The check now rejects
every entry outside `META-INF/` and `com/ionutbanu/scopetracer/`.
Dynamic attach (#37) decides its own attach mechanism: `agentmain`
receives its `Instrumentation` from the JVM like `premain`, so an
attach tool is needed only on the side that starts the attach, not
in the agent jar.
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

2026-11-14 - The agent writes the report at exit, and records on its own with `record`

Decided by the maintainer on #56. Two changes to the agent, one
reason: a first-time user should get a report from one flag.

The report at exit. A `dumponexit` recording reaches `STOPPED` at JVM
exit, and the agent's `FlightRecorderListener` is called then, on
JFR's shutdown hook thread, after the file is written (measured on
Temurin 27.0.1). The listener used to start a new thread for the
report, and the JVM does not wait for a thread started during
shutdown, so no report appeared. It now writes synchronously when the
JVM is shutting down, detected by `Runtime.addShutdownHook` refusing,
which is decided before any hook runs. A flag set by a hook of the
agent's own was rejected: hooks run concurrently and JFR's was seen
both before and after another hook. The synchronous write is why exit
waits for the report (about 3 s for 10,000 scopes, 30 MB of HTML);
there is no timeout, by decision, until that number is shown to
matter. The report is written to a temporary file and moved into
place, so a halt mid-write leaves no partial `.html`.

`record` is opt-in. On by default, everyone who already drives JFR
with `jcmd` or `-XX:StartFlightRecording` would get a second recording
and two more files, and the agent cannot tell at `premain` whether
`-XX:StartFlightRecording` was given: no recording exists yet then.
One flag stays one flag: `=record` replaces the JFR option.

The default location is the working directory, as for
`-XX:StartFlightRecording` without `filename=`, and the name follows
JFR's `hotspot-pid-...` convention:
`scope-tracer-<pid>-<yyyy_MM_dd_HH_mm_ss>.jfr`. It is never written
over an existing file; an explicit `record=<path>` is replaced, as
JFR's `filename=` is. `maxsize` is 250 MB, JFR's own default, so a
long-running service cannot fill the disk; there is no argument for
it. `java.io.tmpdir` was the alternative, rejected as harder to find.

The behaviour change for 0.3.0 users - the README's own command now
leaves an `.html` at exit and waits for it - was accepted as the fix
the missing report always was, with `html=false` as the way back. The
plugin's "Run with Scope Tracer" passes `html=false`: it renders the
trace itself, and an unread report would only delay the IDE.

`premain` was also made to never throw, since an exception out of it
aborts JVM startup; every step now catches `Throwable` and warns.

2026-11-14 - A renamed agent jar puts itself on the bootstrap class path

Decided by the maintainer on #113. The fat-jar's `Boot-Class-Path`
names its own original file, which the JVM resolves beside the
`-javaagent` jar and silently skips when it is missing. A renamed
copy (`agent.jar`, a Dockerfile's `COPY`) was therefore loaded by the
application class loader, ByteBuddy still transformed
`StructuredTaskScope`, and every advice call died with a swallowed
`NoClassDefFoundError`: the program ran, `recording to` was printed,
and the `.jfr` was empty.

The agent now fixes it rather than only warning. Renaming a jar is
what build scripts and copy-pastes do, and M4 is "try it in one
minute"; a warning would still have left the first-time user with a
second step. `Boot-Class-Path` stays, so the original name behaves
exactly as before, with no new line. When `ScopeTracerAgent` is not
loaded by the bootstrap loader, its first `premain` appends the jar
found from its code source (not from `-javaagent:` in the JVM's
input arguments, which would load `java.management`) and calls
`premain` again on the bootstrap copy. The first copy does nothing
else: its classes are not the ones the advice sees, and parsing the
arguments there would print every warning twice. It loads `AgentLog`
before the append, because afterwards the application loader would
find the bootstrap copy, whose package-private methods it cannot
call.

The append alone was not enough. `Advice.to(type)` reads the advice
bytes through the class's own loader, and the bootstrap loader serves
no resources from a jar appended at runtime, so the transformation
failed silently. The four `Advice.to` calls now look in the advice
class's loader and then the system class loader, which has every
`-javaagent` jar on its path. A locator over the agent's own file was
the alternative; the bootstrap copy does not know that file's path.

The cost is one line HotSpot prints in the renamed case, `Sharing is
only supported for boot loader classes because bootstrap classpath
has been appended`. The agent cannot suppress it, start-up was not
measurably slower (281 vs 286 ms, median of 7), and it does not touch
what the traced code returns, throws or cancels, which is what
"tracing never changes behaviour" protects. It is accepted and
documented, with the original name or `-Xbootclasspath/a:` as the way
to avoid it. The agent prints one `[scope-tracer]` info line when it
appends, so the JVM's line does not appear unexplained.

2026-11-15 - Agent arguments that do nothing are warned about, not refused (#114)

`-javaagent:...=recrod` used to give a run that looked fine and
recorded nothing, `verbose=false` turned verbose on and `record=false`
wrote a file called `false`. An argument is now either understood or
named in one WARN line, and the agent carries on with the rest. It
does not stop: an exception out of `premain` aborts the JVM, and a
typo in an optional key should not cost the whole trace when the
warning already makes it visible. There is no strict mode.

Keys stay exact. Accepting any case would make a second spelling of
every key permanent, and the unknown-key line lists the known keys,
built from the one list the parser's `switch` also uses, so the fix is
on the same line and adding a key cannot leave the list stale.
`record`, `html` and `verbose` are booleans, ignoring case, the bare
key meaning true; `record=false` is as if absent and silent, so a
build script can write `record=${flag}`, and a file called `true` or
`false` is `record=./true`. A bad boolean keeps the default.
Out-of-range numbers warn and keep the clamped result they always had;
`sample.rate=NaN` is malformed, because NaN survived the clamp and
dropped every scope. Warnings escape control characters and cut a
value at 100 characters, so one is always one line. The unknown-key
line names the key, not the whole token, so a path in the value is not
repeated. Since #113 parsing runs only in the bootstrap copy of the
agent, so each warning appears once, also under a renamed jar.

2026-11-15 - Under a renamed jar, advice bytes come from the agent's own jar (#123)

#113 made the `Advice.to` calls read the advice bytes from the advice
class's loader and then the system class loader. Under a renamed jar
the first finds nothing, so the system class loader answered, and it
searches `-cp` first. QA found that a `-cp` jar with its own
`ScopeOpenAdvice` was inlined into `StructuredTaskScope.open`: its
body ran, and nothing was traced. Grooming measured worse: with an
advice method of another name or descriptor, ByteBuddy takes the
method from the loaded class and the code from the located bytes,
and the JDK rejects the transformed class (`ClassFormatError:
Illegal exception table range`). The program's first `open` throws
and it exits 1. `suppress = Throwable.class` cannot help, because the
class file itself is broken before any advice runs. That breaks
"tracing never changes behaviour".

The #113 alternative, a locator over the agent's own file, is now
taken. It was rejected because the bootstrap copy did not know that
file's path. `BootstrapEntry` does know it, and now hands it over as
an argument of a package-private method of the bootstrap copy,
reached by reflection (both copies are in unnamed modules). No system
property and no public method: nothing new reaches the application.
The second locator under a renamed jar is
`ClassFileLocator.ForJarFile` on that jar. Under the original name
and with `-Xbootclasspath/a:` the bootstrap loader serves the bytes
from the startup boot path, ahead of `-cp`, and the locator is as
before.

If the jar cannot be read when the locator is built, the agent does
not trace: one `WARN not tracing` line naming the jar and the reason.
Falling back to the system class loader would reopen exactly this
hole, and a run that traces nothing with a warning is better than one
that inlines unknown bytes (maintainer, open question 1).

The four `Advice` objects are built once in `premain`, before
`installOn`, and the jar is closed after it (open question 2).
`StructuredTaskScope` loads after `premain` returns, so with
`Advice.to` inside the transformers, closing the jar recorded zero
events with no warning; `Advice.to` reads the bytes when called and
keeps them. A broken advice class now fails at startup with the
existing `instrumentation failed` warning instead of silently at the
first `open`. Under the original name this changes when the bytes are
read, not which, nor anything printed or recorded.

A whole scope-tracer jar on `-cp` under a renamed jar is not covered:
the JVM loads `Premain-Class` through the system class loader, so that
jar's agent runs instead. It needs its own design (#128).

2026-11-15 - A scope class the agent cannot transform is named on one line (#121)

When ByteBuddy could not apply the advice to `StructuredTaskScope`
or `StructuredTaskScopeImpl`, the agent said nothing unless `verbose`
was set: the program ran, and the recording was empty or held only
`ScopeOpened`. That was the one failure in §3's "each step ... logs
one warning line" that did not. A changed JDK is the likely cause,
which is what M3 watches for.

The line is printed with or without `verbose` (maintainer, open
question 3). It is a `warn`, not a debug line: a trace that will be
incomplete is the thing a user needs to know, and under `verbose`
it is the greppable `[scope-tracer]` line next to ByteBuddy's own
report and stack trace, which it does not replace. Its text is
`WARN cannot instrument <class>, the trace will be incomplete:
<reason>` (question 4), not `not tracing`, because when only
`StructuredTaskScopeImpl` fails the scopes are still recorded as
opened.

One line per class, at most once per JVM (question 2). The two
classes fail independently and lose different events, so the name
says which. ByteBuddy reports a class again on every retransformation
(three reports measured with a later agent retransforming twice), so
a listener that printed every report would repeat itself. The state
is one flag per class name in one shared listener; it keeps no
`Class` or `ClassLoader`.

Not covered: a class that loads but in which no advised method
matches (#130), and a transformed class the JVM rejects (#131).
ByteBuddy reports no error for either, so a listener cannot see them.

Closing the jar locator after `installOn` (#123) could throw and
make the step print `not tracing: instrumentation failed` while
tracing was on, or hide the exception that did stop it. A failing
close is now ignored and prints nothing (question 1): it loses one
file handle and nothing else.

## 2026-11-15 - The agent's DEBUG lines follow the verbose argument (#126)

The two `skipping auto-HTML` lines were gated on
`-Dscopetracer.agent.verbose` alone, read once into a constant,
while the ByteBuddy listing followed the parsed `verbose` value
since #114. So `verbose` printed no skip line and `verbose=false`
did not silence it: one switch with two precedence rules, which is
what #114 set out to remove. The lines now follow the parsed value;
the property is the default when the argument is absent or
malformed, and until `start` sets the gate.

They stay verbose-only, not `info` (maintainer): a recording with
`-XX:StartFlightRecording` in a program that opens no scope reaches
the line, and as `info` it would appear for every unrelated
recording. A recording with no scopes now says `no scopes recorded
in <file>`, since `0 scope(s) is below min.scopes=1` reads as a
threshold problem when nothing was recorded. The gate is set in the
bootstrap copy's `start` and is volatile, because the html-gen and
shutdown-hook threads read it. `-Dscopetracer.agent.verbose` stays.

## 2026-11-15 - A WARN reason is the cause chain, one bounded escaped line (#132)

The agent's WARN lines named only the top exception, so a real
`installOn` failure printed ByteBuddy's wrapper (`Could not install
class file transformer`) and dropped the JDK's reason, and a
throwable with an empty simple name or message left the line ending
in `: ` or nothing. Two formatters existed, one a copy in
`BootstrapEntry`, and neither escaped anything but `\n` and `\r`.

One formatter, `AgentLog.describe`, serves all ten lines. It lives in
`AgentLog` because `BootstrapEntry`, which runs from the application
class loader's copy, already pre-loads it before appending the jar,
so no second class crosses the two copies. Maintainer decisions
(2026-11-15): the format is `Type: message; caused by Type: message`,
as Java's own `Caused by:` reads; at most four entries, then `; caused
by ...` (printing only the root would lose the useful middle, such as
`UncheckedIOException: reading t.jfr`); a message equal to the cause's
`toString()` is not printed twice; 300 characters per type or
message, because real `FileSystemException` messages run to about 170
and keep their reason at the end, which #114's 100 would cut; `\n` is
written as `\n` like `AgentConfig.shown` instead of a space; the
binary name is shown only when the simple name is empty, so a line
without a cause is byte-identical to before. Suppressed exceptions
and stack traces are never printed, and the unused
`AgentLog.warn(String, Throwable)` is deleted. Paths in WARN
prefixes are escaped and never cut, because a cut path is useless;
INFO lines keep printing paths raw, as users copy them (#135).

The JDK's answer differs from what grooming expected in one place:
with `--limit-modules java.base,java.instrument`, the lines for
`jdk/jfr/Recording` and `FlightRecorderListener` have no cause. The
agent's classes are on the bootstrap loader, and a class that loader
cannot find is a `NoClassDefFoundError` with no `ClassNotFoundException`
under it; a probe loaded by the application loader sees one. Those
lines stay as they were.

## 2026-11-17 - The CLI's diagnostics are behind --verbose; Logback is optional (#58)

The `-executable` jar bundled Logback with no configuration, so its
root level was DEBUG and its output went to stdout in the developer
format. The one WARN left in `JfrParser` reached stdout with a
timestamp, `[main]` and the logger class, ahead of the report line,
and any later `log.debug` would have printed on every run. Its text
said the file "may have been cut short" when the scope had only been
open at dump time. And `logback-classic` was a compile dependency of
the analyzer library, so a host with its own binding got SLF4J's
"multiple providers" warning and Logback took over its logging.

Maintainer decisions (2026-11-17), all six recommendations accepted:
a `--verbose` flag is the only switch, with no system property or
environment variable, since a flag shows in the usage, matches the
agent's `verbose`, and the only programmatic caller (the plugin) can
edit its argument list; the user-facing lines are printed by
`AnalyzerMain` from the `TraceModel` and the library's SLF4J output is
hidden by default, because showing library WARNs would make every
future log line a user message whether or not it is phrased as one
(under `--verbose` the incomplete warning appears twice, accepted);
the empty-recording warning is part of this, being the likeliest
first-run mistake; `--verbose` gets one DEBUG summary per parse, as
it would otherwise have nothing to show on a healthy recording;
options are accepted in any position, a strict relaxation; and
library users who got Logback through the analyzer lose it, noted in
the CHANGELOG, because choosing the binding is the host's job.

How: Logback is `<optional>`, so Shade still bundles it into the
`-executable` jar, and the CLI's `logback.xml` lives in `src/cli/`
and is added to that jar alone by an `IncludeResourceTransformer`.
A `logback.xml` in the plain jar would configure a library user's
Logback. The file reads its root level from a property that
`AnalyzerMain.main` sets every time, before the first logger is
created, rather than configuring Logback through its API: `AnalyzerMain`
is in the plain jar, whose classes must load without Logback. Because
`main` always overwrites the property, `-D` cannot turn diagnostics
on. An argument starting with `-` (other than `-` alone) is now an
option, so an input path starting with `-` is written `./-x.jfr`.
Exit codes stay as they were, including the two that disagree with
the Javadoc (#138).

## 2026-11-17 - Logback's own properties and orphan-only recordings (#58)

QA found that the previous entry's "`-D` cannot turn diagnostics on"
held only for scope-tracer's property. `logback.configurationFile`
replaced the jar's configuration and logged at DEBUG on stdout, and
`logback.debug` or `logback.statusListenerClass` printed Logback's
status lines on stdout, by `-D` or through `JAVA_TOOL_OPTIONS`.
Maintainer decision: fix the CLI rather than narrow the claim.
`AnalyzerMain.main` now removes every `logback.*` system property
before the first logger exists. Removing the whole prefix, not the
three named, also covers the ones nobody tried; the CLI has no use
for any of them. The variable `LOGBACK_CONFIGURATION_FILE` is not
read by this Logback, as QA measured.

A recording whose scope-tracer events all lack their `ScopeOpened`
(a recording started while scopes were open) was told it "contains
no scope-tracer events", which is false. It now gets its own line,
`holds N scope-tracer events but no ScopeOpened event`, with
wording set in the issue. The count comes from a second
`RecordingFile` pass that runs only when the model has no scopes, so
`TraceModel`, `TraceModelJson` and `JfrParser.parse` are unchanged
and a normal run reads the file once. A model with scopes plus
orphan events gets no orphan line (#68).

Accepted as a consequence of options in any position plus "unknown
option exits 1": a bare `-x.jfr` input now exits 1 (write
`./-x.jfr`), and `--help extra` exits 1 instead of 2. Both are in
the CHANGELOG.

## 2026-11-17 - The orphan count comes from the first read (#142)

#58 counted the six event types in a second `RecordingFile` pass
after the report was written. PM measured (#142, Temurin 27.0.1)
that a recording changed in that window - the CLI's stdout a pipe
filled to 64 KiB, so the `Report written` line blocks - turned a
complete report into exit 3: truncated to 0 bytes, overwritten with
text and deleted all gave `I/O error`, with the 4429-byte report on
disk. Maintainer decision: take the count from the read that builds
the model. `JfrParser.parseCounting`, package-private, returns the
model and the count of the six known types from the per-type
counter the DEBUG line already used; `parse(Path)` delegates to it
and is unchanged, as are `TraceModel` and `TraceModelJson`, so the
plugin is untouched. Nothing is read after `Report written`, and
there is no "warning without N" fallback, which would be dead code.
The catch clauses and exit codes are #138's; the rule that nothing
after `Report written` may change the exit code is recorded there.

## 2026-11-17 - Exit codes: correct the docs, not the codes (#138)

The `AnalyzerMain` Javadoc said 3 was an I/O error and 4 "not a
valid JFR recording", and that 2 was a missing input. The CLI
exits 3 for a non-JFR file and 2 for an output in a missing
directory. PM measured (Temurin 27.0.1) that `v0.3.0` and `main`
give identical codes and first stderr lines for 14 command lines,
so 0.3.0 users already see them. Maintainer decision: keep the
codes, correct the Javadoc, spec section 3 and the README, add no
sixth code for "not a JFR recording", and promise the codes stable
from 0.4.0 (a change needs a decisions entry and a CHANGELOG
"Changed" line).

Why not change the codes: a non-JFR file as 4 needs classifying
`IOException`s by message text, since the JDK has no type for it
and truncation shows up as both 3 and 4; a missing output directory
as 3 changes a code a script may test, and `file not found` is
true. The plugin tests only non-zero. A script treats 3 and 4 alike
as "could not read the recording".

One code change: the warnings are computed after the try whose
catch clauses return 2, 3 and 4, through a package-private seam,
so a failure there cannot change the exit code (#142's rule). The
catch clauses are untouched.

## 2026-11-18 - The demos module has unit tests (#106)

The demos module has unit tests since #106 (`DemoJarsTest`), with
test-scoped junit-jupiter and assertj. Both are managed in the
parent's `<dependencyManagement>`, so no new dependency. This
supersedes #139's "no tests or test dependencies in the demos
module" for the demos module as a whole: #139's rule held for the
demos' logging setup, and the module may now carry tests.

## 2026-11-18 - The release docs check runs in `release.yml` only (#105)

0.2.0 shipped a README that said `<version>0.1.0</version>` and a
`docs/maven-central-setup.md` that said `scope-tracer-core:0.1.0`.
`.github/scripts/check-release-docs.sh X.Y.Z` now fails the release
when a version in README, CONTRIBUTING, `docs/*.md`, `docs/index.html`
or `jbang-catalog.json` is not X.Y.Z, or when the CHANGELOG heading
and link or spec section 5 `Released:` lack it. It runs in the `check`
job of `release.yml`, which `publish` needs, and by hand in
CONTRIBUTING step 2.

Release only, no `main` or PR check: on `main` the POM is a SNAPSHOT
and the README rightly names the last release, so there is nothing to
compare; the release PR opens after the tag is pushed, so a PR check
comes after the point of no return (Central cannot be un-published).
Spec section 4 test counts are not checked: they are a dated
snapshot that changes with every merged issue, and comparing them
needs `verify`, the stress profile and `:model`. README prose such as
the JDK sentence is not checked either. A qualifier tag
(`v0.4.0-rc1`) exits 2; there has never been one. The plugin's
`pluginVersion` and a jar manifest check are not part of it (#151).
`check_only` on `workflow_dispatch` runs the check job alone, the
only way to see it on a real runner without publishing. The `check`
job checks out the dispatched ref (the script) and the tag into
`tagged/` (the docs and POM), so a dry run from `main` can check a tag
that predates the script; on a tag push the two are the same tree.
The script takes the docs root as a second argument and strips CR, so
a CRLF checkout is not a false "Released:" error.

## 2026-11-18 - The -javaagent jar is the last copy the system loader lists (#128)

Under a renamed `-javaagent` jar the JVM loads `Premain-Class`
through the system class loader, which searches `-cp` first, so a
scope-tracer jar on `-cp` supplied the running `ScopeTracerAgent`
and `BootstrapEntry` appended its jar. Two failures followed: the
thin jar aborted the JVM (exit 134) in `getDeclaredMethod("premain")`,
which resolves every method signature of the class and four named
ByteBuddy types; and once past that, the thin jar was appended,
where the bootstrap loader cannot see ByteBuddy, so nothing was
traced. Another fat-jar copy on `-cp` traced, but from itself.

Decided: every ByteBuddy use moves to a nested class of
`ScopeTracerAgent`, with a unit test on its declared methods' types;
and `BootstrapEntry` appends the last copy of `ScopeTracerAgent.class`
that `ClassLoader.getSystemResources` lists. The JVM appends the
`-javaagent` jar after the `-cp` entries and their `Class-Path` jars,
and measured on Temurin 27.0.1 it was last for a renamed jar, a
symbolic link, a relative path, `JAVA_TOOL_OPTIONS` and a `-cp`
wildcard. It needs no JVM arguments and no `java.management`. The
JVM does not append a jar already on the path, so when the
`-javaagent` jar is also on `-cp` the last copy is a `-cp` jar.
When the last copy is an entry of `java.class.path` and not the
running copy, the agent refuses with one `WARN not tracing` line.
That covers the `-javaagent` jar on `-cp` together with any other
scope-tracer jar, in either order (`a:agent:b`, `agent:thin`,
`thin:agent`), and the rename hint names the file from the running
jar's manifest, or the last copy's. A second layout the rule does
not read, found by QA: a `-cp` jar whose manifest `Class-Path` lists
the `-javaagent` jar and then another scope-tracer jar. The last
copy is that other jar, which is not a `java.class.path` entry, so
the agent chooses it. Known and not handled here; #158 is the
follow-up. A wrong guess would instrument with the wrong jar,
which is the bug; the maintainer chose to refuse. When the copies
cannot be listed or read, the running copy's jar is used, as before.

Measured and not adopted: the running class's `CodeSource` (the bug
itself); `Launcher-Agent` (read only for `java -jar`, not for
`-javaagent`); a `Premain-Class` unique per jar (the jar is renamed
after it is built, and the failing pairs are a thin and a fat jar of
one version, with the same class names); `ProcessHandle` arguments
(no `JAVA_TOOL_OPTIONS` or `@argfiles`, can be empty in a container,
and the JVM-arguments route #113 ruled out); making the class
loadable alone (no abort, but the thin rows do not trace and the
fat-jar row traces from the wrong jar; it is what the design falls
back to). A 0.3.x or earlier scope-tracer jar on `-cp` still takes
over, because its own `premain` runs: documented, not fixed. The
new `ignoring` info line is kept, printed only when the agent passes
over the running copy.

## 2026-11-18 - The gallery is generated, by TracedScope, and checked in CI (#59)

`docs/gallery/` shows what each join policy does as real reports.
Decided with the maintainer: `TracedScope` produces them (every task
can be named; the agent labels lambdas `Class#method:line` and needs a
JVM per scenario; the same shapes were measured under both); the page
is generated from a template resource plus per-scenario text, with
snippets cut from the scenarios' source between `gallery:begin` and
`gallery:end` markers, so a snippet cannot differ from the code that
ran; one file per report in a lazy `<iframe src>` (a report is a whole
document with global CSS and element ids, so inlining breaks it).
`GalleryMain` refuses a recording whose shape differs from the one the
scenario declares (retried at most 3 times, then exit 1, nothing
written): decisive events are at least 150 ms after open and 100 ms
apart, so a descheduled main thread cannot reorder them. The one
exception is ignores-interrupt, whose failure is at about 60 ms: its
criterion wants the failure and the cancelled sibling within 100 ms of
the start, and a main thread stalled more than about 60 ms between two
forks can leave `stubborn` unforked, which the gate rejects and retries. `--check`
compares each report after dropping times, thread names, SVG, JDK
frames and the order of sibling child scopes; the critical-path note
stays as a placeholder (it must be present, on the same row), except
in order-processing, where two tasks of a scope can finish within
milliseconds and the note may sit on either row, so only its presence
per scope is compared;
it is a CI step and not part of `verify`, so a timing flake cannot
fail the build. `OrderProcessingDemo` draws from generators seeded
from order id and step, so ORD-002 is always the declined order and
`#scope-2` on the landing page always opens on it. The joiner's name
on `ScopeOpened` is out of scope (#156).

## 2026-11-19 - README leads with a report and a one-command trial (#60)

The README's first element is a screenshot of the gallery's fail-fast
report, not a new illustration: it is the real renderer's output, the
report already says what the tool is, and a gallery report cannot
drift from the renderer silently. A PNG cannot be compared byte for
byte (times and thread names change each run), so
`readme-image.sh` writes the SHA-256 of the report it was shot from
beside the PNG and `--check`, which needs no Chrome, runs in CI
beside `GalleryMain --check`: regenerating the gallery without
shooting the image again fails the pull request. The order-processing
report was not used: shot at `#scope-2` it is blank in headless Chrome.

The quick start is a self-contained trial (a `Trial.java` heredoc with
a succeeding, a failing and a slow task, run under
`-javaagent:...=record`), not `<your-classpath> com.example.MyApp`:
it is the only form that runs as written and ends in a report in
seconds, with nothing cloned and no Maven. The own-program form
follows it. The landing page carries the same block, and
`check-release-docs.sh` checks that both files hold the `=record`
command at the release version. The quick start is for a POSIX shell;
Windows is untested and says so. The README's license badge became
plain text, so nothing in the first screen depends on shields.io
reaching GitHub.

The order is quick start, what the report shows, `TracedScope`, the
plugin, reference, contributing: the agent leads because spec section 1
makes the agent and the IDE the primary experience, `TracedScope`
follows, and building from source is for contributors, so it comes
last. The agent and analyzer reference stays in the README (under
"Reference") rather than moving to `docs/*.md`; the "Demos" heading
keeps its name because `AGENTS.md` and `CONTRIBUTING.md` cite it.

## 2026-11-19 - The Central bundle is checked, and demos opt out with skipPublishing (#162)

`scope-tracer-demos` 0.3.0 and 0.4.0 reached Maven Central although its pom said it should not.
The pom set `maven.deploy.skip`, which belongs to maven-deploy-plugin and is ignored because the
central-publishing extension replaces that plugin, and `central.publishing.skip`, which is not a
parameter of the plugin at all (absent from the 0.12.0 `plugin.xml`). The extension stages every
reactor module's artifacts. The stress tests were safe only by accident: they are a `-Pstress`
module, so `mvn -P release deploy` never had them in the reactor, and `-P release,stress` would
have bundled them.

The parameter the plugin reads is `skipPublishing`, so both poms set that property. Central never
deletes a release, so the old demos artifacts stay; the README does not mention them (nobody
links them), and the CHANGELOG says they were published by mistake and are unsupported.

A property that nothing reads fails silently, so the release also checks the result.
`.github/scripts/check-release-bundle.sh` fails when the bundle holds an artifactId other than
the parent, core, analyzer and agent, or lacks one of them. It runs in the `check` job, on every
tag push and on `check_only`, after a `mvn -P release deploy -DskipUpload=true` of the tag that
needs no credentials and no signing key. It cannot run in `publish`: the extension builds and
uploads in one `deploy`, so a check there would need a second build after the upload. It is not a
pull request check: on `main` the version is a SNAPSHOT and the plugin stages nothing. Local runs
of the release profile install into the local repository, so they use a scratch
`-Dmaven.repo.local`.

## 2026-11-19 - JBang catalog: pinned aliases, a demo that relaunches itself (#57)

`jbang-catalog.json` at the repository root has two aliases, `analyze`
and `demo`, so `jbang demo@ionut-banu/scope-tracer` runs without a
clone or a build. Decided with the maintainer: the aliases name the
latest release and move only in the release commit (a bare
`group:artifact` would move under users, and `main` must never name an
artifact that is not on Central); `check-release-docs.sh` requires the
catalog and the demo source and a literal version on every scope-tracer
coordinate in them. Both need JDK 27+, the demo with `--enable-preview`;
JBang downloads JDK 27 itself when the machine has none (measured with
JBang 0.143.0: 296 MB, 12 s, into JBang's own cache). The demo is one
source file using only the JDK, so no change to `TracedScope` or the
analyzer can break it.

Measured with a real JBang 0.143.0 run, against what the issue assumed:
the alias key `java-agents` is read from the catalog but ignored when
the alias runs (no `-javaagent` reaches the JVM, with a GAV, a path or
a URL), and `//JAVAAGENT` in a source does not attach an external agent
(it marks the file itself as an agent and splits its value on `=`, so
`record=<path>` cannot be written either). Only the CLI flag
`--javaagent` works, and an alias cannot add CLI flags. So the demo has
`//DEPS com.ionutbanu:scope-tracer-agent:X.Y.Z:agent`, which puts the
agent jar on its class path, and starts itself a second time in a
child JVM with `-javaagent:<that jar>=record=scope-tracer-demo.jfr`
(scope-tracer's own jars left off the child's class path), which keeps
the fixed file name. The `analyze` alias takes the release asset URL,
not a Maven coordinate: JBang adds the pom's dependencies (`scope-tracer-core`
and a second `slf4j-api`) after the already self-contained
`-executable` jar. Cost: JBang asks the user once to trust the
`github.com/.../releases/download/` URL for `analyze`.

## 2026-11-20 - A signal during agent start-up can hang JDK 26+; documented, not fixed (#167)

QA found that a JVM with the agent attached that gets SIGTERM in its first ~200 ms
can hang until `kill -9`. Measured on macOS arm64 (Temurin 27.0.1, Temurin 26+35,
Azul Zulu 21.0.11). It is a JDK bug: the thread that exits the JVM sits in
`JVM_Halt` -> `before_exit` -> `JvmtiExport::post_vm_death` waiting for the JVMTI
callback that is `premain`, and `premain`, defining a class, waits for a
deoptimization handshake that needs the exiting thread. A standalone agent with no
scope-tracer code that loads many `java.base` classes hangs the same way. JDK 26
is affected; a JDK-only reproducer did not hang on Azul Zulu 21 (the agent itself
needs JDK 27, so it was not run there). Only SIGTERM was measured.

Not what the issue first assumed: the agent retransforms nothing at `premain`
today (877 classes loaded, neither `StructuredTaskScope` nor its impl among them;
`REDEFINE COMPLETE 0 batch(es)`), so narrowing the retransformation does nothing.
What matters is how long `premain` keeps loading classes.

Decision: document the limit (README agent reference, spec section 3, the
`ScopeTracerAgent` Javadoc, CHANGELOG known issues), pin it with an IT, and do not
change the agent. Wording says "JDK 26 and later", not "27.0.1 only". The bug id
is a placeholder (`JDK-XXXXXXX`) until the maintainer gets one from OpenJDK.
The occasional hangs a signal at 250-300 ms showed are not understood and are
left out of the wording.

Prototyped and rejected, all from `main`: (1) lazy install - `premain` only
registers a transformer and ByteBuddy is built on the first `StructuredTaskScope`
load: the hang moves to that load, `record` still hangs in `premain`, and an
already-loaded `StructuredTaskScope` is missed; (2) installing on a background
thread with the transformer waiting for it: still hangs; (3) swallowing SIGTERM,
SIGINT and SIGHUP with `sun.misc.Signal` during `premain`, then re-raising: the
re-raised exit races the launcher's main-class load and hangs. A
`java.lang.classfile` rewrite without ByteBuddy was not tried; it is a redesign.
Revisit if OpenJDK answers "won't fix".

The IT is differential, because a signal sent after a delay is a race and
`Thread.sleep` is not allowed. A test-only gate agent, first on the command line,
blocks in a native `System.in.read()` at the first load of a class with the real
agent's package prefix. That load happens while the agent's `Premain-Class` is
loaded, just before `premain` is entered, not inside it. The test is still valid:
the JVM is paused in the agent's start-up with the JVMTI start-up callbacks
running, and the unmodified agent hung 22 of 22 gated runs. A gate in the gate
agent's own `premain` does not work, since a second agent is never started once
shutdown has begun. The test
sends SIGTERM, waits for the gate's shutdown hook to print `GATE-HOOK`, and
releases the gate. A control child (gate plus an agent that loads many
`java.base` classes) says whether this JDK has the bug: if the control exits, the real agent
must exit within 15 s; if the control hangs, the JDK is at fault and the test
passes, printing `JDK shutdown hang present: <java.version>`. A red build that
cannot be fixed would be `@Disabled` in another form.

## 2026-11-22 - Release docs guards ignore what a reader cannot see; the PNG is pinned (#157)

Found by QA on #60 and measured on `main` at 9d588e1: six guards passed for the wrong reason.
A download URL or a README snippet inside an HTML comment satisfied "is it present"; a deleted
`docs/index.html` was skipped; the README half of the `=record` guard was satisfied by a
different line ("Trace your own program"); an emptied PNG matched its sidecar, which hashed only
the report; an upper-case hex sidecar failed with a misleading "report changed".

Decided: "is it present" guards read the page with HTML comments stripped (a small awk state
machine, one output line per input line); the stale-version scans keep the raw file, so a stale
version in a comment still fails. `docs/index.html` is required. The quick start block (first
bash fence under `## Quick start`) and the trial block (first `<pre>`, entities decoded) are
found by position, not by a marker comment, must each carry the `=record` command, and must be
equal; a renamed heading fails loudly with its own message. The sidecar
`docs/img/readme-report.source` is two `sha256sum`-format rows (report, PNG), found by path and
compared in lower case; an empty or malformed one says "no valid SHA-256 row", not "changed".
Rejected: a PNG signature check (the hash covers empty, truncated and edited files), a marker
comment, an `href` requirement (the real page names the URL in plain text in a `<pre>`), and
making `docs/*.md` a fixed list (those files carry no version a reader copies). The committed
sidecar was migrated without re-shooting the PNG.

## 2026-11-22 - A last copy reachable through a manifest Class-Path refuses (#158)

This supersedes the 2026-11-18 entry's "Known and not handled here;
#158 is the follow-up." The layout is now handled.

The #128 rule, "the last copy of `ScopeTracerAgent.class` is the
`-javaagent` jar unless it is a `java.class.path` entry", rested on
the JVM appending the `-javaagent` jar. Measured on Temurin 27.0.1,
it does not append a jar that is already reachable through a `-cp`
jar's manifest `Class-Path` either. With `Class-Path: agent.jar
thin.jar` the system loader lists exactly `[agent.jar, thin.jar]`:
the last copy is the thin jar, which is not an entry, so the agent
printed `ignoring agent.jar ... the -javaagent jar is thin.jar`,
appended the thin jar and failed with `NoClassDefFoundError`; with
another fat-jar in place of the thin one, it traced from that jar.

Decided: only when the last copy is not the running copy, and is
not a `-cp` entry, `BootstrapEntry.chooseJar` expands
`java.class.path` through every jar's manifest `Class-Path`, breadth
first, each file once by real path, with the JVM's resolution rules
(space-separated URLs relative to the listing jar; `./`, `../`,
absolute, `file:` and `%20` resolve; a raw space, a backslash, a
directory, `*` and `http:` find nothing; a missing entry is
skipped). A reachable last copy refuses with the existing `WARN not
tracing` line plus `(listed by the Class-Path of <jar>)`; an
unreachable one was appended by the JVM, so it is the `-javaagent`
jar, as before. Comparing positions alone cannot work: `[agent,
thin]` and `[thin, agent]` look the same whichever jar is the
`-javaagent` one.

The maintainer's three answers on the issue: `Class-Path: thin.jar
agent.jar`, which traced by luck, refuses, as `-cp
thin.jar:agent.jar` does since #128; the expansion has no size or
depth cap, because each file is read once, errors are swallowed per
entry, and it runs only in the layout that would otherwise be wrong;
and the refusal names the listing jar, so the fix is findable in a
chain. A failure of the walk as a whole keeps the pre-#158 answer
(the last copy). The common case, a renamed jar with no other
scope-tracer copy, reads no manifest, so #167's start-up window does
not widen: with a `-cp` jar listing 400 real jars from `~/.m2`, 11
runs each, the median was 379 ms under the original name, 374 ms
renamed, and 422 ms in a layout that forced the expansion.

## 2026-11-23 - Class-Path entries are resolved with java.net.URL, as the JVM does (#158)

QA on #158 found that the 2026-11-22 resolver, built on
`new URI(entry)` and `Path.of(uri)`, dropped entries the JVM follows.
Measured on Temurin 27.0.1 with a probe that prints
`getSystemResources`: `file:thin.jar` and `FILE:thin.jar` (relative
to the listing jar), names with `^ [ ] { }`, `t.jar#frag` (the
fragment is dropped) and `t.jar?q` (a file of that name). In those
layouts the agent still printed the swapped `ignoring` line, or
traced from another fat-jar. `jarOf` and the running jar's code
source had the same hole. A running jar at `odd/a^b.jar` refused
with `URISyntaxException` and the generic file name, a #128 bug.

Decided by the maintainer: each entry is `new URL(<listing jar's
URL>, entry)`, as `URLClassPath` does, and a `file:` URL is decoded
to a path the way `ParseUtil.decode` does (`%XX` as UTF-8, the
query kept, the fragment dropped, a `file:` URL on another host
naming nothing on Unix). The copies' `jar:file:` URLs and the code
source go through the same decoding. An entry that still cannot be
resolved, such as `%00` (rejected by `Path.of`) or a malformed
escape, counts as reachable, so the agent refuses rather than
guesses. The JVM skips those entries, so this is a deliberate
over-refusal for a manifest nobody writes on purpose. A jar that
lists the copy for certain is named in the refusal ahead of one with
an unresolvable entry.

Accepted over-refusal: copies are compared by real path, but the JVM
drops a duplicate only when its URL string is the same. With
`Class-Path: thin.jar /tmp/.../agent.jar` and the same jar as
`-javaagent` through `/private/tmp`, the JVM appends it again and
main traced. The agent now refuses. That is conservative and
consistent with refusing `thin.jar agent.jar`, and the spec says so.

## 2026-11-23: neutral wording for the unfiled JDK bug ID (#173)

The `JDK-XXXXXXX` placeholder is replaced with "reported to OpenJDK; no
bug ID yet" in the README, CHANGELOG, spec, `ScopeTracerAgent` Javadoc and
`AgentStartupSignalIT`, for the 0.5.0 release. The real ID will be added
once OpenJDK assigns one.

## 2026-11-23 - The JDK 28 early-access build runs without --enable-preview (#55)

A weekly job builds and tests scope-tracer on the newest JDK 28 EA
build, to catch a JDK change that stops the agent's matchers from
matching months before 28 GA. It runs under an explicit `-Pjdk-ea`
profile that targets the running JDK (`--release 28`) and passes
`--enable-preview` nowhere: not to javac, not in the surefire and
failsafe `argLine`s, not to the child JVMs and javac runs the tests
start (they read `scopetracer.test.preview`, set by the POM).

Why not keep the flag, measured on OpenJDK 28-ea+25 while grooming:
javac 28 cannot target 27 with `--enable-preview`, and classes built
as 71.65535 do not load on 28. With `--release 28 --enable-preview`,
value classes are in preview, so javac stamps every class that
touches `Record`, `Optional` or a box type 72.65535 (on 27 only the
classes that use `StructuredTaskScope` are stamped). ASM 9.11.1, the
newest, rejects 72.65535, so Shade and SpotBugs fail and no agent IT
runs. On 28 `StructuredTaskScope` is final (JEP 543) and needs no
flag. Without the flag everywhere, `verify` passes with the same 878
tests as on 27.

Decided by the maintainer: the AGENTS.md rule that `--enable-preview`
is never disabled gains this one exception. The profile is explicit
only, never activated by the JDK version, so a release cannot be cut
by accident without the flag; automatic activation belongs with #70,
when 28 becomes the default. The profile's own enforcer rule refuses
a JDK older than 28, so it never runs tests against 27 without the
preview flag. Temurin `28-ea` through `setup-java@v5`, weekly on
Saturday 06:17 UTC plus `workflow_dispatch` and pull requests that
change the workflow file. A red run is reported by GitHub's cron
mail and the step summary; nothing opens an issue. The agent on 28
with `--enable-preview` (class file 72.65535 in the instrumented
`StructuredTaskScopeImpl`) is a separate defect, #176.


## 2026-11-23 - A scope class that no advice matches is named at transformation time (#130)

On a JDK whose `StructuredTaskScope` classes still load but changed,
ByteBuddy reports a transformation in which a method matcher missed
as a success, exactly like a good one, and a renamed
`StructuredTaskScopeImpl` is never transformed at all. Measured on
Temurin 27.0.1 with the class bytes rewritten through
`--patch-module java.base`: a 4th constructor parameter lost the
configured name, and a renamed impl left only `ScopeOpened`, both with
no line. #121's `onError` never fired.

Decided by the maintainer, following the PM's recommendations:

- **When.** At transformation time, in an `AgentBuilder.Listener`
  (`MatcherMissListener.onTransformation`) installed beside #121's,
  with or without `verbose`. `premain` does nothing new. The PM's
  prototypes, median wall time of 25 interleaved runs: a plain `main`
  took 191.2 ms with no check, 190.5 ms with this approach (A), 194.8
  ms with a `TypePool` check in `premain` (B) and 220.4 ms with
  `Class.forName` in `premain` (C). B also described the unpatched class
  file in `jrt:/` and missed the rename; C loads both classes inside
  `premain`, which widens #167's SIGTERM window.
- **How it counts.** The four advice matchers are the same objects the
  agent weaves with (`MatcherMissListener.OPEN`, `CONSTRUCTOR`, `FORK`,
  `CLOSE`), and the listener applies them, with `not(isAbstract())`,
  to the declared methods of the type ByteBuddy just transformed. The
  issue suggested wrapping the matchers to count as ByteBuddy calls
  them; the engineer applied them again in the listener instead,
  because then nothing in the weaving path changes, no per-thread
  state has to carry counts from the matcher to the listener, and the
  decision is a function of a `TypeDescription` that a unit test can
  build. For `REDEFINE`, the methods ByteBuddy's `Advice` matches are
  that type's declared methods, so the answer is the same. On
  `StructuredTaskScope` only `open` is checked: `fork` and `close` are
  abstract there on JDK 27, and the advice weaves nothing into them.
- **A renamed impl.** Checked on the first `open` in the JVM
  (`FirstOpenCheck`, called from `ScopeOpenAdvice` before the filter),
  by comparing the returned scope's class name with
  `java.util.concurrent.StructuredTaskScopeImpl`. Rejected: checking
  `StructuredTaskScope`'s permitted subtypes when it is transformed,
  which depends on the interface staying sealed. The check costs one
  volatile read per `open` after the first.
- **The line.** #121's prefix, `WARN cannot instrument <class>, the
  trace will be incomplete: `, so one grep finds every kind of failure,
  with `no method matched <advice>` (several joined with `, `) or
  `StructuredTaskScope.open returned it, and the agent instruments only
  java.util.concurrent.StructuredTaskScopeImpl`. All three reporters
  share one set of class names (`CannotInstrument`), so a class gets at
  most one `cannot instrument` line whichever fires first. No JDK
  version in the line.
- **Not done.** An advice that matches more methods than on JDK 27 is
  not reported, and a change that every matcher still matches (V2,
  `fork(Runnable)` no longer delegating) is left to the agent ITs on
  the new JDK (#55).


## 2026-11-23 - The agent's type pool reads JDK 28's preview class files (#176)

On JDK 28 EA (measured on 28-ea+25) with `--enable-preview`, the agent
printed `cannot instrument java.util.concurrent.StructuredTaskScopeImpl
... Unsupported class file version 72.65535` and recorded only
`ScopeOpened`. The instrumented class is 72.0. The failing class file
is the `java.base/META-INF/preview` variant of `java.time.Duration`,
which the JVM serves under the flag and which ByteBuddy's `TypePool`
reads to validate `StructuredTaskScopeImpl.scheduleTimeout(Duration)`.
ASM rejects 72.65535 on purpose until it supports value classes (ASM
!473). ByteBuddy's experimental property, which `premain` already
sets, does not apply: `ClassFileVersion` reads the minor version as a
signed `short`, so 72.65535 compares as older than 72.0.

Decided by the maintainer, following the PM's recommendations:

- **The fix: (a).** A class reader for the agent's `TypePool` only
  (`PreviewClassFileReader`), which clears a `0xFFFF` minor version on
  a copy of the bytes and hands every other version to
  `AsmClassReader.Factory.Default.ASM_ONLY` unchanged. Its one-argument
  `make`, the one `TypePool.Default` calls, passes
  `OpenedClassReader.EXPERIMENTAL`, so a 73.65535 class file has only
  its minor cleared and then takes ByteBuddy's own experimental path;
  the PM's prototype passed `false`, which would have refused it. The
  pool is ByteBuddy's `PoolStrategy.Default.FAST` otherwise (lazy
  resolution, `ReaderMode.FAST`). Public ByteBuddy API, no dependency,
  no system property. With it, all agent ITs pass on 28 with
  `--enable-preview` in the child JVMs; without it, 36 of 48 in
  `ScopeTracerAgentIT` and `ScopeTracerAgentFilterIT` fail.
- **Rejected:** turning off ByteBuddy's type validation (works only
  because validation is today the one step that resolves `Duration`,
  and drops a check on every JDK); ByteBuddy's ClassFile-API reader
  (not in the fat-jar, never selected on the bootstrap loader, and
  ByteBuddy #1927 reports a VerifyError in that mode); patching
  through an ASM visitor wrapper (ASM rejects the version in
  `ClassReader`'s constructor, before any visitor); waiting for
  upstream (no date); documenting only (every plugin run on 28 adds
  `--enable-preview`, so all of them would stay broken).
- **Never the transformed class.** The `ByteBuddy` instance keeps its
  default reader, so the class being transformed is read and written
  with its own version. If a JDK ships a preview variant of a scope
  class itself, the agent prints today's `cannot instrument` line.
- **CI.** A second job in `jdk-ea.yml` runs the agent module on 28 EA
  with `-Dscopetracer.test.preview=true`, weekly, beside #55's job,
  which stays without the flag. It uploads its reports under its own
  artifact name, so a run in which both jobs fail keeps both.
- **README.** One Requirements bullet for 0.5.0 and earlier on 28
  with the flag, with both measured workarounds (drop the flag, or
  `-Dcom.ionutbanu.scopetracer.shaded.net.bytebuddy.validation=false`).
  A wider JDK 28 section is #70.
- **No hint in the WARN line**, as #130 decided.
- **Upstream.** The maintainer may file a ByteBuddy issue about the
  signed minor comparison; nothing here waits on it.

## 2026-11-24 - The plugin's version is the Maven version (#151)

- **Decision.** `scope-tracer-plugin` takes its version from the
  parent POM, the same value it already reads as `analyzerVersion`.
  `gradle.properties` has no `pluginVersion`. The zip bundles that
  exact analyzer and agent, so one number names one set of bits and the
  release's `mvn versions:set` is the only bump.
- **Cost.** A plugin-only fix is a patch release of every artifact,
  and the Marketplace refuses the same version twice, so a re-release
  needs a new patch version.
- **Change notes.** Generated in `build.gradle.kts`: a link to the
  GitHub Release and to the tagged `CHANGELOG.md`; a `-SNAPSHOT` links
  `[Unreleased]`. Rendering the section would need a changelog plugin,
  which the maintainer declined.
- **Alternative rejected.** An independent plugin version needs its own
  bump, its own tag check and a bundled-analyzer mapping.

## 2026-11-24 - The plugin supports IntelliJ IDEA 2026.3 and later (#120)

`./gradlew runIde` against 2025.3.6.1 failed to import the
repository's own `release 27` project with a `getNonPreviewLevel`
null, and the built zip was refused by the maintainer's 2026.3. Two
things decide which IDEs the plugin can support.

- **Language level 27.** Whether an IDE imports a `release 27` project
  depends on its Java plugin's `com.intellij.pom.java.LanguageLevel`,
  not on its JBR. Read with `javap` from
  `com.jetbrains.intellij.java:java-syntax:<build>` and from the IDEs'
  own jars: 253 (2025.3.6.1) stops at `JDK_26`/`JDK_26_PREVIEW`;
  261.x, 262.8665 (2026.2) and 262.9437 (2026.2.1) have no `JDK_27`;
  `JDK_27` and `JDK_27_PREVIEW` first appear in 262.10315 (2026.2.2)
  and are in every later 262 build and in 263.7307.200 (2026.3). No
  build has `JDK_28` (#188).
- **JBR.** 2025.3.6.1 bundles JBR 21.0.11, 2026.3 bundles 25.0.4.1
  (each IDE's `jbr/Contents/Home/release`). The statement "the IDE
  runs on JBR (JDK 21)" in the spec was stale. Neither loads
  class-file major 71, so the analyzer stays a subprocess.

Decided by the maintainer, following the PM's recommendations:

- **`since-build` 263.** 2026.3 is the only release checked in a real
  IDE and against the verifier with the new build. 262.10315 to 262.x
  would also know language level 27 but nobody opened a JDK 27 project
  in one; rejected for now.
- **No `until-build`.** JetBrains recommends against it, and Plugin
  Verifier 1.411 finds the zip without one compatible with 2026.3. If
  2026.3 still refuses the zip by hand, the fallback is `263.*`.
- **A JDK 27 toolchain with `--release 25`**, against `platformVersion`
  2026.3 and IntelliJ Platform Gradle Plugin 2.20.1. 2026.3's jars are
  Java 25 class files (major 69), which a JDK 21 javac refuses ("class
  file has wrong version 69.0, should be 65.0", 99 errors). JDK 27 is
  already required by the repository and installed in CI's plugin
  job; a JDK 25 toolchain would add a third JDK everywhere. Gradle
  itself stays on JDK 21. `platformVersion` 2026.3.0.1 does not
  resolve a download URL in 2.20.1, so it is 2026.3.
- **Verification.** `pluginVerification.ides` names 2026.3 and selects
  the newest IntelliJ IDEA build from 263 on, release or EAP, which is
  what `printProductsReleases` returns (on 2026-11-24, IU-263.8311.30,
  2026.3.1 Preview; the newest release is 2026.3 itself).
- **CI.** The plugin job names the 27 for Gradle with
  `-Dorg.gradle.java.installations.fromEnv=JAVA_HOME_27_X64` rather than
  rely on auto-detection of the runner's tool cache, and prints
  `javaToolchains`. No auto-provisioning.

## 2026-11-24 - The stress workflow is path-filtered and runs only the stress tests (#208)

- **Trigger.** `stress.yml` runs on PRs to `main` and pushes to `main` that touch
  `scope-tracer-{core,agent,analyzer,stress-tests}/**`, `pom.xml`, `*/pom.xml` or the workflow,
  weekly, and on `workflow_dispatch`. A plugin-only or docs-only PR does not start it.
- **Only the stress tests.** The step is `mvn -q -Pstress -pl scope-tracer-stress-tests -am verify`
  with `-Dtest=NoUnitTests`, `-Dit.test='**/stress/*IT'` and both `failIfNoSpecifiedTests`
  properties false. `-am` stays, as AGENTS.md requires; upstream modules' surefire and failsafe
  find no matching test and run nothing, the stress ITs run and fail the job. No POM change. The
  `build` job covers the other modules.

## 2026-11-24 - The tool window embeds the report with JCEF (#62)

- **JCEF, with the table as the fallback**, the maintainer's choice on
  #62. One renderer: the timeline is the analyzer's own HTML. A native
  Swing timeline is #192 (v2).
- **Two analyzer runs, JSON then HTML.** The HTML embeds the JSON, but
  reading it from there would make the report's inner structure part
  of the plugin's contract. The second run costs one more parse.
- **JCEF is an optional plugin dependency.** The 2025.3 reading on #62
  had JCEF in the core platform; in 2026.3 it is the bundled plugin
  "Web Browser (JCEF)" (`com.intellij.modules.jcef`), so the build
  declares it with `bundledPlugin` and `plugin.xml` depends on it with
  `optional="true"`. A user who disables that plugin keeps the table;
  a required dependency would have disabled Scope Tracer. Plugin
  Verifier 1.411 finds 2026.3 and IU-263.8311.30 compatible either
  way.
- **Remote development and Gateway are not a target for M5.** Where
  JCEF does not reach the client, the table fallback covers it.

## 2026-11-24 - The release signs and publishes the plugin, opt-in for the Marketplace (#42)

Decided by the maintainer, following the PM's recommendations:

- **Vendor.** An individual vendor, "Ionut Banu", with the address
  `plugin.xml` and the POM's `<developers>` already give; it is public
  on the listing.
- **Signed with a self-signed key.** JetBrains' publishing guide says to
  sign before publishing, and a signed zip lets the Marketplace detect a
  zip changed between CI and the upload. A self-signed certificate is
  accepted; its validity is picked when it is made and its expiry is
  written in `docs/jetbrains-marketplace-setup.md`. The cost is a key to
  keep and renew.
- **No unsigned zip, ever.** With a signing secret missing,
  `publish-plugin` fails before building and names it. The build closes
  the three ways IntelliJ Platform Gradle Plugin 2.20.1 lets an unsigned
  zip through, each found by QA: `verifyPluginSignature` passes as
  NO-SOURCE when `signPlugin` skipped itself (a `requireSignedZip` task
  now fails first); `publishPlugin` takes the unsigned zip whenever
  `signPlugin` is up to date in that run (its archive is now set to the
  `-signed` zip); and `verifyPluginSignature` writes the Base64 chain to
  a `.pem` file verbatim and finds no certificate (the build decodes it).
  Gradle's project cache records the keys in plain text, so the signing
  steps keep it under `$RUNNER_TEMP` and delete it. Maven Central and the
  GitHub Release are finished in `publish` first, so a red
  `publish-plugin` holds back only the plugin.
- **The Marketplace upload is opt-in, by a repository variable.** The
  first version can only be uploaded through the web form (#198), so
  `publishPlugin` runs only when `PLUGIN_MARKETPLACE_PUBLISH` is `true`,
  a variable rather than a secret because it is not sensitive and must
  be readable in a step's `if`. Until it is set, each release attaches
  the signed zip to the GitHub Release and skips the upload with a
  notice.
- **"Predates plugin releases"** means the tag's tree has no
  `.github/scripts/check-plugin-zip.sh`. It is the one file that arrived
  with this pipeline, so the rule needs no list of versions; v0.6.0 and
  earlier take it and their plugin steps are skipped.
- **The secret gate sees no value.** The workflow passes
  `secrets.X != ''` to `check-plugin-secrets.sh`, so the step that
  decides is testable locally and never holds a secret.

## 2026-11-25 - Small QA findings are fixed in the same pull request

Decided by the maintainer.

- **The problem.** Every finding QA reported beyond the acceptance
  criteria became a follow-up issue. A task closed one issue and opened
  one to three, so the backlog kept growing: by 2026-11-24, 33 of the 66
  open issues came from QA or review, most of them a few lines each.
- **The rule.** QA sorts each finding as small or follow-up. A small one
  (a few lines in code the branch touches, no decision needed) goes back
  to the engineer and is fixed on the same branch before the pull
  request opens; QA checks the fix. Only the rest become `from: qa`
  issues. `_docs/process.md`, "Small findings", has the details.
- **The cost.** One more engineer and QA pass on some tasks. It does not
  count toward the three-round ceiling, since the work had passed.
- **Unchanged.** The milestone reviewer's findings still become issues:
  the reviewer reads merged code, so there is no open pull request to
  fix them in.

## 2026-11-27 - The report has two palettes; the plugin sets an explicit theme attribute (#191)

- **Decision.** `HtmlRenderer`'s stylesheet owns a light and a dark
  palette as CSS custom properties. The dark one follows the OS through
  `prefers-color-scheme` and an explicit `data-theme` attribute on
  `<html>` beats it. The plugin only sets that attribute, always to an
  explicit `dark` or `light` from `JBColor.isBright()`, because it is not
  known that JCEF's media query follows the IDE theme and a light IDE on a
  dark OS must stay light.
- **Bar fills are identical in both themes**, so a bar means the same on
  either background; only text, borders, tracks, ticks and links change.
- **Byte-identity.** The plugin still runs the same jar with the same
  arguments and never rewrites the file, so the file is byte for byte the
  CLI's. What changed is that the rendering now differs by the one
  attribute set in the live DOM. "Open in Browser" opens the same bytes
  and shows the OS theme, not the IDE's: accepted.
- **Cost.** Every committed report and the README image changed.

## 2026-11-27 - "Run with Scope Tracer" for JUnit: one trace per JVM

Decided by the maintainer on #64 (2026-11-24 and 2026-11-27); the
details below are the engineer's, within those decisions.

- **What.** The action runs JUnit run configurations started by the
  IDE's own JUnit runner, through the same `java.programPatcher` and
  `TracedRunLifecycle` as applications. No custom `Executor`: §3's
  "Run executor, once" stays. Gradle delegation is #193, Maven
  `mvn test` #194.
- **One trace per JVM, never merged.** The recording is
  `scope-tracer-%p.jfr` in a temp directory per run; JFR expands `%p`
  to the pid, so forked test JVMs write their own files. Merging is
  wrong while `scopeId` restarts per JVM (#197). Applications use the
  same directory, so there is one code path.
- **A cap of 10.** A `forkEvery`-style run could otherwise start
  hundreds of analyzer runs. Past the cap, every recording is kept on
  disk, the analyzed ones included, so the named directory holds them
  all.
- **Where the item is.** The gutter menu's `RunLineMarkerExtraActions`
  and `RunContextGroupInner` after `RunContextExecutorsGroup` (the
  editor's and project view's Run and Debug), checked in 2026.3's
  `ExecutionActions.xml`. The action is a `BaseRunConfigurationAction`,
  registered as a `<group>` as the platform's `CreateRunConfiguration`
  is, so the context's configurations, Run's chooser and Run's
  temporary configuration come from the platform, not a copy of them.
- **JUnit stays optional.** Everything that names a JUnit class is in
  `scope-tracer-junit.xml`; the main code asks a
  `tracedConfigurationKind` extension point, never a class name.
- **Rerun is not traced.** A rerun after the traced run finds no
  registered request, as an ordinary Run does.

## 2026-11-27 - A click in the embedded Timeline goes to the fork call site

Decided by the maintainer on #190 (2026-11-27); the details below are
the engineer's, within those decisions. #217's `ReportNavigation` tests
are folded in.

- **The page carries identity, nothing else.** `HtmlRenderer` writes
  `data-scope-id` and `data-task-id` on each task bar's `<svg>` and
  each task `<tr>`, so the CLI's report gains them too and the gallery,
  the example and the README image were regenerated. Matching bars by
  DOM order, title text or scope name was rejected: scope names repeat,
  and order and text are presentation. Tagging the elements from a
  plugin script instead was rejected for the same reason: it would have
  to re-derive identity from presentation.
- **The handler is injected, not shipped.** The plugin runs
  `TaskClick.script` on the loaded page (in the same `onLoadEnd` as the
  theme) and receives the click through a `JBCefJSQuery`. The file on
  disk has no bridge code, so the CLI's report and "Open in Browser"
  behave as before. A `JBCefJSQuery` call is not a navigation, so
  `ReportNavigation` is unchanged.
- **Call sites stay in the plugin's JSON.** The page sends only
  `"<scopeId>:<taskId>"`; the plugin joins it to its own parsed model
  (`TaskCallSites`, `CallSiteParser.forTask`) and navigates through
  the table's `TaskSourceNavigator`. The page is never trusted with a
  class name, a path or a line, and the plugin never reads the
  `trace-data` block.
- **Gesture.** A single click on a bar or a row of the report's table,
  since both are in the Timeline tab; the "Tasks" table keeps its
  double-click.
- **No call site is said out loud.** A dead click in a page reads as
  broken, so a task with neither a structured call site nor a parseable
  `taskName` shows one information notification ("no call site to go
  to"). The table's double-click stays silent, as before.
