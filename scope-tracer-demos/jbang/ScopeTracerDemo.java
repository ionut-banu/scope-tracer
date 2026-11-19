///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 27+
//PREVIEW
//DEPS com.ionutbanu:scope-tracer-agent:0.4.0:agent

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Joiner;
import java.util.concurrent.TimeUnit;

/**
 * A small structured-concurrency program for trying scope-tracer without a clone or a build:
 * {@code jbang demo@ionut-banu/scope-tracer}.
 *
 * <p>JBang puts the scope-tracer agent jar on the class path (the {@code //DEPS} line), and this
 * program starts itself again in a second JVM with {@code -javaagent:<that jar>=record=
 * scope-tracer-demo.jfr}. The agent records the run and, when it ends, writes {@code
 * scope-tracer-demo.html} next to {@code scope-tracer-demo.jfr} in the current directory, and
 * prints both paths on stderr. (JBang's own {@code java-agents} alias key and {@code //JAVAAGENT}
 * directive cannot attach an agent with a file name: see the issue #57 thread.)
 *
 * <p>The scenario uses only the JDK. One scope opens a task that has a scope of its own, one task
 * fails, and the slow sibling is cancelled because the scope fails fast.
 */
public class ScopeTracerDemo {

  private static final String CHILD = "scope-tracer.demo.child";

  public static void main(String[] args) throws Exception {
    if (!agentAttached()) {
      String agentJar = agentJarOnClassPath();
      if (agentJar != null && System.getProperty(CHILD) == null) {
        System.exit(relaunchWithAgent(agentJar, args));
      }
      System.out.println(
          "The scope-tracer agent is not attached, so no report will be written."
              + " Run: jbang demo@ionut-banu/scope-tracer");
    }
    if (args.length > 0) {
      System.out.println("arguments: " + String.join(" ", args));
    }
    checkout();
  }

  private static void checkout() throws Exception {
    try (var scope =
        StructuredTaskScope.open(
            Joiner.<String>awaitAllSuccessfulOrThrow(), c -> c.withName("checkout"))) {
      scope.fork(ScopeTracerDemo::priceBasket);
      scope.fork(ScopeTracerDemo::chargeCard);
      scope.fork(ScopeTracerDemo::reserveStock);
      try {
        scope.join();
      } catch (ExecutionException e) {
        System.out.println("checkout failed: " + e.getCause().getMessage());
      }
    }
  }

  /** Opens a scope of its own, so the report shows a scope nested under a task. */
  private static String priceBasket() throws InterruptedException, ExecutionException {
    try (var scope =
        StructuredTaskScope.open(
            Joiner.<String>awaitAllSuccessfulOrThrow(), c -> c.withName("price-lookup"))) {
      scope.fork(() -> pause("list price", 40));
      scope.fork(() -> pause("discount", 60));
      scope.join();
      return "priced";
    }
  }

  /** Fails after the pricing scope has finished, which cancels the slow sibling. */
  private static String chargeCard() throws InterruptedException {
    Thread.sleep(250);
    throw new IllegalStateException("card declined");
  }

  /** Still waiting when the card is declined, so the scope interrupts it. */
  private static String reserveStock() throws InterruptedException {
    return pause("stock", 3_000);
  }

  private static String pause(String what, long millis) throws InterruptedException {
    Thread.sleep(millis);
    return what;
  }

  private static boolean agentAttached() {
    return ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
        .anyMatch(a -> a.startsWith("-javaagent:") && a.contains("scope-tracer"));
  }

  /** The agent jar JBang resolved from the {@code //DEPS} line, or null when there is none. */
  private static String agentJarOnClassPath() {
    for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
      if (new File(entry).getName().matches("scope-tracer-agent-.*-agent\\.jar")) {
        return entry;
      }
    }
    return null;
  }

  /** Runs this class again with the agent attached, without scope-tracer's jars on the path. */
  private static int relaunchWithAgent(String agentJar, String[] args) throws Exception {
    List<String> classPath = new ArrayList<>();
    for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
      if (!new File(entry).getName().startsWith("scope-tracer-")) {
        classPath.add(entry);
      }
    }
    List<String> command = new ArrayList<>();
    command.add(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
    command.add("-javaagent:" + agentJar + "=record=scope-tracer-demo.jfr");
    command.add("--enable-preview");
    command.add("-D" + CHILD + "=true");
    command.add("-classpath");
    command.add(String.join(File.pathSeparator, classPath));
    command.add(ScopeTracerDemo.class.getName());
    command.addAll(List.of(args));

    // SIGTERM to this JVM must not orphan the child: stop it too, and give its own shutdown hooks
    // (the agent writing the report) a few seconds. Ctrl-C reaches both JVMs already. The hook is
    // registered before the child is started and takes the lock the start holds, so a signal that
    // arrives while the child is being started waits for it, and one that arrives before it is
    // started finds nothing to stop.
    Object lock = new Object();
    Process[] child = new Process[1];
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  Process p;
                  synchronized (lock) {
                    p = child[0];
                  }
                  if (p == null) {
                    return;
                  }
                  p.destroy();
                  try {
                    if (!p.waitFor(10, TimeUnit.SECONDS)) {
                      p.destroyForcibly();
                    }
                  } catch (InterruptedException e) {
                    p.destroyForcibly();
                  }
                }));
    synchronized (lock) {
      child[0] = new ProcessBuilder(command).inheritIO().start();
    }
    return child[0].waitFor();
  }
}
