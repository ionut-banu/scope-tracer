package com.ionutbanu.scopetracer.agent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs the JDK's {@code jcmd} against a child JVM, as a user of the agent would. */
final class Jcmd {

  private Jcmd() {}

  /** Runs {@code jcmd <pid> <command...>}, fails unless it exits 0, and returns its output. */
  static String run(long pid, String... command) throws Exception {
    var args = new ArrayList<String>();
    args.add(ChildJvm.javaHome().resolve("bin").resolve("jcmd").toString());
    args.add(Long.toString(pid));
    args.addAll(List.of(command));
    Process jcmd = new ProcessBuilder(args).redirectErrorStream(true).start();
    try {
      String output = new String(jcmd.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (!jcmd.waitFor(ChildJvm.TIMEOUT.toSeconds(), TimeUnit.SECONDS) || jcmd.exitValue() != 0) {
        throw new AssertionError("jcmd " + String.join(" ", command) + " failed:\n" + output);
      }
      return output;
    } finally {
      jcmd.destroyForcibly();
    }
  }
}
