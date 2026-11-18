package com.ionutbanu.scopetracer.demos;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Finds what makes an HTML file load something: the gallery page and every report must work with
 * the network off. Navigation is allowed ({@code <a href="https://...">}, and the SVG namespace
 * identifier {@code xmlns="http://www.w3.org/2000/svg"}, which is a name, not a URL that is
 * fetched); everything that fetches is a problem.
 */
final class NetworkCheck {

  private NetworkCheck() {}

  private static final Pattern FETCHERS =
      Pattern.compile(
          "<script\\b[^>]*\\ssrc\\s*=|<link\\b|<img\\b|<source\\b|<video\\b|<audio\\b|<embed\\b"
              + "|<object\\b|@import|\\bfetch\\s*\\(|XMLHttpRequest|\\bimport\\s*\\(|new\\s+WebSocket"
              + "|sendBeacon",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern URL_FUNCTION =
      Pattern.compile("url\\(\\s*['\"]?\\s*(?:https?:)?//", Pattern.CASE_INSENSITIVE);
  private static final Pattern ABSOLUTE_URL = Pattern.compile("(?:https?:)?//[A-Za-z0-9.-]+");
  private static final Pattern PROTOCOL_RELATIVE_ATTRIBUTE =
      Pattern.compile("\\s(?:src|href|action|data)\\s*=\\s*[\"']?//", Pattern.CASE_INSENSITIVE);
  private static final Pattern ANCHOR_HREF_BEFORE =
      Pattern.compile("<a\\s[^>]*href\\s*=\\s*[\"']$", Pattern.CASE_INSENSITIVE);

  /** Everything in {@code html} that would load a resource; empty when it loads nothing. */
  static List<String> problems(String html) {
    var problems = new ArrayList<String>();
    var fetcher = FETCHERS.matcher(html);
    while (fetcher.find()) {
      problems.add("loads a resource: " + fetcher.group());
    }
    var urlFunction = URL_FUNCTION.matcher(html);
    while (urlFunction.find()) {
      problems.add("css url() to a remote target: " + urlFunction.group());
    }
    var relative = PROTOCOL_RELATIVE_ATTRIBUTE.matcher(html);
    while (relative.find()) {
      problems.add("protocol-relative target: " + relative.group().strip());
    }
    var absolute = ABSOLUTE_URL.matcher(html);
    while (absolute.find()) {
      if (absolute.group().startsWith("//") && !isUrlStart(html, absolute.start())) continue;
      var before = html.substring(Math.max(0, absolute.start() - 80), absolute.start());
      boolean namespace =
          before.endsWith("xmlns=\"") && absolute.group().equals("http://www.w3.org");
      if (namespace || ANCHOR_HREF_BEFORE.matcher(before).find()) continue;
      problems.add("remote reference that is not a link: " + absolute.group());
    }
    return problems;
  }

  /** A bare {@code //} is a comment or a path, not a URL, unless a quote or paren precedes it. */
  private static boolean isUrlStart(String html, int index) {
    if (index == 0) return false;
    char c = html.charAt(index - 1);
    return c == '"' || c == '\'' || c == '(';
  }
}
