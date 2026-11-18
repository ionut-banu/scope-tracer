package com.ionutbanu.scopetracer.demos;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * Builds {@code docs/gallery/index.html} from the template resource {@code gallery/page.html}, the
 * catalog's text and the snippets extracted from the scenarios' source. The page holds no time, so
 * it is the same text on every run: {@code GalleryMain --check} and {@code GalleryTest} compare it
 * byte for byte.
 */
final class GalleryPage {

  private GalleryPage() {}

  /** The page for {@code scenarios}, with snippets read from the sources under {@code root}. */
  static String render(Path root, List<GalleryScenario> scenarios) throws IOException {
    var nav = new StringBuilder();
    var sections = new StringBuilder();
    for (var s : scenarios) {
      nav.append("      <li><a href=\"#")
          .append(s.id())
          .append("\">")
          .append(s.id())
          .append("</a></li>\n");
      var snippet = Snippets.extract(root.resolve(s.sourceFile()), s.id());
      sections
          .append("  <section id=\"")
          .append(s.id())
          .append("\">\n    <h2><a href=\"#")
          .append(s.id())
          .append("\">")
          .append(escape(s.heading()))
          .append("</a></h2>\n    <p>")
          .append(s.lookFor().strip())
          .append("</p>\n    <pre><code>")
          .append(escape(snippet))
          .append("</code></pre>\n    <iframe src=\"")
          .append(s.reportHref())
          .append("\" loading=\"lazy\" sandbox=\"allow-scripts\" title=\"")
          .append(s.id())
          .append(" report\"></iframe>\n    <p class=\"open\"><a href=\"")
          .append(s.reportHref())
          .append("\">Open the full ")
          .append(s.id())
          .append(" report</a></p>\n    <p class=\"cannot\">")
          .append(s.cannotShow().strip())
          .append("</p>\n  </section>\n");
    }
    return template()
        .replace("{{NAV}}", stripTrailingNewline(nav))
        .replace("{{SECTIONS}}", sections);
  }

  private static String stripTrailingNewline(StringBuilder sb) {
    return sb.toString().stripTrailing();
  }

  private static String template() throws IOException {
    try (InputStream in = GalleryPage.class.getResourceAsStream("/gallery/page.html")) {
      if (in == null) throw new IOException("resource /gallery/page.html is missing");
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  static String escape(String s) {
    return s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }
}
