# scope-tracer

[![CI](https://github.com/ionut-banu/scope-tracer/actions/workflows/ci.yml/badge.svg)](https://github.com/ionut-banu/scope-tracer/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/com.ionutbanu/scope-tracer-core.svg?label=maven%20central)](https://central.sonatype.com/artifact/com.ionutbanu/scope-tracer-core)
[![License](https://img.shields.io/github/license/ionut-banu/scope-tracer)](LICENSE)

Visualise Java structured concurrency task trees, lifetimes, and cancellation propagation.

**[View a live example report →](https://ionut-banu.github.io/scope-tracer/example/order-processing-report.html)**
(no install needed — nested scopes, a cancelled task, and critical-path highlighting)

Wrap your `StructuredTaskScope` code with `TracedScope`, run the program under a JFR
recording, feed the `.jfr` file to the analyzer, and open the resulting self-contained HTML
report. The report shows a per-scope Gantt timeline and task table so you can see what ran
in parallel, which tasks were cancelled, and how nested scopes relate to their parent task.

Targets JDK 27+ with `--enable-preview` (`StructuredTaskScope` is a preview API).

---

## Requirements

| Tool | Version |
|------|---------|
| Java | 27+ (with `--enable-preview`) |
| Maven | 3.9+ |

---

## Quick start

### 1. Add the dependency

```xml
<dependency>
  <groupId>com.ionutbanu</groupId>
  <artifactId>scope-tracer-core</artifactId>
  <version>0.3.0</version>
</dependency>
```

0.3.0 requires JDK 27 (every jar is built with `--release 27`); on JDK 26, stay on 0.2.0.

`TracedScope` is a preview API on JDK 27, so your own build needs `--enable-preview` and
`--release 27` to compile against it, and `--enable-preview` to run. Without them javac stops
with `... TracedScope.class uses preview features of Java SE 27`. In Maven:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-compiler-plugin</artifactId>
  <configuration>
    <release>27</release>
    <compilerArgs>
      <arg>--enable-preview</arg>
    </compilerArgs>
  </configuration>
</plugin>
```

With plain `javac`: `javac --enable-preview --release 27 -cp scope-tracer-core-0.3.0.jar ...`.

### 2. Open a `TracedScope` instead of a `StructuredTaskScope`

`TracedScope` mirrors JDK 27's `StructuredTaskScope`: the same type parameters, the same
`open` factories, `fork`, `join` and `close`. The differences are the entry point,
`TracedScope.open("name", ...)` with the scope's name as the first argument (there is no
constructor), and the JFR events it emits. `join()` throws what the plain scope's throws:
`ExecutionException` for the default joiner, with the failed task's exception as its cause.

```java
import com.ionutbanu.scopetracer.core.TracedScope;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.StructuredTaskScope.Subtask;

Checkout checkout(Cart cart) throws ExecutionException, InterruptedException {
    try (var scope = TracedScope.open("checkout-flow")) {
        Subtask<Quote>       pricing   = scope.fork(() -> pricingService.quote(cart));
        Subtask<Reservation> inventory = scope.fork(() -> inventoryService.reserve(cart));
        scope.join();
        return new Checkout(pricing.get(), inventory.get());
    }
}
```

`TracedScope` emits six JFR events at every lifecycle moment (scope opened/closed, task
forked/succeeded/failed/cancelled). No other configuration is required.

### 3. Run under JFR recording

```bash
java -XX:StartFlightRecording=filename=myapp.jfr,dumponexit=true \
     --enable-preview \
     -cp <your-classpath> \
     com.example.MyApp
```

Put your own classpath (including the `scope-tracer-core` jar) where `<your-classpath>` is,
and your main class where `com.example.MyApp` is.

### 4. Run the analyzer

Run this from a clone of this repository, after `mvn -q -DskipTests package`:

```bash
VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)
java --enable-preview \
     -jar scope-tracer-analyzer/target/scope-tracer-analyzer-${VERSION}-executable.jar \
     myapp.jfr report.html
```

The `-executable` jar is a self-contained fat-jar produced by `mvn package`. It bundles
all runtime dependencies so no classpath assembly is needed.

Pass `--format=json` as a third argument to write the parsed trace as JSON instead of an
HTML report (schema documented in `TraceModelJson`'s Javadoc) — intended for external
tools, such as an IDE plugin, that want the parsed trace without loading any scope-tracer
classes in-process:

```bash
java --enable-preview \
     -jar scope-tracer-analyzer/target/scope-tracer-analyzer-${VERSION}-executable.jar \
     myapp.jfr trace.json --format=json
```

Or use the programmatic API (put the `scope-tracer-analyzer` jar on the classpath):

```java
import com.ionutbanu.scopetracer.analyzer.HtmlRenderer;
import com.ionutbanu.scopetracer.analyzer.JfrParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

void writeReport() throws IOException {
    var model = JfrParser.parse(Path.of("myapp.jfr"));
    String html = HtmlRenderer.render(model);
    Files.writeString(Path.of("report.html"), html);
}
```

### 5. Open the report

Open `report.html` in any browser. No server required — the file is fully self-contained.

---

## What the report shows

Each `TracedScope` gets its own section containing:

- **Metadata line** — owner thread, open time (UTC), total duration, task count.
- **Task table** — one row per forked task: task ID, task name (explicit, derived from
  the `Callable` class, or `SimpleClass#method:line` from the call site), thread name,
  fork offset from scope open, duration, and outcome. Failed tasks show the exception
  type. Tasks that opened a nested scope show the child scope name. In all-success scopes
  the slowest task is
  annotated `← critical path +Xms`.
- **SVG Gantt timeline** — a blue bar for the scope lifetime, colour-coded bars for each
  task (green = success, amber = critical path, red = failed, orange = cancelled, grey =
  incomplete), with the task ID and name labelled inside each bar; narrow bars drop the
  class-name prefix and, if still too narrow, truncate with an ellipsis — the full name
  is always available in the hover tooltip. Hover for details. The amber
  critical-path bar is the task that determined the scope's total duration.

Nested scopes are rendered indented beneath the parent task that opened them, with a
breadcrumb showing which task spawned them.

---

## Zero-code-change tracing (Java agent)

Don't want to change source code? Use the agent. It instruments `StructuredTaskScope` at
the bytecode level — any JDK 27+ application is traced without touching its source.

One flag is enough: with the `record` argument the agent starts a JFR recording of its own,
and when the program exits it writes the `.jfr` and an HTML report next to it.

```bash
VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)
java --enable-preview \
     -javaagent:scope-tracer-agent/target/scope-tracer-agent-${VERSION}-agent.jar=record \
     -cp <your-classpath> \
     com.example.MyApp
```

```text
[scope-tracer] recording to /work/scope-tracer-4242-2026_11_14_10_30_00.jfr
... your program's own output ...
[scope-tracer] HTML report written to /work/scope-tracer-4242-2026_11_14_10_30_00.html
```

The agent's lines go to stderr, prefixed `[scope-tracer]`; nothing is added to stdout. The
recording is named `scope-tracer`, holds only scope-tracer events and is capped at 250 MB.
`jcmd <pid> JFR.stop name=scope-tracer` writes both files at once instead of at exit.

A recording you start yourself gets its report at exit too, the same as one stopped with
`jcmd <pid> JFR.stop`:

```bash
java --enable-preview \
     -javaagent:scope-tracer-agent/target/scope-tracer-agent-${VERSION}-agent.jar \
     -XX:StartFlightRecording=filename=myapp.jfr,dumponexit=true \
     -cp <your-classpath> \
     com.example.MyApp
# leaves myapp.jfr and myapp.html
```

The report is written while the JVM shuts down, so a large recording delays exit (about 3 s
for 10,000 scopes, measured on a laptop). `html=false` turns it off. A JVM killed outright
(`kill -9`, `Runtime.halt`) runs no shutdown hooks and gets no report; JFR leaves an empty
`.jfr` at the recording's path, and never a half-written report.

| Argument | Meaning |
|----------|---------|
| `record` | Start the agent's own recording: `scope-tracer-<pid>-<yyyy_MM_dd_HH_mm_ss>.jfr` in the working directory, never written over an existing file. |
| `record=<path>` | The same, written to `<path>` (parent directories created, an existing file replaced). An existing directory means the default file name in it. |
| `html=false` | No HTML report, neither on `JFR.stop` nor at exit. |
| `output.dir=<path>` | Write the report to this directory (created if missing) instead of beside the `.jfr`. |
| `output.suffix=<ext>` | Report file suffix in place of `.jfr` (default `.html`). |
| `min.scopes=<N>` | No report for a recording with fewer than N scopes. |

Arguments are comma-separated: `-javaagent:...agent.jar=record,html=false`. If the `record`
location cannot be written, the agent prints one warning and runs without its own recording;
the program is still traced into any other recording.

Running `record` together with `-XX:StartFlightRecording` gives two recordings and two
reports. JFR shares its buffers between running recordings, so each file then also holds the
other's events: the agent's file gets the JDK events of the `-XX` recording, and the `-XX`
file gets the scope-tracer events whatever its own settings say.

With `TracedScope` (no agent attached) nothing writes a report at exit: the recording is
only the `.jfr`, and the analyzer CLI (step 4 of the quick start) turns it into the report.

The agent:
- Uses the name supplied to `Configuration.withName("my-scope")` when set (for example
  `StructuredTaskScope.open(cfg -> cfg.withName("my-scope"))`). When no name is configured
  the name is derived from the call-site stack frame (format: `SimpleClassName#methodName`).
- Emits the same six JFR events as `TracedScope`, so the analyzer pipeline is identical.
- **Do not** combine with `TracedScope` — each scope would emit duplicate events.

### Capture filters & sampling

For production deployments where you don't want to capture every scope, the agent accepts
filter and sampling arguments. Filtered or sampled-out scopes produce **zero** JFR events
for their entire lifetime — no open, no fork, no completion, no close.

| Argument | Meaning |
|----------|---------|
| `include.name=<glob>` | Only capture scopes whose name matches the glob. |
| `exclude.name=<glob>` | Drop scopes whose name matches. Exclude wins over include. |
| `include.package=<glob>` | Only capture scopes opened from a class in a matching package. Fails closed when the stack walk yields no user frame. |
| `exclude.package=<glob>` | Drop scopes opened from a class in a matching package. |
| `sample.rate=<0.0-1.0>` | Capture only this fraction of scopes that survive include/exclude. `1.0` (default) keeps all; `0.0` drops everything; `0.01` keeps ~1%. |

Globs support `*`, `**` (both equivalent to `.*`), and `?`. Patterns are **not** path-segment
aware: `com.acme.*` matches both `com.acme.foo` and `com.acme.foo.bar`. Use a literal pattern
if you need a strict prefix.

The package glob is matched against the package name itself, so `com.acme.checkout.**` skips
scopes opened directly in `com.acme.checkout`; `com.acme.checkout*` takes that package and
everything below it.

Example — trace only the checkout subsystem at a 10% sample rate (quote the agent argument so
the shell does not expand the `*`):

```bash
VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)
java --enable-preview \
     "-javaagent:scope-tracer-agent/target/scope-tracer-agent-${VERSION}-agent.jar=include.package=com.acme.checkout*,sample.rate=0.1" \
     -XX:StartFlightRecording=filename=checkout.jfr,dumponexit=true \
     -cp <your-classpath> com.acme.Main
```

Replace `<your-classpath>` and `com.acme.Main` with your own classpath and main class.

> **Note:** The agent jar is self-bootstrapped via `Boot-Class-Path` in its manifest. No
> extra JVM flags are needed for the bootstrap classloader setup. The jar can be renamed
> or copied (`COPY ... /agent.jar` in a Dockerfile, say): under another name the agent adds
> the jar to the bootstrap class path itself and says so in one `[scope-tracer]` line. The
> JVM then prints a warning of its own, `Sharing is only supported for boot loader classes
> because bootstrap classpath has been appended`, which only means class-data sharing is
> off for the application's classes. Keeping the original name
> (`scope-tracer-agent-<v>-agent.jar`), or adding `-Xbootclasspath/a:<path to the jar>`
> beside `-javaagent`, avoids it.

---

## IntelliJ plugin

`scope-tracer-plugin/` opens a recording inside IntelliJ IDEA: Tools → **Open .jfr Recording
(Scope Tracer)…** loads a `.jfr` file into the **Scope Tracer** tool window, and clicking a
task jumps to the `fork` call that created it. It is not published yet; build it from source:

```bash
mvn -q -DskipTests package                 # builds the analyzer's -executable jar
cd scope-tracer-plugin && ./gradlew build  # bundles that jar and builds the plugin
./gradlew runIde                           # try it in a sandbox IDE
```

The IDE itself runs on JDK 21, so the plugin runs the bundled analyzer as a subprocess on a
JDK 27 that you configure; it needs that JDK installed, and refuses an older `java`. To trace your own code from the IDE, select a Java application run configuration and use Tools
→ **Run with Scope Tracer**: the plugin adds the agent, `--enable-preview` and a JFR recording
to that run and opens the result when the process exits (the run configuration needs a JDK 27).
You can also open a `.jfr` you recorded yourself (see "Run under JFR recording" and
"Zero-code-change tracing" above).

---

## Demos

Eight runnable examples are included in `scope-tracer-demos`:

| Demo | What it shows |
|------|--------------|
| `ParallelFetchDemo` | Happy path — three tasks run in parallel, all succeed |
| `FailFastDemo` | Cancellation — one task fails, its sibling is interrupted |
| `NestedScopesDemo` | Nesting — a task inside the outer scope opens an inner scope |
| `AgentDemo` | Zero-code-change — plain `StructuredTaskScope`, traced by the agent |
| `LiveServiceDemo` | On-demand monitoring — long-running service; use `jcmd` to turn tracing on/off without restarting |
| `OrderProcessingDemo` | Multi-level nesting — e-commerce pipeline with payment and inventory sub-scopes; fraud failures; critical-path highlighting |
| `LiveOrderProcessingDemo` | Live version of `OrderProcessingDemo` — runs until Ctrl+C; use `jcmd` to capture windows of the nested pipeline |
| `FilteringDemo` | The agent's capture filters and sampling — opens five kinds of scope (one of them ten times); the `include.name`, `exclude.name`, `include.package`, `exclude.package` and `sample.rate` arguments decide which reach the report |

Six demos write a `.jfr` and an `.html` file to `target/` and exit: `ParallelFetchDemo`,
`FailFastDemo`, `NestedScopesDemo`, `OrderProcessingDemo`, `AgentDemo` and `FilteringDemo`.
The two live demos, `LiveServiceDemo` and `LiveOrderProcessingDemo`, run until Ctrl+C and print
ready-to-paste `jcmd` commands at startup. `AgentDemo`, `FilteringDemo` and the live demos use
plain `StructuredTaskScope` and need the agent (without it nothing is recorded); the others
use `TracedScope`.

**Run a demo:**

```bash
mvn -q -DskipTests package
VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)
mvn -pl scope-tracer-demos -am -q -DskipTests package dependency:build-classpath -Dmdep.outputFile=/tmp/scope-tracer-cp.txt
CP=$(cat /tmp/scope-tracer-cp.txt)
JARS="scope-tracer-core/target/scope-tracer-core-${VERSION}.jar:\
scope-tracer-analyzer/target/scope-tracer-analyzer-${VERSION}.jar:\
scope-tracer-demos/target/scope-tracer-demos-${VERSION}.jar:$CP"

java --enable-preview -cp "$JARS" com.ionutbanu.scopetracer.demos.ParallelFetchDemo
java --enable-preview -cp "$JARS" com.ionutbanu.scopetracer.demos.FailFastDemo
java --enable-preview -cp "$JARS" com.ionutbanu.scopetracer.demos.NestedScopesDemo

# AgentDemo uses the agent — no TracedScope in source
java --enable-preview \
     -javaagent:scope-tracer-agent/target/scope-tracer-agent-${VERSION}-agent.jar \
     -cp "$JARS" com.ionutbanu.scopetracer.demos.AgentDemo

# OrderProcessingDemo — multi-level nesting; writes .jfr and .html then exits
java --enable-preview -cp "$JARS" com.ionutbanu.scopetracer.demos.OrderProcessingDemo

# LiveServiceDemo — long-running; copy the jcmd commands it prints, then Ctrl+C to stop
java --enable-preview \
     -javaagent:scope-tracer-agent/target/scope-tracer-agent-${VERSION}-agent.jar \
     -cp "$JARS" com.ionutbanu.scopetracer.demos.LiveServiceDemo

# LiveOrderProcessingDemo — live nested pipeline; copy the jcmd commands it prints, then Ctrl+C to stop
java --enable-preview \
     -javaagent:scope-tracer-agent/target/scope-tracer-agent-${VERSION}-agent.jar \
     -cp "$JARS" com.ionutbanu.scopetracer.demos.LiveOrderProcessingDemo

# FilteringDemo — the agent's filters; baseline first (all five scope names appear), then only *-flow
java --enable-preview \
     -javaagent:scope-tracer-agent/target/scope-tracer-agent-${VERSION}-agent.jar \
     -cp "$JARS" com.ionutbanu.scopetracer.demos.FilteringDemo
java --enable-preview \
     "-javaagent:scope-tracer-agent/target/scope-tracer-agent-${VERSION}-agent.jar=include.name=*-flow" \
     -cp "$JARS" com.ionutbanu.scopetracer.demos.FilteringDemo
```

The other scenarios are in `FilteringDemo`'s Javadoc.

**Using LiveServiceDemo** (in a second terminal while the service is running):

```bash
# capture a window of activity
jcmd <pid> JFR.start name=trace filename=/tmp/scope-trace.jfr

# ... wait for a few orders to process ...

# stop and dump
jcmd <pid> JFR.stop name=trace

# analyze
VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)
java --enable-preview \
     -jar scope-tracer-analyzer/target/scope-tracer-analyzer-${VERSION}-executable.jar \
     /tmp/scope-trace.jfr /tmp/report.html
open /tmp/report.html
```

You can repeat `JFR.start` / `JFR.stop` as many times as you like without restarting the
service. Each recording captures only the orders that ran during that window.

---

## Building from source

```bash
git clone <repo-url>
cd scope-tracer
mvn -q verify        # compile, test, style check
mvn spotless:apply   # auto-fix formatting if needed
```

See [_docs/spec.md](_docs/spec.md) for architecture details, [AGENTS.md](AGENTS.md) for coding
conventions, and [CONTRIBUTING.md](CONTRIBUTING.md) for contributor
guidelines.

---

## Modules

| Module | Artifact | Purpose |
|--------|----------|---------|
| `scope-tracer-core` | `com.ionutbanu:scope-tracer-core` | `TracedScope` wrapper; emits JFR events |
| `scope-tracer-analyzer` | `com.ionutbanu:scope-tracer-analyzer` | Parses `.jfr` files; renders HTML/SVG reports |
| `scope-tracer-agent` | `com.ionutbanu:scope-tracer-agent` | Java agent; instruments `StructuredTaskScope` at bytecode level |
| `scope-tracer-demos` | `com.ionutbanu:scope-tracer-demos` | Runnable example programs |
| `scope-tracer-stress-tests` | — | Concurrency stress tests; only built with `-Pstress` |
| `scope-tracer-plugin` | — | IntelliJ plugin; separate Gradle build, not in the Maven reactor |

---

## JFR event schema

The six events emitted by `TracedScope` and the agent are documented in
[docs/jfr-events.md](docs/jfr-events.md). That file lists every field, its type, and
when each event is emitted — useful when writing custom consumers of the raw `.jfr` file.

---

## License

[Apache License 2.0](LICENSE). Free for any use, including commercial — see
[NOTICE](NOTICE) for attribution requirements.
