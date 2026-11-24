# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- **The stress workflow runs only when stressed code changes, and only the stress tests**
  (#208). `stress.yml` has a `paths` filter (core, agent, analyzer, the stress module, POMs and
  the workflow itself) on PRs and pushes to `main`, plus a weekly schedule and
  `workflow_dispatch`. Its test step no longer re-runs the other modules' unit tests and ITs.

- **The IntelliJ plugin's version follows the project's** (#151). The plugin zip is now
  `scope-tracer-plugin-<project version>.zip` and its `plugin.xml` carries that version
  (it was a fixed `0.1.0`), with generated change notes linking the GitHub Release and the
  changelog. There is no separate plugin version to bump.

- **The IntelliJ plugin requires IntelliJ IDEA 2026.3 or later** (`since-build` 263, no
  `until-build`, #120). 2025.3 cannot import a JDK 27 project (a `PluginException` from
  `getNonPreviewLevel` when it opens one), and 2026.3 is the first release checked with one. The plugin is
  compiled for Java 25, 2026.3's JBR, on a JDK 27 toolchain; Gradle still runs on JDK 21. The
  plugin was never published, so no installed version stops working.

### Fixed

- **The plugin's analyzer subprocess is bounded, and cancelling it stops it** (#93). Before,
  the plugin read the analyzer's stderr to the end before its 2-minute timeout started, so the
  timeout could never fire, cancelling the background task left the analyzer running (past the
  IDE's own exit), and an analyzer writing more than a pipe buffer to stdout deadlocked. Now
  stdout is discarded and stderr goes to a temp file, the analysis is stopped after 10 minutes
  with a message saying large recordings are slow (#92) and can be cancelled, and cancelling
  from the progress bar, or closing the project, kills the analyzer within 2 s without an error
  balloon. The extracted analyzer jar and the temp JSON are deleted however it ends. The
  `java -version` check is bounded the same way (30 s), so a hanging `java` is reported as an
  undeterminable JDK instead of freezing the IDE.

### Dependencies

- IntelliJ Platform Gradle Plugin 2.18.1 → 2.20.1
- IntelliJ Platform (`platformVersion`) 2025.3.6.1 → 2026.3

## [0.6.0] - 2026-11-24

### Added

- **A weekly build on JDK 28 early access** (`.github/workflows/jdk-ea.yml`, #55). Every
  Saturday the full `mvn verify` runs on the newest Temurin `28-ea` build under a new,
  explicit `-Pjdk-ea` Maven profile, which builds for the running JDK without
  `--enable-preview`. A JDK change that stops the agent's matchers from matching shows up as
  a red run within a week, not on the day of the JDK 28 release. It is not a required check.
  The default build and the release are unchanged: Java 27 with `--enable-preview`.

- **The agent says when a JDK's scope classes no longer match what it instruments.** When
  `StructuredTaskScope` or `StructuredTaskScopeImpl` loads but an instrumented method is not
  found in it (say a changed constructor signature), or `StructuredTaskScope.open` returns a
  class other than `StructuredTaskScopeImpl`, the agent prints one `[scope-tracer] WARN cannot
  instrument <class>, the trace will be incomplete: <reason>` line per class, as for a failed
  transformation (#121). Before, the trace was silently partial or empty. Nothing is added to
  `premain` (#130).

### Fixed

- **The agent traces on JDK 28 with `--enable-preview`.** Under the flag JDK 28 serves some of its
  own classes, `java.time.Duration` among them, as preview class files (version 72.65535), which
  the agent's bundled ASM refuses to read. The agent printed `cannot instrument
  java.util.concurrent.StructuredTaskScopeImpl` and recorded only `ScopeOpened`. It now reads such
  a class file as minor version 0, on a copy, when it only needs to describe the type; the class
  it instruments is read and written with its own version, unchanged. A second weekly JDK 28
  early-access job runs the agent's tests with `--enable-preview` (#176).

## [0.5.0] - 2026-11-23

### Known issues

- **JDK 26 and later: a signal during agent start-up can hang the JVM** (reported to OpenJDK; no bug ID yet). A JVM that
  gets SIGTERM in the first ~200 ms of its life, while the agent installs itself, may never
  exit and needs `kill -9` (SIGINT and SIGHUP were not measured). It is a JDK bug in the shutdown
  path that any agent loading many classes in `premain` triggers; a reproducer with no
  scope-tracer code did not hang on JDK 21 (the agent itself needs JDK 27). The agent cannot avoid it in
  this release. `AgentStartupSignalIT` pins the behaviour against a control with no scope-tracer
  code. See the README's agent reference (#167).

### Fixed

- **A scope-tracer jar reached through a manifest `Class-Path` no longer takes over a renamed
  agent jar.** With `-javaagent:agent.jar` and a `-cp` jar whose manifest `Class-Path` listed
  `agent.jar` and then another scope-tracer jar, the agent printed an `ignoring` line with the two
  jars swapped and then `WARN not tracing: agent startup failed: NoClassDefFoundError` (the thin
  jar), or traced from the other fat-jar. The agent now refuses whenever the `-javaagent` jar and
  another scope-tracer jar are both reachable from `-cp`, directly or through manifest
  `Class-Path` attributes, followed through chains as the JVM follows them: one `WARN not
  tracing` line names both jars, the jar whose `Class-Path` listed the other copy, and the two
  fixes, and nothing is traced. `Class-Path: thin.jar agent.jar` now refuses too: it traced only
  by luck, and cannot be told apart from the reverse order. A `Class-Path` that lists only the
  thin jar, or only the `-javaagent` jar, traces as before. Entries are resolved as the JVM
  resolves them, so `file:thin.jar` and jar names with `^`, `[`, `]`, `{` or `}` count, and an
  entry the agent cannot resolve at all (such as `%00`) makes it refuse rather than guess. One
  accepted over-refusal: the `-javaagent` jar listed by a `Class-Path` under another path to the
  same file (`/tmp/.../agent.jar` against `/private/tmp/.../agent.jar`) now refuses, where the
  JVM had appended it and the agent traced. Manifests are read only in that rare layout; a
  renamed jar with no other scope-tracer copy reads none (#158).
- **A renamed agent jar whose path holds `^`, `[`, `]`, `{` or `}` is read correctly.** When
  such a jar was reached through a manifest `Class-Path`, the agent failed with
  `URISyntaxException` and suggested the generic file name; it now reads the jar's path and its
  exact file name (#158).
- **`scope-tracer-demos` is no longer published to Maven Central.** The bundle held it although
  its pom meant to skip it (the property it set is not read by the publishing plugin). 0.3.0 and
  0.4.0 of `scope-tracer-demos` on Central were published by mistake and are not supported; run
  the demos from a checkout. The release's `check` job now builds the bundle with
  `-DskipUpload=true` and fails a release whose bundle holds anything but the parent POM, core,
  analyzer and agent.

### Changed

- **README quick start from the released jars.** The README opens with a real report (the
  gallery's fail-fast report, as `docs/img/readme-report.png`) and a one-command quick start:
  `curl` the agent from the GitHub Release, run a small `Trial.java` under
  `-javaagent:...=record`, and open `trial.html`. Nothing is cloned or built. The analyzer is run
  from its released `-executable` jar without `--enable-preview`, and the agent, analyzer and
  filter reference follows the `TracedScope` and plugin sections; the contributor material
  (building, demos, modules, the plugin build) moved under "Contributing". The landing page's
  trial command is the same block, and `check-release-docs.sh` now fails a release where either
  file lacks the `=record` command at the release version.

### Added

- **Run scope-tracer with JBang, without cloning or building.** `jbang-catalog.json` defines
  two aliases for the latest release: `analyze` turns a `.jfr` recording into an HTML report, and
  `demo` runs a small structured-concurrency program (a failing task, a cancelled sibling and a
  nested scope) with the agent attached and leaves `scope-tracer-demo.jfr` and
  `scope-tracer-demo.html` in the current directory. The demo source is
  `scope-tracer-demos/jbang/ScopeTracerDemo.java`. The release check now requires the catalog and
  the demo source and a literal version on every scope-tracer coordinate in them, rejects any
  `releases/latest` URL, and requires the catalog itself to name a pinned scope-tracer artifact on
  this repository's releases. The demo passes its arguments on to the traced program, and stopping
  it with SIGTERM stops the traced program too.

## [0.4.0] - 2026-11-19

### Added

- **Gallery of join policies.** `docs/gallery/` shows what `StructuredTaskScope` does under
  each join policy on JDK 27 (fail-fast, all-successful, any-successful, a policy that never
  cancels, a custom joiner, a timeout, nested scopes, a task that ignores its interrupt, and the
  order pipeline): the code, what to look for, and the real report the code produced. The page and
  its reports are generated by `GalleryMain` (`java --enable-preview -cp "$JARS"
  com.ionutbanu.scopetracer.demos.GalleryMain`, from the repository root; see "Demos" in the
  README), which refuses a recording that does not show the behaviour it was written to show.
  `GalleryMain --check` runs in CI and fails a pull request that changed the renderer without
  regenerating the reports. `OrderProcessingDemo` is now deterministic: it always declines
  ORD-002, so the example report on the landing page always shows a failed order.
- **Agent `record` argument.** `-javaagent:scope-tracer-agent-<v>-agent.jar=record` makes
  the agent start a JFR recording of its own in `premain`, so no `-XX:StartFlightRecording`
  is needed: when the program exits there is a `.jfr` and its HTML report on disk, and both
  absolute paths are printed on stderr. By default the file is
  `scope-tracer-<pid>-<yyyy_MM_dd_HH_mm_ss>.jfr` in the working directory, never written over
  an existing file; `record=<path>` names the file (or a directory for the default name).
  The recording is named `scope-tracer`, holds only scope-tracer events, and is capped at
  250 MB. A location that cannot be written gives one warning and no recording; the program
  runs and is instrumented as without the argument. Opt-in: without `record` nothing changes.
- **Analyzer `--verbose`.** `analyzer <in.jfr> <out> [--format=json] [--verbose]` shows the
  analyzer's own log lines on stderr at DEBUG and up, with level and logger name, including one
  summary line per parse (events read by type, scopes and tasks, time taken), and adds the
  stack trace to a run that exits 3 or 4. It is the only switch; no system property or
  environment variable turns it on, Logback's own `logback.configurationFile`, `logback.debug`
  and `logback.statusListenerClass` included (the CLI removes every `logback.*` property before
  logging starts, from `-D` or `JAVA_TOOL_OPTIONS`). Options may now come before, between or
  after the two file arguments, each at most once. Two command lines change because of this:
  an input path that starts with `-` must be written with `./` (`./-x.jfr out.html`; bare
  `-x.jfr out.html` now prints the usage and exits 1, where it exited 0), and `--help extra`
  now prints the usage and exits 1, where it exited 2 (`input.jfr not found: --help`).
- **Analyzer warnings a user can act on.** After writing the report the CLI prints one
  `analyzer: warning:` line on stderr for scopes without a close event or tasks without a
  completion event (counted by scope, with both causes: still running when the recording was
  written, or dropped by JFR), and one for a recording with no scope-tracer events at all
  (the agent or `TracedScope` was not used, or `com.ionutbanu.scopetracer.*` was not
  enabled). A recording whose scope-tracer events all lack a `ScopeOpened` gets `holds N
  scope-tracer events but no ScopeOpened event, so the report shows no scopes` instead, with
  its two causes (scopes opened before the recording started, or dropped events). The exit
  code stays 0.
- **Every jar names its version.** All six built jars, the agent fat-jar included, carry
  `Implementation-Title`, `Implementation-Version` and `Implementation-Vendor` in their
  manifest. `Premain-Class`, `Boot-Class-Path` and `Main-Class` are unchanged.

### Changed

- **Release check.** `release.yml` now runs `.github/scripts/check-release-docs.sh X.Y.Z` in a
  `check` job before the Central publish, and fails the release when the README, `docs/`,
  `CONTRIBUTING.md`, the `CHANGELOG.md` heading and link, or spec section 5 `Released:` name a
  version other than the one being released (0.2.0 shipped a README that said 0.1.0). A
  `check_only` dispatch input runs the checks without publishing. A tag with a qualifier such
  as `v0.4.0-rc1` is rejected. Nothing changes for users of the library.
- **Analyzer exit codes are documented as they are.** The codes are unchanged since 0.3.0;
  the `AnalyzerMain` Javadoc, the spec and the README now describe them, and correct the two
  cases the Javadoc got wrong: a non-JFR input exits 3 (not 4) and an output in a missing
  directory exits 2 (not 3). The codes are stable from 0.4.0. A failure while computing the
  warnings, after the report is written, can no longer change the exit code from 0.
- **The analyzer CLI prints only its report line on stdout.** The library's log lines no
  longer reach stdout in Logback's developer format (`09:26:00.199 [main] WARN
  ...JfrParser -- ...`): they are hidden unless `--verbose` is given, and then go to stderr.
  The parser's WARN for incomplete scopes and tasks no longer says the file "may have been cut
  short"; it names both causes. Exit codes, the report line and the HTML and JSON output are
  unchanged.
- **The analyzer no longer brings Logback to library users.** `logback-classic` is now an
  optional dependency of `scope-tracer-analyzer`, bundled only into its `-executable` jar
  together with the CLI's `logback.xml`; the plain jar carries no logging configuration. A
  program that uses the analyzer as a library logs through its own SLF4J binding, and SLF4J no
  longer warns about multiple providers. A project that relied on getting Logback through the
  analyzer must now declare a binding itself.

- **Agent warnings name the cause.** Every `[scope-tracer] WARN` line that names an exception
  now ends in the whole cause chain: `IllegalStateException: Could not install class file
  transformer; caused by UnsupportedOperationException: adding retransformable transformers is
  not supported in this environment`, at most four entries and then `; caused by ...`, each
  type and message cut at 300 characters. A reason is never empty (an anonymous class is shown
  by its binary name, a blank message is left out), line breaks and control characters in a
  reason or in a path in the line's prefix are written as `\n`, `\uXXXX` and so on, and a line
  without a cause and with a plain message is unchanged. One formatter now serves all of them;
  the unused stack-trace `AgentLog.warn` is gone.
- **`verbose` also prints why a report was skipped.** The `[scope-tracer] DEBUG skipping
  auto-HTML: ...` lines now follow the parsed `verbose` argument, as the ByteBuddy listing does:
  `verbose` prints them, `verbose=false` silences them even with
  `-Dscopetracer.agent.verbose=true`, and without the argument (or with a malformed value) the
  property decides, as before. They stay verbose-only. A recording with no scopes now says
  `skipping auto-HTML: no scopes recorded in <file>` instead of `0 scope(s) is below
  min.scopes=1`; one with scopes but fewer than `min.scopes` keeps `<n> scope(s) is below
  min.scopes=<N>`.
- **The agent warns about arguments that did nothing.** An unknown key (`recrod`; keys are
  case-sensitive, so `RECORD` too), a value with no key (`=x`), a `record`, `html` or
  `verbose` value that is not `true`/`false`, a key that needs a value and has none
  (`output.dir=`), `min.scopes` below 1, and `sample.rate` outside 0.0-1.0 now each print one
  `[scope-tracer] WARN` line naming the argument, and the agent carries on with the others.
  `sample.rate=NaN` is rejected (it used to drop every scope silently). Two behaviours change
  for existing users: `verbose=false` now means off (it used to turn verbose on), and
  `record=true` / `record=false` are booleans, no longer a file of that name - `record=false`
  records nothing of the agent's own, and a file called `true` or `false` needs `./true` or
  `./false`. `html=no` and `html=flase` still keep the report, now with a warning.
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
- **The agent names a scope class it cannot instrument.** When ByteBuddy cannot transform
  `StructuredTaskScope` or `StructuredTaskScopeImpl`, one `[scope-tracer] WARN cannot
  instrument <class>, the trace will be incomplete: <reason>` line per class is printed, at
  most once per JVM and with or without `verbose`; before, the program ran and the recording
  was silently empty or held only `ScopeOpened`. A failing close of the agent's jar after
  instrumenting no longer prints a false `WARN not tracing: instrumentation failed`.
- **IntelliJ plugin:** "Run with Scope Tracer" attaches the agent with `html=false`. The
  plugin shows the trace itself, so no `.html` is written beside its recording and the IDE
  does not wait for one at exit.

### Fixed

- **The analyzer's orphan count no longer reads the file twice.** The `holds N scope-tracer
  events but no ScopeOpened event` count now comes from the read that builds the model, so a
  recording that changes or disappears after `Report written` can no longer turn a complete
  report into exit 3. Output for an unmodified recording is unchanged.
- **A renamed agent jar traces.** The fat-jar's `Boot-Class-Path` names its original file,
  so a copy under another name (`agent.jar`) used to be instrumented but record nothing, with
  no warning. The agent now adds the jar it was loaded from to the bootstrap class path
  itself and prints one `[scope-tracer]` line saying so; arguments, `record`,
  `-XX:StartFlightRecording` and the report work as under the original name. The JVM prints
  its own `Sharing is only supported for boot loader classes because bootstrap classpath has
  been appended` warning in that case; the original name or `-Xbootclasspath/a:<jar>` avoids
  it. If the jar cannot be added, one `WARN not tracing` line names the reason and the fix.
- **Under a renamed agent jar, advice comes from the agent's own jar.** The advice inlined
  into `StructuredTaskScope` used to be read through the system class loader, `-cp` first,
  so an application class named like a scope-tracer advice class was inlined instead of the
  agent's: nothing was traced, or, with another method signature, the program's first
  `StructuredTaskScope.open` threw `ClassFormatError` and the program exited 1. The advice
  bytes are now read from the jar the agent added to the bootstrap class path. If that jar
  cannot be read, one `WARN not tracing` line names it and the agent instruments nothing.
  Nothing changes under the original name or with `-Xbootclasspath/a:`.
- **A scope-tracer jar on `-cp` no longer takes over a renamed agent jar.** With the agent
  under another name (`-javaagent:agent.jar`), a scope-tracer jar also on `-cp` was the one
  the JVM ran: the thin `scope-tracer-agent-<v>.jar` aborted the JVM (exit 134,
  `NoClassDefFoundError: net/bytebuddy/agent/builder/AgentBuilder`), the thin jar with
  ByteBuddy beside it recorded nothing, and another copy of the fat-jar traced but from
  itself. The agent now always adds the `-javaagent` jar to the bootstrap class path and
  traces with it, and prints one `[scope-tracer] ignoring <jar> on the class path: the
  -javaagent jar is <jar>` line when it passes over the copy that started it. When the
  `-javaagent` jar is also on `-cp` together with any other scope-tracer jar, in either order
  (`-cp agent.jar:thin.jar`, `-cp thin.jar:agent.jar`, `-cp a.jar:agent.jar:b.jar`), it cannot
  tell the jars apart: one `WARN not tracing` line
  names the two fixes (the fat-jar's exact original file name, or
  `-Xbootclasspath/a:<jar>`), nothing is traced, and the program runs as without the agent. This needs the jar on `-cp` to be 0.4.0
  or later: a 0.3.x or earlier scope-tracer jar on `-cp` still takes over, because its own
  `premain` is the one the JVM calls. A `-cp` jar whose manifest `Class-Path` lists the
  `-javaagent` jar and then another scope-tracer jar is not handled in this release; it refuses
  from the next one (#158). Nothing changes under the original name.
- **The live demos print jar names that exist.** `LiveServiceDemo` and `LiveOrderProcessingDemo`
  printed `0.1.0-SNAPSHOT` jar paths; they now read the version from their own jar's manifest
  (`<VERSION>` and one line saying so when run from `target/classes` or an IDE) and say the
  paths are relative to the repository root. The demos' Javadoc no longer has `0.1.0-SNAPSHOT`,
  `mvn -pl` without `-am` or `exec:java`, and `FilteringDemo` quotes its `-javaagent:`
  arguments so `zsh` runs them.
- **The package-glob documentation says what is matched.** `com.acme.checkout*` takes
  `com.acme.checkout`, its sub-packages and a sibling such as `com.acme.checkoutx`;
  `com.acme.**` does not take `com.acme` itself; a literal takes one package; no glob means
  "this package and below". README, spec, `ScopeTracerAgent` and `Glob` Javadoc agree, and
  a test pins every row. The "use a literal pattern for a strict prefix" advice was wrong
  and is gone. Glob behaviour is unchanged.

### Dependencies

- gradle/actions/setup-gradle GitHub Action v4 → v6

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

[Unreleased]: https://github.com/ionut-banu/scope-tracer/compare/v0.6.0...HEAD
[0.6.0]: https://github.com/ionut-banu/scope-tracer/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/ionut-banu/scope-tracer/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/ionut-banu/scope-tracer/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/ionut-banu/scope-tracer/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/ionut-banu/scope-tracer/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/ionut-banu/scope-tracer/compare/v0.1.0-SNAPSHOT...v0.1.0
[0.1.0-SNAPSHOT]: https://github.com/ionut-banu/scope-tracer/releases/tag/v0.1.0-SNAPSHOT
