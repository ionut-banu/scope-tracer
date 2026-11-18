package com.ionutbanu.scopetracer.demos;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The committed gallery against the scenarios that produced it. It runs nothing and times nothing:
 * the committed reports hold times, so only their shape is compared here, and {@code GalleryMain
 * --check} (a CI step) is what regenerates and compares the markup.
 */
class GalleryTest {

  private static final long PAGE_BUDGET = 40 * 1024;
  private static final long REPORT_BUDGET = 120 * 1024;

  private static Path repositoryRoot() {
    var dir = Path.of("").toAbsolutePath();
    while (dir != null && !Files.isDirectory(dir.resolve("docs"))) {
      dir = dir.getParent();
    }
    assertThat(dir).as("a directory with docs/ above the working directory").isNotNull();
    return dir;
  }

  private static String read(String relative) throws IOException {
    return Files.readString(repositoryRoot().resolve(relative), StandardCharsets.UTF_8);
  }

  private static List<String> committedFiles() {
    var files = new ArrayList<String>();
    files.add("docs/gallery/index.html");
    GalleryCatalog.ALL.forEach(s -> files.add(s.reportFile()));
    return files;
  }

  @Test
  void theCatalogHasTheNineSectionsInOrder() {
    assertThat(GalleryCatalog.ALL)
        .extracting(GalleryScenario::id)
        .containsExactly(
            "fail-fast",
            "all-successful",
            "any-successful",
            "await-all",
            "custom-joiner",
            "timeout",
            "nested",
            "ignores-interrupt",
            "order-processing");
  }

  @Test
  void everyScenarioHasACommittedReportAndASectionWithItsOwnLink() throws Exception {
    var page = read("docs/gallery/index.html");
    for (var s : GalleryCatalog.ALL) {
      assertThat(repositoryRoot().resolve(s.reportFile())).as(s.id()).isRegularFile();
      assertThat(page)
          .contains("<section id=\"" + s.id() + "\">")
          .contains("<iframe src=\"" + s.reportHref() + "\" loading=\"lazy\"")
          .contains("<a href=\"" + s.reportHref() + "\">Open the full " + s.id() + " report</a>");
    }
    assertThat(page).contains("../example/order-processing-report.html");
  }

  @Test
  void theCommittedPageIsThePageTheGeneratorWrites() throws Exception {
    var generated = GalleryPage.render(repositoryRoot(), GalleryCatalog.ALL);

    assertThat(read("docs/gallery/index.html")).isEqualTo(generated);
  }

  @Test
  void aSnippetIsTheSourceBetweenItsMarkersAndChangingTheSourceChangesThePage(@TempDir Path copy)
      throws Exception {
    for (var s : GalleryCatalog.ALL) {
      var target = copy.resolve(s.sourceFile());
      Files.createDirectories(target.getParent());
      Files.copy(
          repositoryRoot().resolve(s.sourceFile()),
          target,
          java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
    assertThat(GalleryPage.render(copy, GalleryCatalog.ALL))
        .isEqualTo(read("docs/gallery/index.html"));

    var source = copy.resolve(GalleryCatalog.ALL.get(0).sourceFile());
    Files.writeString(
        source,
        Files.readString(source).replace("inventory service unavailable", "inventory is down"));

    var changed = GalleryPage.render(copy, GalleryCatalog.ALL);
    assertThat(changed).contains("inventory is down").isNotEqualTo(read("docs/gallery/index.html"));
  }

  @Test
  void aMissingMarkerIsAnErrorNotAnEmptySnippet() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> Snippets.extract(List.of("a", "b"), "nope", "X.java"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("gallery:begin nope")
        .hasMessageContaining("X.java");
  }

  @Test
  void snippetsAreDedentedAndKeepTheirRelativeIndentation() {
    var lines =
        List.of(
            "class A {",
            "  void m() {",
            "    // gallery:begin x",
            "    try (var s = open()) {",
            "      s.go();",
            "    }",
            "",
            "    // gallery:end x",
            "  }",
            "}");

    assertThat(Snippets.extract(lines, "x", "A.java"))
        .isEqualTo("try (var s = open()) {\n  s.go();\n}\n");
  }

  @Test
  void everyCommittedReportShowsTheShapeItsScenarioDeclares() throws Exception {
    for (var s : GalleryCatalog.ALL) {
      var observed = ReportRows.observe(read(s.reportFile()));

      assertThat(s.shape().structureProblems(observed)).as(s.id()).isEmpty();
    }
  }

  @Test
  void theReportParserReadsNestingFromTheRenderedReport() throws Exception {
    var observed = ReportRows.observe(read("docs/gallery/reports/nested.html"));

    assertThat(observed)
        .extracting(
            Shape.ObservedScope::name, Shape.ObservedScope::parent, Shape.ObservedScope::parentTask)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple("request", null, null),
            org.assertj.core.groups.Tuple.tuple("checkout", "request", "handle-checkout"),
            org.assertj.core.groups.Tuple.tuple("payment", "checkout", "take-payment"));
  }

  @Test
  void theOrderReportHasNineRootScopesAndOneDeclinedOrder() throws Exception {
    var html = read("docs/example/order-processing-report.html");
    var observed = ReportRows.observe(html);

    assertThat(observed).hasSize(19);
    assertThat(observed.stream().filter(s -> s.parent() == null)).hasSize(9);
    assertThat(
            observed.stream()
                .filter(s -> s.name().startsWith("order-processing-"))
                .filter(s -> s.tasks().stream().anyMatch(t -> t.outcome().equals("failed"))))
        .extracting(Shape.ObservedScope::name)
        .containsExactly("order-processing-ORD-002");
    assertThat(observed).noneMatch(s -> s.name().equals("order-dispatch-ORD-002"));
  }

  @Test
  void theLandingPagesScopeTwoIsTheDeclinedOrderWithACancelledSibling() throws Exception {
    var html = read("docs/example/order-processing-report.html");
    int start = html.indexOf("id=\"scope-2\"");
    assertThat(start).isPositive();
    var section = html.substring(start, html.indexOf("</section>", start));

    assertThat(section)
        .contains("data-scope=\"order-processing-ORD-002\"")
        .contains("data-outcome=\"failed\"")
        .contains("outcome-failed")
        .contains("outcome-cancelled");
    // the landing page opens exactly this fragment
    assertThat(read("docs/index.html")).contains("order-processing-report.html#scope-2");
  }

  @Test
  void theExampleReportStartsWithTheBannerTheCommandInserts() throws Exception {
    assertThat(read("docs/example/order-processing-report.html"))
        .contains("<body>\n" + GalleryMain.BANNER + "<h1>scope-tracer report</h1>");
    for (var s : GalleryCatalog.ALL) {
      if (!s.banner()) {
        assertThat(read(s.reportFile())).doesNotContain("This is an example report");
      }
    }
  }

  @Test
  void noFileLoadsAnythingFromTheNetwork() throws Exception {
    for (var file : committedFiles()) {
      assertThat(NetworkCheck.problems(read(file))).as(file).isEmpty();
    }
  }

  @Test
  void theSizeBudgetsHold() throws Exception {
    var root = repositoryRoot();
    assertThat(Files.size(root.resolve("docs/gallery/index.html")))
        .isLessThanOrEqualTo(PAGE_BUDGET);
    for (var s : GalleryCatalog.ALL) {
      assertThat(Files.size(root.resolve(s.reportFile())))
          .as(s.id())
          .isLessThanOrEqualTo(REPORT_BUDGET);
    }
  }

  @Test
  void noFileHoldsAnAbsolutePath() throws Exception {
    var checkout = repositoryRoot().toString();
    for (var file : committedFiles()) {
      var text = read(file);
      assertThat(text)
          .as(file)
          .doesNotContain(checkout)
          .doesNotContain("/Users/")
          .doesNotContain("/home/");
      assertThat(text).as(file).doesNotContainPattern("[A-Za-z]:\\\\\\\\");
    }
  }

  @Test
  void thePageSaysWhatItNeedsToSay() throws Exception {
    var page = read("docs/gallery/index.html");

    assertThat(page)
        .contains("JDK 27")
        .contains("<code>TracedScope</code>")
        .contains("The Java agent gives the same report")
        .contains("https://github.com/ionut-banu/scope-tracer#readme")
        .contains("no event records which joiner")
        .contains("name=\"viewport\"")
        .contains("prefers-color-scheme: dark")
        .doesNotContain("/releases/download/");
  }
}
