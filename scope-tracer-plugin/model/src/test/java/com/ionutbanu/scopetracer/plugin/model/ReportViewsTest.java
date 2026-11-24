package com.ionutbanu.scopetracer.plugin.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.ionutbanu.scopetracer.plugin.model.ReportViews.Display;
import com.ionutbanu.scopetracer.plugin.model.ReportViews.Json;
import com.ionutbanu.scopetracer.plugin.model.ReportViews.Notice;
import com.ionutbanu.scopetracer.plugin.model.ReportViews.Timeline;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * What the tool window shows for one analysis (#62), for every combination of JCEF supported or
 * not, the JSON run parsed or failed, and the HTML run written, failed or not run.
 */
class ReportViewsTest {

  private static final Path HTML = Path.of("/tmp/scope-tracer-report-1.html");
  private static final HtmlReport WRITTEN = new HtmlReport.Written(HTML);
  private static final HtmlReport FAILED = new HtmlReport.Failed("exit 3: cannot render");
  private static final HtmlReport NOT_RUN = HtmlReport.NOT_RUN;
  private static final Json PARSED = new Json.Parsed(false);
  private static final Json EMPTY = new Json.Parsed(true);
  private static final Json JSON_FAILED = new Json.Failed("exit 2: not a recording");

  private static Display.Replace replace(boolean jcef, Json json, HtmlReport html) {
    var display = ReportViews.decide(jcef, json, html);
    assertThat(display).isInstanceOf(Display.Replace.class);
    return (Display.Replace) display;
  }

  @Test
  void withJcefAWrittenReportIsTheTimelinePageAndCanBeOpenedInTheBrowser() {
    var r = replace(true, PARSED, WRITTEN);

    assertThat(r.timeline()).isEqualTo(new Timeline.Page(HTML));
    assertThat(r.htmlFile()).isEqualTo(HTML);
    assertThat(r.error()).isNull();
    assertThat(r.notices()).isEmpty();
  }

  @Test
  void withoutJcefThereIsNoTimelineViewTheReportOpensInTheBrowserAndJcefIsExplained() {
    var r = replace(false, PARSED, WRITTEN);

    assertThat(r.timeline()).isEqualTo(Timeline.NONE);
    assertThat(r.htmlFile()).isEqualTo(HTML);
    assertThat(r.error()).isNull();
    assertThat(r.notices()).containsExactly(Notice.JCEF_NEEDED);
  }

  @Test
  void withJcefAFailedHtmlRunSaysSoInTheTimelineOffersNothingToOpenAndErrsOnce() {
    var r = replace(true, PARSED, FAILED);

    assertThat(r.timeline()).isEqualTo(new Timeline.Message(ReportViews.TIMELINE_FAILED));
    assertThat(r.htmlFile()).isNull();
    assertThat(r.error()).isEqualTo("exit 3: cannot render");
    assertThat(r.notices()).isEmpty();
  }

  @Test
  void withoutJcefAFailedHtmlRunIsOneErrorAndNoJcefNoticeForAReportThatDoesNotExist() {
    var r = replace(false, PARSED, FAILED);

    assertThat(r.timeline()).isEqualTo(Timeline.NONE);
    assertThat(r.htmlFile()).isNull();
    assertThat(r.error()).isEqualTo("exit 3: cannot render");
    assertThat(r.notices()).isEmpty();
  }

  @Test
  void withJcefAnHtmlRunThatDidNotHappenShowsTheTasksAndNoError() {
    var r = replace(true, PARSED, NOT_RUN);

    assertThat(r.timeline()).isEqualTo(new Timeline.Message(ReportViews.TIMELINE_NOT_RUN));
    assertThat(r.htmlFile()).isNull();
    assertThat(r.error()).isNull();
    assertThat(r.notices()).isEmpty();
  }

  @Test
  void withoutJcefAnHtmlRunThatDidNotHappenShowsOnlyTheTasks() {
    var r = replace(false, PARSED, NOT_RUN);

    assertThat(r.timeline()).isEqualTo(Timeline.NONE);
    assertThat(r.htmlFile()).isNull();
    assertThat(r.error()).isNull();
    assertThat(r.notices()).isEmpty();
  }

  @Test
  void anEmptyRecordingStillShowsItsReportAndAddsTheNoActivityNoticeOnce() {
    var withJcef = replace(true, EMPTY, WRITTEN);
    var withoutJcef = replace(false, EMPTY, WRITTEN);

    assertThat(withJcef.timeline()).isEqualTo(new Timeline.Page(HTML));
    assertThat(withJcef.notices()).containsExactly(Notice.NO_SCOPES);
    assertThat(withoutJcef.notices()).containsExactlyInAnyOrder(Notice.NO_SCOPES, Notice.JCEF_NEEDED);
  }

  @Test
  void anEmptyRecordingWhoseHtmlFailedIsOneErrorAndTheNoActivityNotice() {
    var r = replace(true, EMPTY, FAILED);

    assertThat(r.error()).isEqualTo("exit 3: cannot render");
    assertThat(r.notices()).containsExactly(Notice.NO_SCOPES);
  }

  @Test
  void aFailedJsonRunReplacesNothingAndIsOneErrorWhateverTheHtmlAndJcef() {
    for (boolean jcef : new boolean[] {true, false}) {
      for (var html : new HtmlReport[] {WRITTEN, FAILED, NOT_RUN}) {
        var display = ReportViews.decide(jcef, JSON_FAILED, html);

        assertThat(display)
            .as("jcef=%s html=%s", jcef, html)
            .isInstanceOf(Display.Keep.class);
        var keep = (Display.Keep) display;
        assertThat(keep.error()).isEqualTo("exit 2: not a recording");
        assertThat(keep.discard()).isEqualTo(html == WRITTEN ? HTML : null);
      }
    }
  }
}
