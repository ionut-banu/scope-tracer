package com.ionutbanu.scopetracer.plugin.model;

/**
 * The one line of script that makes the embedded report follow the IDE's UI theme (#191). The
 * report's stylesheet owns both palettes and picks one from the {@code data-theme} attribute on
 * {@code <html>}; the attribute name and its two values are the whole contract. Plain Java, so it
 * is tested without a browser; nothing but the two fixed words is ever put into the script.
 *
 * <pre>{@code
 * browser.getCefBrowser().executeJavaScript(ReportTheme.script(!JBColor.isBright()), url, 0);
 * }</pre>
 */
public final class ReportTheme {

  private ReportTheme() {}

  /** The script that sets {@code data-theme} to {@code dark} or {@code light} on the page. */
  public static String script(boolean dark) {
    return "document.documentElement.setAttribute('data-theme','"
        + (dark ? "dark" : "light")
        + "');";
  }
}
