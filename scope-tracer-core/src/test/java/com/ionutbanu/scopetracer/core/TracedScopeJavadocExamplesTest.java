package com.ionutbanu.scopetracer.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.StructuredTaskScope.Joiner;
import java.util.concurrent.StructuredTaskScope.Subtask;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Every usage example in {@link TracedScope}'s Javadoc, copied verbatim (only the indentation
 * differs) and run against stub services. {@link #everyJavadocExampleIsCopiedHereVerbatim()} reads
 * {@code TracedScope.java} and fails if an example is not in this file, so the two cannot drift.
 */
class TracedScopeJavadocExamplesTest {

  // --- stubs the examples call ---

  record Cart(long id) {}

  record Quote(long cartId) {}

  record Reservation(long cartId) {}

  record Checkout(Quote quote, Reservation reservation) {}

  record Request(String path) {}

  record Response(String from) {}

  record User(long id) {}

  record Session(User user, Cart cart) {}

  /** Counts calls, so a test can tell the example's tasks ran. */
  private final AtomicInteger calls = new AtomicInteger();

  private final Cart cart = new Cart(7);
  private final long id = 42;
  private final Request request = new Request("/item");
  private final ThreadFactory factory = Thread.ofPlatform().factory();

  private final PricingService pricingService = new PricingService();
  private final InventoryService inventoryService = new InventoryService();
  private final RemoteService primaryService = new RemoteService("primary");
  private final RemoteService fallbackService = new RemoteService("fallback");
  private final RemoteService serviceA = new RemoteService("a");
  private final RemoteService serviceB = new RemoteService("b");
  private final UserService userService = new UserService();
  private final CartService cartService = new CartService();
  private final AuditLog auditLog = new AuditLog();

  final class PricingService {
    Quote quote(Cart cart) {
      calls.incrementAndGet();
      return new Quote(cart.id());
    }
  }

  final class InventoryService {
    Reservation reserve(Cart cart) {
      calls.incrementAndGet();
      return new Reservation(cart.id());
    }
  }

  final class RemoteService {
    private final String name;

    RemoteService(String name) {
      this.name = name;
    }

    Response call(Request request) {
      calls.incrementAndGet();
      return new Response(name);
    }

    Response call() {
      calls.incrementAndGet();
      return new Response(name);
    }
  }

  final class UserService {
    User findById(long id) {
      calls.incrementAndGet();
      return new User(id);
    }
  }

  final class CartService {
    Cart load(long id) {
      calls.incrementAndGet();
      return new Cart(id);
    }
  }

  final class AuditLog {
    void record(Cart cart) {
      calls.incrementAndGet();
    }
  }

  // --- class Javadoc ---

  private Checkout classDefaultJoiner() throws Exception {
    try (var scope = TracedScope.open("checkout-flow")) {
      Subtask<Quote> pricing = scope.fork(() -> pricingService.quote(cart));
      Subtask<Reservation> inventory = scope.fork(() -> inventoryService.reserve(cart));
      scope.join(); // throws ExecutionException if either subtask failed
      return new Checkout(pricing.get(), inventory.get());
    }
  }

  @Test
  void classDefaultJoinerExample() throws Exception {
    assertThat(classDefaultJoiner()).isEqualTo(new Checkout(new Quote(7), new Reservation(7)));
  }

  @Test
  void classRaceExample() throws Exception {
    try (var scope = TracedScope.open("service-race", Joiner.<Response>anySuccessfulOrThrow())) {
      scope.fork(() -> primaryService.call(request));
      scope.fork(() -> fallbackService.call(request));
      Response first = scope.join(); // first successful result; losers are cancelled
    }
    assertThat(calls.get()).isBetween(1, 2);
  }

  @Test
  void classCollectAllExample() throws Exception {
    try (var scope = TracedScope.open("fan-out", Joiner.<Response>allSuccessfulOrThrow())) {
      scope.fork(() -> serviceA.call());
      scope.fork(() -> serviceB.call());
      List<Response> results = scope.join(); // all results, in the order they were forked
    }
    assertThat(calls).hasValue(2);
  }

  @Test
  void classAllUntilExample() throws Exception {
    try (var scope = TracedScope.open("best-effort", Joiner.<Response>allUntil(s -> false))) {
      scope.fork(() -> serviceA.call());
      scope.fork(() -> serviceB.call());
      List<Subtask<Response>> subtasks = scope.join(); // never throws for a failed subtask
    }
    assertThat(calls).hasValue(2);
  }

  private Quote classTimeout() throws Exception {
    try (var scope = TracedScope.open("deadline", cfg -> cfg.withTimeout(Duration.ofSeconds(5)))) {
      Subtask<Quote> quote = scope.fork(() -> pricingService.quote(cart));
      scope.join(); // throws if the timeout expires first
      return quote.get();
    }
  }

  @Test
  void classTimeoutExample() throws Exception {
    assertThat(classTimeout()).isEqualTo(new Quote(7));
  }

  // --- the four factories ---

  @Test
  void openNameJoinerConfigExample() throws Exception {
    try (var scope =
        TracedScope.open(
            "race",
            Joiner.<Response>anySuccessfulOrThrow(),
            cfg -> cfg.withThreadFactory(factory))) {
      scope.fork(() -> primaryService.call(request));
      scope.fork(() -> fallbackService.call(request));
      Response first = scope.join();
    }
    assertThat(calls.get()).isBetween(1, 2);
  }

  @Test
  void openNameJoinerExample() throws Exception {
    try (var scope = TracedScope.open("race", Joiner.<Response>anySuccessfulOrThrow())) {
      scope.fork(() -> primaryService.call(request));
      scope.fork(() -> fallbackService.call(request));
      Response first = scope.join();
    }
    assertThat(calls.get()).isBetween(1, 2);
  }

  private Quote openNameConfig() throws Exception {
    try (var scope =
        TracedScope.open("checkout", cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
      Subtask<Quote> quote = scope.fork(() -> pricingService.quote(cart));
      scope.join();
      return quote.get();
    }
  }

  @Test
  void openNameConfigExample() throws Exception {
    assertThat(openNameConfig()).isEqualTo(new Quote(7));
  }

  private Quote openName() throws Exception {
    try (var scope = TracedScope.open("checkout")) {
      Subtask<Quote> quote = scope.fork(() -> pricingService.quote(cart));
      scope.join();
      return quote.get();
    }
  }

  @Test
  void openNameExample() throws Exception {
    assertThat(openName()).isEqualTo(new Quote(7));
  }

  // --- instance methods ---

  @Test
  void nameExample() throws Exception {
    try (var scope = TracedScope.open("checkout")) {
      String name = scope.name(); // "checkout"
      scope.join();
    }
  }

  private Quote forkCallable() throws Exception {
    try (var scope = TracedScope.open("lookup")) {
      Subtask<Quote> quote = scope.fork(() -> pricingService.quote(cart));
      scope.join();
      return quote.get();
    }
  }

  @Test
  void forkCallableExample() throws Exception {
    assertThat(forkCallable()).isEqualTo(new Quote(7));
  }

  @Test
  void forkRunnableExample() throws Exception {
    try (var scope = TracedScope.open("notify")) {
      scope.fork(() -> auditLog.record(cart));
      scope.join();
    }
    assertThat(calls).hasValue(1);
  }

  private Session forkLabelled() throws Exception {
    try (var scope = TracedScope.open("checkout")) {
      Subtask<User> user = scope.fork("findUser", () -> userService.findById(id));
      Subtask<Cart> cart = scope.fork("loadCart", () -> cartService.load(id));
      scope.join();
      return new Session(user.get(), cart.get());
    }
  }

  @Test
  void forkLabelledExample() throws Exception {
    assertThat(forkLabelled()).isEqualTo(new Session(new User(42), new Cart(42)));
  }

  private void join() throws InterruptedException {
    try (var scope = TracedScope.open("lookup", Joiner.<Response>anySuccessfulOrThrow())) {
      scope.fork(() -> primaryService.call(request));
      scope.fork(() -> fallbackService.call(request));
      Response found = scope.join();
    } catch (ExecutionException e) {
      // both calls failed; e.getCause() is the first failure
    }

    try (var scope =
        TracedScope.open(
            "io", Joiner.<Response, IOException>allSuccessfulOrThrow(IOException::new))) {
      scope.fork(() -> serviceA.call());
      List<Response> results = scope.join();
    } catch (IOException e) {
      // a call failed; e.getCause() is its exception
    }
  }

  @Test
  void joinExample() throws Exception {
    join();
    assertThat(calls.get()).isBetween(2, 3); // the race's loser may be cancelled before its call
  }

  @Test
  void isCancelledExample() throws Exception {
    try (var scope = TracedScope.open("race", Joiner.<Response>anySuccessfulOrThrow())) {
      scope.fork(() -> primaryService.call(request));
      scope.fork(() -> fallbackService.call(request));
      Response first = scope.join();
      boolean losersCancelled = scope.isCancelled(); // true: the first success cancelled it
    }
    assertThat(calls.get()).isBetween(1, 2);
  }

  @Test
  void closeExample() throws Exception {
    var scope = TracedScope.open("manual");
    try {
      scope.fork(() -> auditLog.record(cart));
      scope.join();
    } finally {
      scope.close(); // or use try-with-resources
    }
    assertThat(calls).hasValue(1);
  }

  // --- the examples here are TracedScope's ---

  private static final Pattern EXAMPLE =
      Pattern.compile("<pre>\\{@code\\n(.*?)\\n\\s*\\*\\s*}</pre>", Pattern.DOTALL);

  @Test
  void everyJavadocExampleIsCopiedHereVerbatim() throws IOException {
    var javadoc =
        Files.readString(Path.of("src/main/java/com/ionutbanu/scopetracer/core/TracedScope.java"));
    var tests =
        normalize(
            Files.readString(
                Path.of(
                    "src/test/java/com/ionutbanu/scopetracer/core/"
                        + "TracedScopeJavadocExamplesTest.java")));
    var examples = new ArrayList<String>();
    var matcher = EXAMPLE.matcher(javadoc);
    while (matcher.find()) {
      examples.add(normalize(matcher.group(1).replaceAll("(?m)^\\s*\\*", "")));
    }
    assertThat(examples).hasSize(16);
    assertThat(examples)
        .allSatisfy(
            example -> {
              assertThat(tests).contains(example);
              assertThat(example).doesNotContain("(Response)", "List<Object>");
            });
  }

  /** Each line without its leading whitespace, so only indentation may differ. */
  private static String normalize(String code) {
    return String.join(
        "\n", code.lines().map(String::strip).filter(line -> !line.isEmpty()).toList());
  }
}
