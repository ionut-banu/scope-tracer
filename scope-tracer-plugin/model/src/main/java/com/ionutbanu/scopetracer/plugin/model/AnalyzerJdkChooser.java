package com.ionutbanu.scopetracer.plugin.model;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Picks the JDK that runs the bundled analyzer from the JDKs the IDE already knows, so neither
 * plugin action has to ask for a path. Pure logic with no IntelliJ dependency: the plugin passes
 * each candidate as name, home and {@code java} launcher, and a probe that runs {@code java
 * -version}.
 *
 * <p>The order of choice, first usable one wins (spec §3, Plugin):
 *
 * <ol>
 *   <li>the explicit JDK chosen on the settings page (a {@code java} path, also what 0.6.0 saved);
 *   <li>the JDK the traced run itself used, for "Run with Scope Tracer";
 *   <li>the project SDK;
 *   <li>the registered JDKs, highest major version first, then the newest full version, then by
 *       name, as IntelliJ stored their versions.
 * </ol>
 *
 * <p>A candidate is usable when its home exists, its {@code java} is executable and the probe's
 * {@code java -version} says {@link TracedLaunch#MIN_JDK_MAJOR} or newer. The version IntelliJ
 * stored only orders the registered JDKs; whether one is usable is always the probe's answer.
 * Candidates are probed lazily: once one is usable the rest are not probed, and a {@code java}
 * already tried is not probed twice.
 *
 * <pre>{@code
 * var choice = AnalyzerJdkChooser.choose(
 *     new Inputs(explicit, tracedRun, projectSdk, registered), AnalyzerJdk.probe(tempDir));
 * if (choice.chosen().isPresent()) { ... choice.chosen().get().candidate().java() ... }
 * else { notifyError(choice.failureMessage()); }
 * }</pre>
 */
public final class AnalyzerJdkChooser {

  /** Where a candidate came from; also the order of choice. */
  public enum Source {
    EXPLICIT,
    TRACED_RUN,
    PROJECT_SDK,
    REGISTERED
  }

  /**
   * One JDK the analyzer could run on.
   *
   * @param name shown to the user: the SDK's name, or the path for an explicit {@code java}.
   * @param home the JDK home, or {@code null} when only the launcher is known (an explicit path).
   * @param java the {@code java} launcher, or {@code null} when IntelliJ could not name one.
   * @param storedVersion the version string IntelliJ stored, or {@code null}; orders the
   *     registered JDKs and is never used to decide whether one is usable.
   * @param unreadable why the IDE's description of this JDK could not be read (an invalid path,
   *     say), or {@code null}; such a candidate is skipped with this reason and never probed.
   */
  public record Candidate(
      Source source, String name, Path home, Path java, String storedVersion, String unreadable) {

    /** A candidate the IDE described without problems. */
    public Candidate(Source source, String name, Path home, Path java, String storedVersion) {
      this(source, name, home, java, storedVersion, null);
    }

    /**
     * A candidate from the strings IntelliJ stores. A home or launcher that is not a valid path
     * makes a candidate that is skipped with the reason, instead of failing the whole choice.
     */
    public static Candidate of(
        Source source, String name, String home, String java, String storedVersion) {
      try {
        return new Candidate(
            source,
            name,
            home == null ? null : Path.of(home),
            java == null ? null : Path.of(java),
            storedVersion);
      } catch (InvalidPathException e) {
        return unreadable(source, name, storedVersion, "invalid path (" + e.getMessage() + ")");
      }
    }

    /** A candidate whose description could not be read; skipped with {@code reason}. */
    public static Candidate unreadable(
        Source source, String name, String storedVersion, String reason) {
      return new Candidate(source, name, null, null, storedVersion, reason);
    }
  }

  /** What the choice is made from; any of them may be absent or empty. */
  public record Inputs(
      Optional<Candidate> explicit,
      Optional<Candidate> tracedRun,
      Optional<Candidate> projectSdk,
      List<Candidate> registered) {
    public Inputs {
      registered = List.copyOf(registered);
    }
  }

  /** What checking one candidate found. */
  public sealed interface Verdict permits Chosen, Skipped {}

  /** A usable candidate and the major version its probe reported. */
  public record Chosen(Candidate candidate, int major) implements Verdict {}

  /** A candidate that was tried and skipped, and why, in a few words. */
  public record Skipped(Candidate candidate, String reason) implements Verdict {
    /** One line for a notification: {@code temurin-26: JDK 26}. */
    public String line() {
      return candidate.name() + ": " + reason;
    }
  }

  /**
   * The outcome: the JDK to use (if any) and every candidate skipped on the way, in the order
   * tried.
   */
  public record Choice(Optional<Chosen> chosen, List<Skipped> skipped) {
    public Choice {
      skipped = List.copyOf(skipped);
    }

    /** The explicit choice, when there was one and it was skipped. */
    public Optional<Skipped> explicitSkipped() {
      return skipped.stream().filter(s -> s.candidate().source() == Source.EXPLICIT).findFirst();
    }

    /**
     * The warning shown when the explicit JDK was skipped but another was found, or empty. Names
     * the chosen JDK and why it was skipped.
     */
    public Optional<String> explicitWarning() {
      return explicitSkipped()
          .flatMap(
              skippedExplicit ->
                  chosen.map(
                      c ->
                          "The JDK chosen in Settings → Tools → Scope Tracer, "
                              + skippedExplicit.candidate().name()
                              + ", cannot run the analyzer ("
                              + skippedExplicit.reason()
                              + "). Using "
                              + c.candidate().name()
                              + " instead."));
    }

    /**
     * The error shown when nothing is usable: what is needed, then one line per candidate tried.
     */
    public List<String> failureLines() {
      var lines = new ArrayList<String>();
      lines.add(
          "The scope-tracer analyzer needs JDK "
              + TracedLaunch.MIN_JDK_MAJOR
              + " or newer, and none was found"
              + (skipped.isEmpty() ? " (no JDK is registered in the IDE)." : ". Tried:"));
      skipped.forEach(s -> lines.add(s.line()));
      return List.copyOf(lines);
    }
  }

  private static final Pattern NUMBERS = Pattern.compile("\\d+(?:[._]\\d+)*");

  private AnalyzerJdkChooser() {}

  /**
   * Chooses the analyzer's JDK.
   *
   * @param probe from a {@code java} launcher to what its {@code java -version} printed, {@code
   *     ""} when it could not be run or did not answer in time ({@link AnalyzerJdk#probe}).
   */
  public static Choice choose(Inputs inputs, Function<Path, String> probe) {
    var ordered = new ArrayList<Candidate>();
    inputs.explicit().ifPresent(ordered::add);
    inputs.tracedRun().ifPresent(ordered::add);
    inputs.projectSdk().ifPresent(ordered::add);
    ordered.addAll(byNewest(inputs.registered()));

    var skipped = new ArrayList<Skipped>();
    var tried = new HashSet<Path>();
    for (var candidate : ordered) {
      if (candidate.java() != null && !tried.add(candidate.java().toAbsolutePath().normalize())) {
        continue; // the same java under another name: already skipped
      }
      switch (check(candidate, probe)) {
        case Chosen chosen -> {
          return new Choice(Optional.of(chosen), skipped);
        }
        case Skipped s -> skipped.add(s);
      }
    }
    return new Choice(Optional.empty(), skipped);
  }

  /**
   * Checks a {@code java} the user chose on the settings page; returns why it is refused, naming
   * JDK 27, or empty when it can run the analyzer.
   */
  public static Optional<String> refusalOfExplicit(Path java, Function<Path, String> probe) {
    if (!Files.isRegularFile(java) || !Files.isExecutable(java)) {
      return Optional.of(
          "No java launcher at "
              + java
              + "; the scope-tracer analyzer needs JDK "
              + TracedLaunch.MIN_JDK_MAJOR
              + " or newer.");
    }
    return AnalyzerJdk.refusal(java.toString(), probe.apply(java));
  }

  /**
   * Whether the settings page's selection differs from the saved setting, so Apply has something
   * to do. {@code saved} is the raw saved text (empty for "Automatic"), {@code selected} the
   * {@code java} the page selects (empty for "Automatic", or for disk text that is not a path).
   * A saved value that is not a valid path never equals a selection, so the user can always
   * replace it, and choosing "Automatic" over it removes it.
   */
  public static boolean settingChanged(Optional<String> saved, Optional<Path> selected) {
    if (saved.isEmpty() || selected.isEmpty()) {
      return saved.isPresent() != selected.isPresent();
    }
    try {
      return !Path.of(saved.get()).equals(selected.get());
    } catch (InvalidPathException e) {
      return true;
    }
  }

  /**
   * The {@code java} launcher for a path the user chose from disk: the path itself when it is a
   * file, otherwise {@code <home>/bin/java} ({@code java.exe} when {@code windows}).
   */
  public static Path launcherFor(Path chosen, boolean windows) {
    if (Files.isRegularFile(chosen)) {
      return chosen;
    }
    return chosen.resolve("bin").resolve(windows ? "java.exe" : "java");
  }

  /** {@link Chosen} when usable, otherwise {@link Skipped}. */
  static Verdict check(Candidate candidate, Function<Path, String> probe) {
    if (candidate.unreadable() != null) {
      return new Skipped(candidate, candidate.unreadable());
    }
    if (candidate.home() != null && !Files.isDirectory(candidate.home())) {
      return new Skipped(candidate, "home not found");
    }
    var java = candidate.java();
    if (java == null || !Files.isRegularFile(java) || !Files.isExecutable(java)) {
      return new Skipped(candidate, "no executable java");
    }
    int major = AnalyzerJdk.parseVersionOutput(probe.apply(java));
    if (major < 0) {
      return new Skipped(candidate, "could not read its java -version");
    }
    if (major < TracedLaunch.MIN_JDK_MAJOR) {
      return new Skipped(candidate, "JDK " + major);
    }
    return new Chosen(candidate, major);
  }

  /** Highest stored major first, then the newest full version, then by name. */
  static List<Candidate> byNewest(List<Candidate> registered) {
    Comparator<Candidate> byMajor =
        Comparator.comparingInt(c -> TracedLaunch.parseJavaMajor(c.storedVersion()));
    Comparator<Candidate> byFull = (a, b) -> compareNumbers(numbers(a), numbers(b));
    return registered.stream()
        .sorted(
            byMajor
                .reversed()
                .thenComparing(byFull.reversed())
                .thenComparing(Candidate::name, Comparator.nullsLast(Comparator.naturalOrder())))
        .toList();
  }

  private static int[] numbers(Candidate c) {
    if (c.storedVersion() == null) return new int[0];
    var m = NUMBERS.matcher(c.storedVersion());
    if (!m.find()) return new int[0];
    var parts = m.group().split("[._]");
    var out = new int[parts.length];
    for (int i = 0; i < parts.length; i++) {
      try {
        out[i] = Integer.parseInt(parts[i]);
      } catch (NumberFormatException e) {
        out[i] = 0;
      }
    }
    return out;
  }

  private static int compareNumbers(int[] a, int[] b) {
    for (int i = 0; i < Math.max(a.length, b.length); i++) {
      int x = i < a.length ? a[i] : 0;
      int y = i < b.length ? b[i] : 0;
      if (x != y) return Integer.compare(x, y);
    }
    return 0;
  }
}
