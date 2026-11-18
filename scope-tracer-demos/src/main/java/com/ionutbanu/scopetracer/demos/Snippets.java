package com.ionutbanu.scopetracer.demos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Extracts a code snippet from a source file: the lines between {@code // gallery:begin <id>} and
 * {@code // gallery:end <id>}, with the common indentation removed. The gallery page shows these
 * lines, so it can never show code that differs from the code that ran.
 */
final class Snippets {

  private Snippets() {}

  /** The snippet {@code id} of {@code sourceFile}; throws if either marker is missing. */
  static String extract(Path sourceFile, String id) throws IOException {
    return extract(Files.readAllLines(sourceFile), id, sourceFile.toString());
  }

  static String extract(List<String> lines, String id, String where) {
    int begin = -1;
    int end = -1;
    for (int i = 0; i < lines.size(); i++) {
      var line = lines.get(i).strip();
      if (line.equals("// gallery:begin " + id)) begin = i;
      if (line.equals("// gallery:end " + id)) end = i;
    }
    if (begin < 0 || end < 0 || end <= begin) {
      throw new IllegalStateException(
          "no `// gallery:begin " + id + "` / `// gallery:end " + id + "` pair in " + where);
    }
    var body = new ArrayList<>(lines.subList(begin + 1, end));
    int indent = Integer.MAX_VALUE;
    for (var line : body) {
      if (line.isBlank()) continue;
      indent = Math.min(indent, line.length() - line.stripLeading().length());
    }
    if (indent == Integer.MAX_VALUE) indent = 0;
    var out = new StringBuilder();
    for (var line : body) {
      out.append(line.isBlank() ? "" : line.substring(indent).stripTrailing()).append('\n');
    }
    return out.toString().stripTrailing() + "\n";
  }
}
