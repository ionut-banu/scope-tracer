package com.ionutbanu.scopetracer.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/**
 * A test-only agent for {@link AgentStartupSignalIT}, listed first on the command line. It pauses
 * the JVM at a known point in the start-up of the agent listed after it (while its Premain-Class
 * loads): the first time a class whose internal name starts with the prefix in its agent argument
 * is loaded, it prints {@code GATE-READY} and blocks in a native {@code System.in.read()} until the
 * test writes a byte. The block is safepoint-safe, so the JVM can begin shutting down while the
 * other agent starts up and is paused. A shutdown hook prints {@code GATE-HOOK} once the JVM has
 * started to exit.
 *
 * <p>The gate cannot sit in its own {@code premain}: an agent listed second is never started if
 * shutdown has already begun.
 */
public final class StartupGateAgent {

  static final String READY = "GATE-READY";
  static final String HOOK = "GATE-HOOK";

  private StartupGateAgent() {}

  public static void premain(String prefix, Instrumentation inst) throws Exception {
    Runtime.getRuntime().addShutdownHook(new Thread(() -> System.err.println(HOOK)));
    // loaded now, so that blocking inside the transformer loads nothing new
    Class.forName("java.io.FileInputStream");
    inst.addTransformer(new Gate(prefix));
  }

  private static final class Gate implements ClassFileTransformer {
    private final String prefix;
    private volatile boolean fired;

    Gate(String prefix) {
      this.prefix = prefix;
    }

    @Override
    public byte[] transform(
        ClassLoader loader,
        String className,
        Class<?> classBeingRedefined,
        ProtectionDomain protectionDomain,
        byte[] classfileBuffer) {
      if (!fired && className != null && className.startsWith(prefix)) {
        fired = true;
        System.err.println(READY + " " + className);
        try {
          System.in.read();
        } catch (Exception e) {
          // released or closed: carry on
        }
      }
      return null;
    }
  }
}
