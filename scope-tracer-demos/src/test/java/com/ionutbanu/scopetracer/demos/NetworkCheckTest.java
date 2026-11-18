package com.ionutbanu.scopetracer.demos;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NetworkCheckTest {

  @Test
  void aPageThatOnlyNavigatesHasNoProblems() {
    var html =
        "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>"
            + "<a href=\"https://github.com/ionut-banu/scope-tracer\" style=\"x\">link</a>"
            + "<a class=\"c\" href=\"reports/x.html\">report</a>"
            + "<iframe src=\"reports/x.html\" loading=\"lazy\"></iframe>"
            + "<script>var a = 1; // a comment\n</script><style>a { color: red; }</style>";

    assertThat(NetworkCheck.problems(html)).isEmpty();
  }

  @Test
  void aScriptWithASourceIsFound() {
    assertThat(NetworkCheck.problems("<script src=\"https://cdn.example/x.js\"></script>"))
        .anyMatch(p -> p.contains("<script src="))
        .anyMatch(p -> p.contains("https://cdn.example"));
  }

  @Test
  void everyKindOfResourceLoadIsFound() {
    for (var html :
        new String[] {
          "<link rel=\"stylesheet\" href=\"a.css\">",
          "<img src=\"a.png\">",
          "<picture><source srcset=\"a.png\"></picture>",
          "<style>@import \"a.css\";</style>",
          "<style>a { background: url(https://x.example/a.png); }</style>",
          "<style>a { background: url(//x.example/a.png); }</style>",
          "<script>fetch('/data.json')</script>",
          "<script>new XMLHttpRequest()</script>",
          "<iframe src=\"https://x.example/\"></iframe>",
          "<iframe src=\"//x.example/\"></iframe>",
        }) {
      assertThat(NetworkCheck.problems(html)).as(html).isNotEmpty();
    }
  }
}
