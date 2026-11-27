package com.ionutbanu.scopetracer.demos;

import static com.ionutbanu.scopetracer.demos.Shape.cancelled;
import static com.ionutbanu.scopetracer.demos.Shape.failed;
import static com.ionutbanu.scopetracer.demos.Shape.scope;
import static com.ionutbanu.scopetracer.demos.Shape.succeeded;
import static com.ionutbanu.scopetracer.demos.TestModels.model;
import static com.ionutbanu.scopetracer.demos.TestModels.ok;
import static com.ionutbanu.scopetracer.demos.TestModels.task;
import static org.assertj.core.api.Assertions.assertThat;

import com.ionutbanu.scopetracer.analyzer.model.TraceModel;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The command around the recordings, with the recordings replaced by hand-built models. */
class GalleryMainTest {

  @TempDir Path root;

  private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
  private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
  private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
  private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

  private static final Shape SHAPE =
      Shape.of(scope("s", succeeded("a"), failed("b", "boom"), cancelled("c")));

  private final List<GalleryScenario> scenarios =
      List.of(
          new GalleryScenario(
              "demo",
              "demo heading",
              "look <b>here</b>",
              "not this",
              "src/Demo.java",
              "docs/gallery/reports/demo.html",
              "reports/demo.html",
              false,
              false,
              SHAPE,
              () -> {}),
          new GalleryScenario(
              "bannered",
              "bannered heading",
              "look",
              "not this",
              "src/Demo.java",
              "docs/example/bannered.html",
              "../example/bannered.html",
              true,
              true,
              SHAPE,
              () -> {}));

  /** Hands out a model per call; the shift and thread base change between calls, as in life. */
  private static final class FakeRecorder implements GalleryMain.Recorder {
    final List<String> warmed = new ArrayList<>();
    int recordings;
    Function<Integer, TraceModel> models = n -> good(n);

    @Override
    public void warmUp(GalleryScenario scenario) {
      warmed.add(scenario.id());
    }

    @Override
    public TraceModel record(GalleryScenario scenario) {
      return models.apply(recordings++);
    }
  }

  private static TraceModel good(int n) {
    return model(
        TestModels.scope(
            1,
            "s",
            n * 1000L,
            210,
            null,
            task(1, "a", 10 + n, n * 1000L, 50, ok()),
            task(2, "b", 20 + n, n * 1000L, 200, TestModels.failure("boom")),
            task(3, "c", 30 + n, n * 1000L, 201 + n, TestModels.cancelled())));
  }

  private static TraceModel withoutCancelled() {
    return model(
        TestModels.scope(
            1,
            "s",
            0,
            210,
            null,
            task(1, "a", 1, 0, 50, ok()),
            task(2, "b", 2, 0, 200, TestModels.failure("boom")),
            task(3, "c", 3, 0, 201, ok())));
  }

  @BeforeEach
  void repositoryRoot() throws Exception {
    Files.createDirectories(root.resolve("docs"));
    Files.createDirectories(root.resolve("src"));
    Files.writeString(
        root.resolve("src/Demo.java"),
        """
        class Demo {
          void run() {
            // gallery:begin demo
            try (var scope = open()) {
              scope.fork("a", () -> 1);
            }
            // gallery:end demo
            // gallery:begin bannered
            second();
            // gallery:end bannered
            // gallery:begin ok
            third();
            // gallery:end ok
          }
        }
        """);
  }

  private int run(boolean check, GalleryMain.Recorder recorder) throws Exception {
    return GalleryMain.run(root, check, scenarios, recorder, out, err);
  }

  private String text(String relative) throws Exception {
    return Files.readString(root.resolve(relative));
  }

  @Test
  void writesTheReportsTheBannerAndThePageAndPrintsOneLinePerFile() throws Exception {
    var recorder = new FakeRecorder();

    assertThat(run(false, recorder)).isZero();

    assertThat(recorder.warmed).containsExactly("demo", "bannered");
    assertThat(text("docs/gallery/reports/demo.html")).startsWith("<!DOCTYPE html>");
    assertThat(text("docs/gallery/reports/demo.html")).doesNotContain("This is an example report");
    assertThat(text("docs/example/bannered.html"))
        .contains("<body>\n" + GalleryMain.BANNER + "<h1>");
    assertThat(text("docs/gallery/index.html"))
        .contains("id=\"demo\"", "id=\"bannered\"", "try (var scope = open())", "look <b>here</b>");
    assertThat(
            outBytes.toString(StandardCharsets.UTF_8).lines().filter(l -> l.startsWith("wrote ")))
        .hasSize(3);
  }

  @Test
  void aSingleBadRecordingIsRetriedAndThePublishedOneIsTheGoodOne() throws Exception {
    var recorder = new FakeRecorder();
    recorder.models = n -> n == 0 ? withoutCancelled() : good(n);

    assertThat(run(false, recorder)).isZero();

    assertThat(recorder.recordings).isEqualTo(3); // demo twice, bannered once
    assertThat(outBytes.toString(StandardCharsets.UTF_8))
        .contains("demo: attempt 1 rejected")
        .contains("demo: recorded on attempt 2")
        .contains("bannered: recorded on attempt 1");
  }

  @Test
  void aScenarioThatNeverShowsItsShapeFailsAfterThreeAttemptsAndWritesNothing() throws Exception {
    Files.createDirectories(root.resolve("docs/gallery/reports"));
    Files.writeString(root.resolve("docs/gallery/reports/demo.html"), "committed");
    var recorder = new FakeRecorder();
    recorder.models = n -> withoutCancelled();

    assertThat(run(false, recorder)).isEqualTo(1);

    assertThat(recorder.recordings).isEqualTo(GalleryMain.ATTEMPTS);
    var error = errBytes.toString(StandardCharsets.UTF_8);
    assertThat(error)
        .contains("scenario demo")
        .contains("expected: s [a=success, b=failed, c=cancelled]")
        .contains("actual:   s [a=success, b=failed, c=success]")
        .contains("nothing was written");
    assertThat(text("docs/gallery/reports/demo.html")).isEqualTo("committed");
    assertThat(root.resolve("docs/gallery/index.html")).doesNotExist();
    assertThat(root.resolve("docs/example/bannered.html")).doesNotExist();
  }

  @Test
  void withoutADocsDirectoryItAsksForTheRepositoryRoot() throws Exception {
    var elsewhere = Files.createTempDirectory(root, "elsewhere");

    var code = GalleryMain.run(elsewhere, false, scenarios, new FakeRecorder(), out, err);

    assertThat(code).isEqualTo(1);
    assertThat(errBytes.toString(StandardCharsets.UTF_8))
        .contains("run it from the repository root");
    try (var files = Files.list(elsewhere)) {
      assertThat(files).isEmpty();
    }
  }

  @Test
  void checkPassesAfterAGenerationEvenThoughEveryTimeAndThreadDiffers() throws Exception {
    assertThat(run(false, new FakeRecorder())).isZero();
    var second = new FakeRecorder();
    second.recordings = 7; // other shifts, other threads, other durations

    assertThat(run(true, second)).isZero();

    assertThat(outBytes.toString(StandardCharsets.UTF_8))
        .contains("docs/gallery/reports/demo.html: ok")
        .contains("docs/example/bannered.html: ok")
        .contains("docs/gallery/index.html: ok");
  }

  @Test
  void checkWritesNothing() throws Exception {
    assertThat(run(false, new FakeRecorder())).isZero();
    var before = text("docs/gallery/reports/demo.html");

    assertThat(run(true, new FakeRecorder())).isZero();

    assertThat(text("docs/gallery/reports/demo.html")).isEqualTo(before);
  }

  @Test
  void checkNamesAReportThatWasEditedByHand() throws Exception {
    assertThat(run(false, new FakeRecorder())).isZero();
    var report = root.resolve("docs/gallery/reports/demo.html");
    Files.writeString(
        report, text("docs/gallery/reports/demo.html").replace("<td>a</td>", "<td>z</td>"));

    assertThat(run(true, new FakeRecorder())).isEqualTo(1);

    assertThat(errBytes.toString(StandardCharsets.UTF_8))
        .contains("docs/gallery/reports/demo.html: differs")
        .contains("The gallery is out of date: docs/gallery/reports/demo.html");
  }

  @Test
  void checkNamesAReportWithARowDeleted() throws Exception {
    assertThat(run(false, new FakeRecorder())).isZero();
    var report = root.resolve("docs/example/bannered.html");
    var html = text("docs/example/bannered.html");
    int row = html.lastIndexOf("<tr", html.indexOf("><td>2</td>"));
    Files.writeString(
        report, html.substring(0, row) + html.substring(html.indexOf("</tr>", row) + 6));

    assertThat(run(true, new FakeRecorder())).isEqualTo(1);

    assertThat(errBytes.toString(StandardCharsets.UTF_8))
        .contains("docs/example/bannered.html: differs");
  }

  @Test
  void checkNamesAMissingFileAndAPageThatIsStale() throws Exception {
    assertThat(run(false, new FakeRecorder())).isZero();
    Files.delete(root.resolve("docs/gallery/reports/demo.html"));
    Files.writeString(root.resolve("docs/gallery/index.html"), "old page");

    assertThat(run(true, new FakeRecorder())).isEqualTo(1);

    assertThat(errBytes.toString(StandardCharsets.UTF_8))
        .contains("docs/gallery/reports/demo.html: missing")
        .contains("docs/gallery/index.html: differs");
  }

  @Test
  void checkFailsWhenTheRendererMarkupChanged() throws Exception {
    assertThat(run(false, new FakeRecorder())).isZero();
    var report = root.resolve("docs/gallery/reports/demo.html");
    Files.writeString(
        report, text("docs/gallery/reports/demo.html").replace("outcome-failed", "outcome-broken"));

    assertThat(run(true, new FakeRecorder())).isEqualTo(1);
  }

  @Test
  void checkFailsWhenTheCriticalPathNoteIsRemovedFromACommittedReport() throws Exception {
    var allOk =
        List.of(
            new GalleryScenario(
                "ok",
                "ok heading",
                "look",
                "not this",
                "src/Demo.java",
                "docs/gallery/reports/ok.html",
                "reports/ok.html",
                false,
                false,
                Shape.of(scope("s", succeeded("a"), succeeded("b"))),
                () -> {}));
    GalleryMain.Recorder recorder =
        new GalleryMain.Recorder() {
          public void warmUp(GalleryScenario scenario) {}

          public TraceModel record(GalleryScenario scenario) {
            return model(
                TestModels.scope(
                    1,
                    "s",
                    0,
                    210,
                    null,
                    task(1, "a", 1, 0, 50, ok()),
                    task(2, "b", 2, 0, 200, ok())));
          }
        };
    assertThat(GalleryMain.run(root, false, allOk, recorder, out, err)).isZero();
    assertThat(GalleryMain.run(root, true, allOk, recorder, out, err)).isZero();
    var report = root.resolve("docs/gallery/reports/ok.html");
    var html = Files.readString(report);
    assertThat(html).contains("critical-path-note");
    Files.writeString(
        report, html.replaceAll(" ?<span class=\"critical-path-note\">.*?</span>", ""));

    assertThat(GalleryMain.run(root, true, allOk, recorder, out, err)).isEqualTo(1);
    assertThat(errBytes.toString(StandardCharsets.UTF_8))
        .contains("docs/gallery/reports/ok.html: differs");
  }

  @Test
  void aScopeTracerAgentIsRecognisedAndOtherAgentsAreNot() {
    assertThat(
            GalleryMain.scopeTracerAgent(
                List.of(
                    "-Dx=1",
                    "-javaagent:scope-tracer-agent/target/scope-tracer-agent-0.4.0-agent.jar=record")))
        .startsWith("-javaagent:scope-tracer-agent");
    assertThat(GalleryMain.scopeTracerAgent(List.of("-javaagent:/opt/other-agent.jar", "-Dx=1")))
        .isNull();
    assertThat(GalleryMain.scopeTracerAgent(List.of())).isNull();
  }
}
