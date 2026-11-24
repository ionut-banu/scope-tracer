package com.ionutbanu.scopetracer.plugin.model;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The embedded browser's navigation rule (#62): it may load the report's own file, with any
 * fragment (the recording overview's {@code #scope-N} links scroll the page), and nothing else.
 * Every other URL a page could ask for (a link, {@code window.location}, a {@code blob:} or
 * {@code data:} URL, another file) is refused. Plain Java, so it is tested without a browser.
 *
 * <pre>{@code
 * boolean cancel = !ReportNavigation.allowed(request.getURL(), reportFile);
 * }</pre>
 */
public final class ReportNavigation {

  private ReportNavigation() {}

  /** Whether {@code url} is {@code report}'s own {@code file:} URL, ignoring its fragment. */
  public static boolean allowed(String url, Path report) {
    if (url == null || report == null) {
      return false;
    }
    URI uri;
    try {
      uri = new URI(url);
    } catch (URISyntaxException e) {
      return false;
    }
    if (!"file".equalsIgnoreCase(uri.getScheme()) || uri.isOpaque()) {
      return false;
    }
    var authority = uri.getRawAuthority();
    if (authority != null && !authority.isEmpty() && !authority.equalsIgnoreCase("localhost")) {
      return false;
    }
    if (uri.getRawQuery() != null || uri.getPath() == null || uri.getPath().isEmpty()) {
      return false;
    }
    Path requested;
    try {
      requested = Path.of(new URI("file", null, uri.getPath(), null));
    } catch (URISyntaxException | IllegalArgumentException e) {
      return false;
    }
    return sameFile(requested, report);
  }

  private static boolean sameFile(Path a, Path b) {
    try {
      if (Files.exists(a) && Files.exists(b)) {
        return Files.isSameFile(a, b);
      }
    } catch (IOException | SecurityException e) {
      // fall through to the textual comparison
    }
    return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
  }
}
