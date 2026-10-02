Visualises Java structured concurrency (`StructuredTaskScope`, JDK
26+) as task trees, lifetimes and cancellation. See `_docs/spec.md`
for what it is and `_docs/decisions.md` for why. Tasks live as GitHub
issues; see `_docs/process.md` for the per-task workflow.

Commands

- `mvn -q verify` - full build: compile, unit tests, agent ITs,
  Spotless, Jacoco, SpotBugs. The only command that counts as green.
- `mvn -pl scope-tracer-analyzer -am -q verify` - one module plus
  what it depends on. Never drop `-am` - see Rules.
- one test:
  `mvn -pl scope-tracer-core -am -Dtest=TracedScopeTest#method -Dsurefire.failIfNoSpecifiedTests=false test`
- `mvn spotless:apply` - fix formatting (google-java-format, sortPom)
- `mvn -q -DskipTests install && mvn -q -Pstress verify` - stress
  tests
- `cd scope-tracer-plugin && ./gradlew build` - plugin and its
  `:model` tests. Needs the analyzer's `-executable` jar in
  `scope-tracer-analyzer/target/` first (`mvn -q -DskipTests package`).
- `cd scope-tracer-plugin && ./gradlew runIde` - try the plugin in a
  sandbox IDE
- Demos: see "Demos" in `README.md` for the exact `java
  --enable-preview` invocations. `exec:java` cannot pass
  `--enable-preview`, so demos are always run with plain `java`.

Layout

- `scope-tracer-core/` - `TracedScope` and the six JFR events
- `scope-tracer-analyzer/` - `.jfr` → model → HTML/JSON, and the CLI
- `scope-tracer-agent/` - ByteBuddy agent; fat-jar classifier `agent`
- `scope-tracer-demos/` - runnable examples, correct and buggy
- `scope-tracer-stress-tests/` - only in the `stress` profile
- `scope-tracer-plugin/` - IntelliJ plugin, a separate Gradle build
  on JDK 21, not in the Maven reactor
- `docs/` - published by GitHub Pages; `docs/jfr-events.md` is the
  public event schema
- `_docs/` - how the project is specified and run; not published

Rules

- Java 26 with `--enable-preview` project-wide, deliberately. Do not
  disable it.
- Dependencies go in the parent `<dependencyManagement>`; modules
  omit `<version>`. Do not add one without asking - in a comment on
  the issue, before adding it.
- No Lombok. Records, sealed types, pattern matching.
- Public API in core has Javadoc with a usage example.
- Tests: JUnit 5, AssertJ, Awaitility. No `Thread.sleep` outside
  demos. Never `@Disabled` a failing test.
- Logging is SLF4J, except in the agent, which uses `AgentLog` -
  the agent lives on the bootstrap classloader, where an SLF4J
  binding would collide with the host application's. `AnalyzerMain`
  may print to stdout/stderr. No `System.out.println` anywhere else
  outside demos.
- No `Thread.stop`, `Thread.suspend` or other deprecated thread APIs.
- Tracing never changes the behaviour of the traced code. Advice is
  `suppress = Throwable.class`; a tracer bug may lose events, never
  break the application.
- Events and fields are never renamed or removed. A new field is
  nullable and readers check `hasField`. Recordings from every
  released version must still parse.
- A new or changed event or field updates `docs/jfr-events.md` (the
  "Common fields" and "Event catalog" tables) and the analyzer's
  exhaustive switch in the same change.
- The plugin never loads analyzer classes in the IDE's JVM. It talks
  to the analyzer through a subprocess and JSON. A change to
  `TraceModelJson`'s output changes the plugin's `:model` parser in
  the same issue.
- Never run `mvn -pl <module>` without `-am`. Without it Maven takes
  sibling modules from `~/.m2`, which every checkout and worktree
  shares, and the tests pass or fail against someone else's bytecode.
- Do not combine the agent with `TracedScope` in one JVM - every
  event is emitted twice.

Documents

- `_docs/spec.md` - the only specification, always current
- `_docs/decisions.md` - why things were decided, dated, append-only
- `_docs/process.md` - how work is organized
- `_docs/task-template.md` - the format a groomed issue body must
  be in
- `_docs/team/pm.md` - the PM role: grooms a task before anyone
  implements it
- `_docs/team/software-engineer.md` - the engineer role: implements
  one groomed task at a time
- `_docs/team/qa-engineer.md` - the QA role: checks finished work
  against the issue that specified it
- `_docs/team/reviewer.md` - the reviewer role: reads a milestone
  of code and says what is wrong with it

Anyone working one of the four roles above - PM, engineer, QA,
reviewer - reads its own role file before doing anything else.
