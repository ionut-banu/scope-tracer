# scope-tracer

Visualise Java structured concurrency task trees, lifetimes, and cancellation propagation.

Wrap your `StructuredTaskScope` code with `TracedScope`, run the program under a JFR
recording, feed the `.jfr` file to the analyzer, and open the resulting self-contained HTML
report. The report shows a per-scope Gantt timeline and task table so you can see what ran
in parallel, which tasks were cancelled, and how nested scopes relate to their parent task.

Targets JDK 26+ with `--enable-preview` (`StructuredTaskScope` is a preview API).

---

## Requirements

| Tool | Version |
|------|---------|
| Java | 26+ (with `--enable-preview`) |
| Maven | 3.9+ |

---

## Quick start

### 1. Add the dependency

```xml
<dependency>
  <groupId>com.ionutbanu</groupId>
  <artifactId>scope-tracer-core</artifactId>
  <version>0.1.0</version>
</dependency>
```

> Until 0.1.0 lands on Maven Central, build from source (see below) and install into your
> local Maven repository before adding the dependency.

### 2. Wrap your scope

Replace `StructuredTaskScope` with `TracedScope`. The API is the same: `fork()`, `join()`,
try-with-resources.

```java
import com.ionutbanu.scopetracer.core.TracedScope;

try (var scope = new TracedScope("checkout-flow")) {
    Subtask<Quote>       pricing   = scope.fork(() -> pricingService.quote(cart));
    Subtask<Reservation> inventory = scope.fork(() -> inventoryService.reserve(cart));
    scope.join();
    return new Checkout(pricing.get(), inventory.get());
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

### 4. Run the analyzer

```bash
VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)
java --enable-preview \
     -jar scope-tracer-analyzer/target/scope-tracer-analyzer-${VERSION}-executable.jar \
     myapp.jfr report.html
```

The `-executable` jar is a self-contained fat-jar produced by `mvn package`. It bundles
all runtime dependencies so no classpath assembly is needed.

Or use the programmatic API:

```java
import com.ionutbanu.scopetracer.analyzer.JfrParser;
import com.ionutbanu.scopetracer.analyzer.HtmlRenderer;

var model = JfrParser.parse(Path.of("myapp.jfr"));
String html = HtmlRenderer.render(model);
Files.writeString(Path.of("report.html"), html);
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
the bytecode level — any JDK 26+ application is traced without touching its source.

```bash
VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)
java --enable-preview \
     -javaagent:scope-tracer-agent/target/scope-tracer-agent-${VERSION}-agent.jar \
     -XX:StartFlightRecording=filename=myapp.jfr,dumponexit=true \
     -cp <your-classpath> \
     com.example.MyApp
```

The agent:
- Uses the name supplied to `Config.withName("my-scope")` when set. When no name is
  configured the name is derived from the call-site stack frame (format:
  `SimpleClassName#methodName`).
- Emits the same six JFR events as `TracedScope`, so the analyzer pipeline is identical.
- **Do not** combine with `TracedScope` — each scope would emit duplicate events.

> **Note:** The agent jar is self-bootstrapped via `Boot-Class-Path` in its manifest. No
> extra JVM flags are needed for the bootstrap classloader setup.

---

## Demos

Seven runnable examples are included in `scope-tracer-demos`:

| Demo | What it shows |
|------|--------------|
| `ParallelFetchDemo` | Happy path — three tasks run in parallel, all succeed |
| `FailFastDemo` | Cancellation — one task fails, its sibling is interrupted |
| `NestedScopesDemo` | Nesting — a task inside the outer scope opens an inner scope |
| `AgentDemo` | Zero-code-change — plain `StructuredTaskScope`, traced by the agent |
| `LiveServiceDemo` | On-demand monitoring — long-running service; use `jcmd` to turn tracing on/off without restarting |
| `OrderProcessingDemo` | Multi-level nesting — e-commerce pipeline with payment and inventory sub-scopes; fraud failures; critical-path highlighting |
| `LiveOrderProcessingDemo` | Live version of `OrderProcessingDemo` — runs until Ctrl+C; use `jcmd` to capture windows of the nested pipeline |

The first four demos write a `.jfr` and `.html` file to `target/` and exit. The live demos
run until Ctrl+C and print ready-to-paste `jcmd` commands at startup.

**Run a demo:**

```bash
mvn install -DskipTests
VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)
mvn -pl scope-tracer-demos -q dependency:build-classpath -Dmdep.outputFile=/tmp/scope-tracer-cp.txt
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
java --enable-preview -cp "$JARS" com.ionutbanu.scopetracer.demos.LiveOrderProcessingDemo
```

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

See [CLAUDE.md](CLAUDE.md) for architecture details, coding conventions, and contributor
guidelines.

---

## Modules

| Module | Artifact | Purpose |
|--------|----------|---------|
| `scope-tracer-core` | `com.ionutbanu:scope-tracer-core` | `TracedScope` wrapper; emits JFR events |
| `scope-tracer-analyzer` | `com.ionutbanu:scope-tracer-analyzer` | Parses `.jfr` files; renders HTML/SVG reports |
| `scope-tracer-agent` | `com.ionutbanu:scope-tracer-agent` | Java agent; instruments `StructuredTaskScope` at bytecode level |
| `scope-tracer-demos` | `com.ionutbanu:scope-tracer-demos` | Runnable example programs |

---

## JFR event schema

The six events emitted by `TracedScope` and the agent are documented in
[docs/jfr-events.md](docs/jfr-events.md). That file lists every field, its type, and
when each event is emitted — useful when writing custom consumers of the raw `.jfr` file.

---

## License

[Apache License 2.0](LICENSE). Free for any use, including commercial — see
[NOTICE](NOTICE) for attribution requirements.
