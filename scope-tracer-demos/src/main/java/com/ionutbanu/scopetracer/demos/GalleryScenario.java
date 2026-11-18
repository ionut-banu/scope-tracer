package com.ionutbanu.scopetracer.demos;

/**
 * One section of the gallery: the code that runs, the shape its report must show, the snippet that
 * the page displays, and the prose around it.
 *
 * @param id the section's anchor, the report's file name and the snippet's marker id
 * @param heading the section heading
 * @param lookFor what to look for in the report, as HTML (what the report shows)
 * @param cannotShow what the report cannot show, as HTML
 * @param sourceFile the source file holding the snippet, relative to the repository root
 * @param reportFile where the report is written, relative to the repository root
 * @param reportHref where the page finds the report, relative to {@code docs/gallery/}
 * @param banner true for the one report that begins with the explanatory banner
 * @param shape what the report must show
 * @param body the code the report is recorded from
 */
record GalleryScenario(
    String id,
    String heading,
    String lookFor,
    String cannotShow,
    String sourceFile,
    String reportFile,
    String reportHref,
    boolean banner,
    Shape shape,
    Body body) {

  /** The code of a scenario. */
  @FunctionalInterface
  interface Body {
    void run() throws Exception;
  }
}
