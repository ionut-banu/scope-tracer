package com.ionutbanu.scopetracer.plugin.model;

import java.nio.file.Path;

/**
 * How the HTML run of one analysis ended (#62): the analyzer's own report written to a temp file,
 * a failure, or no HTML run at all.
 */
public sealed interface HtmlReport {

  /** The run that never happened, for example because the JSON run before it failed. */
  HtmlReport NOT_RUN = new NotRun();

  /** The analyzer wrote its report, unchanged, to {@code file}. */
  record Written(Path file) implements HtmlReport {}

  /** The HTML run failed; {@code message} says why. No file is left behind. */
  record Failed(String message) implements HtmlReport {}

  /** No HTML run; see {@link #NOT_RUN}. */
  record NotRun() implements HtmlReport {}
}
