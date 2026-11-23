package com.ionutbanu.scopetracer.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A SIGTERM that arrives while the agent is installing itself (issue #167). On JDK 26 and later a
 * JVM signalled while any agent's {@code premain} is loading classes can hang in its exit path
 * (reported to OpenJDK; no bug ID yet); the agent cannot avoid it. So the test is differential: a
 * control child with the same gate and an agent whose {@code premain} loads many JDK classes says
 * whether this JDK has the bug. If it exits, the JDK is fine and the real agent must exit too. If
 * it hangs, the JDK is at fault and the test passes, saying so. The test fails only when the
 * control exits and the agent's JVM does not.
 *
 * <p>A signal sent after a delay would be a race, so the child is paused instead: {@link
 * StartupGateAgent}, listed first, blocks in a native read at the first class load with the real
 * agent's package prefix. That is while the agent's {@code Premain-Class} loads, just before {@code
 * premain} is entered, not inside it; the JVM is still paused in the agent's start-up, and the
 * unmodified agent hung in every gated run. The test sends SIGTERM, waits for the gate's shutdown
 * hook to print {@code GATE-HOOK}, then writes one byte to release the read. It waits on output
 * lines only, never on time. Every child is closed in a {@code finally}, which kills it if it is
 * still alive.
 */
class AgentStartupSignalIT {

  private static final Duration EXIT_WITHIN = Duration.ofSeconds(15);
  private static final String AGENT_PACKAGE = "com/ionutbanu/scopetracer/agent/";

  private static String agent;

  @BeforeAll
  static void requireFatJar() {
    agent = ChildJvm.agentJar();
    assumeTrue(
        agent != null && ChildJvm.testClassesDir() != null,
        "Skipping agent IT: run 'mvn verify' to build the fat-jar and execute these tests.");
  }

  @Test
  void sigtermDuringAgentStartupExitsUnlessTheJdkItselfHangs(@TempDir Path dir) throws Exception {
    Path gate = agentJar(dir, StartupGateAgent.class);
    Path control = agentJar(dir, ClassLoadingControlAgent.class);

    // the control: the gate and an agent that loads many classes, no scope-tracer code
    var controlRun =
        signalledAtGate(
            dir,
            List.of(
                "-javaagent:" + gate + "=" + AGENT_PACKAGE + "ClassLoadingControlAgent",
                "-javaagent:" + control),
            "none");
    // the real agent, signalled at its first class
    var agentRun =
        signalledAtGate(
            dir,
            List.of(
                "-javaagent:" + gate + "=" + AGENT_PACKAGE + "ScopeTracerAgent",
                "-javaagent:" + agent),
            "none");

    assertThat(agentRun.stderr)
        .as("a signal during start-up is not a failure the agent reports")
        .doesNotContain(ChildJvm.AGENT_PREFIX + " WARN");
    if (controlRun.exited) {
      assertThat(agentRun.exited)
          .as(
              "the JDK exits when signalled in a premain that loads classes, so the agent's JVM"
                  + " must exit within %s too; stderr:%n%s",
              EXIT_WITHIN, agentRun.stderr)
          .isTrue();
    } else {
      System.out.println(
          "JDK shutdown hang present: "
              + System.getProperty("java.version")
              + " (control JVM did not exit; agent JVM "
              + (agentRun.exited ? "exited" : "did not exit")
              + ")");
    }
  }

  @Test
  void sigtermAfterPremainExitsWithAndWithoutRecord(@TempDir Path dir) throws Exception {
    for (String args : new String[] {"", "=record"}) {
      Path work = Files.createDirectory(dir.resolve(args.isEmpty() ? "plain" : "record"));
      try (var child = ChildJvm.start(work, List.of("-javaagent:" + agent + args), "stdin")) {
        child.awaitStdout(ExitSubject.READY);
        child.terminate();
        assertThat(child.awaitExit(EXIT_WITHIN))
            .as(
                "exit within %s after SIGTERM, agent argument '%s'; stderr:%n%s",
                EXIT_WITHIN, args, child.stderrSoFar())
            .isTrue();
        assertThat(child.stderrSoFar())
            .as("agent argument '%s'", args)
            .doesNotContain(ChildJvm.AGENT_PREFIX + " WARN");
      }
    }
  }

  /** What one gated child did. */
  private record Outcome(boolean exited, String stderr) {}

  /**
   * Runs the subject with {@code jvmArgs}, sends SIGTERM once the gate has paused the JVM inside
   * the agent's {@code premain}, releases the gate after the JVM has started to exit, and reports
   * whether the child ended within {@link #EXIT_WITHIN}. A child still alive is killed.
   */
  private static Outcome signalledAtGate(Path dir, List<String> jvmArgs, String subject)
      throws Exception {
    try (var child = ChildJvm.start(dir, jvmArgs, subject)) {
      child.awaitStderrStartingWith(StartupGateAgent.READY);
      child.terminate();
      child.awaitStderrStartingWith(StartupGateAgent.HOOK);
      child.writeStdin("go");
      boolean exited = child.awaitExit(EXIT_WITHIN);
      if (!exited) {
        child.kill();
      }
      return new Outcome(exited, child.stderrSoFar());
    }
  }

  /** A jar holding {@code main} and its nested classes, with {@code main} as the Premain-Class. */
  private static Path agentJar(Path dir, Class<?> main) throws IOException {
    var manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    manifest.getMainAttributes().put(new Attributes.Name("Premain-Class"), main.getName());
    Path jar = dir.resolve(main.getSimpleName() + ".jar");
    Path classes = Path.of(ChildJvm.testClassesDir()).resolve(AGENT_PACKAGE);
    try (OutputStream out = Files.newOutputStream(jar);
        var jos = new JarOutputStream(out, manifest);
        Stream<Path> files = Files.list(classes)) {
      for (Path file : files.toList()) {
        String name = file.getFileName().toString();
        if (name.startsWith(main.getSimpleName()) && name.endsWith(".class")) {
          jos.putNextEntry(new JarEntry(AGENT_PACKAGE + name));
          Files.copy(file, jos);
          jos.closeEntry();
        }
      }
    }
    return jar;
  }
}
