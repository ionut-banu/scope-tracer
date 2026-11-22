#!/usr/bin/env bash
# Fails when a file a reader copies a version from names a scope-tracer version other than
# the one being released.
#
# Usage: .github/scripts/check-release-docs.sh X.Y.Z [DOCS_ROOT]
#   DOCS_ROOT is the root of the tree to check (the repository, or an extracted `git archive`);
#   it defaults to the current directory. The script and the docs can come from different
#   trees: release.yml runs the dispatched ref's script against the tag's docs.
#   X.Y.Z is the release version: no 'v' prefix, no -SNAPSHOT, no qualifier such as -rc1.
#
# Exit codes: 0 all good, 1 at least one problem (every problem is listed, not only the
# first), 2 bad argument (nothing is read).
#
# Output is one GitHub annotation per problem: ::error file=FILE::message
# Plain bash, grep and awk; runs under macOS bash 3.2 and Ubuntu bash 5. No `set -e`, on
# purpose: one run must report every stale spot.
set -u

V="${1:-}"
if ! [[ "$V" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "usage: check-release-docs.sh X.Y.Z" >&2
  echo "  X.Y.Z is a release version: no 'v' prefix, no -SNAPSHOT, no qualifier (got '$V')" >&2
  exit 2
fi

ROOT="${2:-.}"
if [ ! -d "$ROOT" ]; then
  echo "check-release-docs.sh: docs root '$ROOT' is not a directory" >&2
  exit 2
fi
cd "$ROOT" || exit 2

fail=0
err() {
  echo "::error file=$1::$2"
  fail=1
}

# stdin to stdout: CR removed, then every <!-- ... --> span (several per line, over several lines;
# an unterminated one runs to the end of the file, as a browser treats it). One output line per
# input line, so line numbers survive. Only guards that ask "is this present" read this view; the
# stale-version scans keep the raw file, so a stale version inside a comment still fails.
strip_html_comments() {
  tr -d '\r' | awk '
    { line = $0; out = ""
      while (1) {
        if (inc) { i = index(line, "-->"); if (!i) break; line = substr(line, i + 3); inc = 0 }
        else { i = index(line, "<!--"); if (!i) { out = out line; break }
               out = out substr(line, 1, i - 1); line = substr(line, i + 4); inc = 1 } }
      print out }'
}

# stdin to stdout: "LINE:VERSION" for a <version> within two lines after a scope-tracer <artifactId>.
dependency_versions() {
  awk '
    /<artifactId>scope-tracer-/ { a = NR }
    /<version>/ && a && NR - a <= 2 {
      s = $0
      sub(/.*<version>/, "", s)
      sub(/<\/version>.*/, "", s)
      print NR ":" s
      a = 0
    }'
}

VER_RE='[0-9]+\.[0-9]+\.[0-9]+(-SNAPSHOT)?'
checked=0

# Files a reader copies a version from. README.md, CONTRIBUTING.md and docs/index.html are required
# too; only docs/*.md is a glob.
# jbang-catalog.json and the JBang demo source are
# required: a user's `jbang demo@...` reads them from main, so a missing one is a release problem,
# not a file to skip (#57).
JBANG_FILES="jbang-catalog.json scope-tracer-demos/jbang/ScopeTracerDemo.java"
FILES="README.md CONTRIBUTING.md docs/index.html $JBANG_FILES"
for f in docs/*.md; do
  [ -f "$f" ] && FILES="$FILES $f"
done

for f in $FILES; do
  if [ ! -f "$f" ]; then
    err "$f" "file not found; the release docs check expects it"
    continue
  fi
  checked=$((checked + 1))

  # 1. A <version> within two lines after a scope-tracer <artifactId> (Maven snippets). The raw
  # file is scanned, so a stale version inside a comment still fails with its real line number.
  while IFS=: read -r line ver; do
    [ -n "$line" ] || continue
    [ "$ver" = "$V" ] || err "$f" "line $line: dependency <version> is '$ver', releasing '$V'"
  done < <(tr -d '\r' < "$f" | dependency_versions)
  if [ "$f" = "docs/index.html" ] && ! strip_html_comments < "$f" | grep -q -E "releases/download/v$VER_RE"; then
    err docs/index.html "no releases/download/vX.Y.Z URL found; the landing page's download links are gone or use a variable instead of a literal version"
  fi
  # "Is there a snippet" asks about what a reader sees, so a snippet in a comment does not count.
  if [ "$f" = "README.md" ] && [ "$(strip_html_comments < "$f" | dependency_versions | grep -c .)" -lt 1 ]; then
    err README.md "no scope-tracer dependency <version> found; the quick-start snippet is gone or reshaped"
  fi

  # 2. Literal jar names, Maven coordinates and release download URLs.
  while IFS=: read -r line tok; do
    [ -n "$line" ] || continue
    ver=$(echo "$tok" | grep -oE "$VER_RE" | head -1)
    [ "$ver" = "$V" ] || err "$f" "line $line: '$tok' names version '$ver', releasing '$V'"
  done < <(tr -d '\r' < "$f" | grep -n -o -E "scope-tracer-(core|analyzer|agent|demos)-$VER_RE|com\.ionutbanu:scope-tracer-[a-z]+:$VER_RE|releases/download/v$VER_RE")
done

# 2b. The README's quick start and the landing page's trial command are the same command: the
# first bash fence under "## Quick start" and the first <pre> of the page (comments ignored,
# entities decoded) each carry the agent's `record` form at the release version, and are equal.
# Found by position, no marker in either file.
RECORD="-javaagent:scope-tracer-agent-$V-agent.jar=record"
readme_block=""
page_block=""
readme_ok=0
page_ok=0
if [ -f README.md ]; then
  readme_rc=0
  readme_block=$(tr -d '\r' < README.md | awk '
    /^## Quick start[ \t]*$/ { q = 1; next }
    q && !inb && /^```bash[ \t]*$/ { inb = 1; next }
    inb && /^```[ \t]*$/ { closed = 1; exit }
    inb { print }
    END { exit closed ? 0 : 3 }') || readme_rc=$?
  if [ "$readme_rc" -ne 0 ]; then
    err README.md "no closed bash block under '## Quick start'; the quick start is gone or reshaped"
  elif ! printf '%s\n' "$readme_block" | grep -q -F -- "$RECORD"; then
    err README.md "the first bash block under '## Quick start' has no '$RECORD' command; the quick start and the landing page's trial command must both carry it at the release version"
  else
    readme_ok=1
  fi
fi
if [ -f docs/index.html ]; then
  prc=0
  page_block=$(strip_html_comments < docs/index.html | awk '
    !inp && /<pre[ >]/ { inp = 1; sub(/.*<pre[^>]*>/, "") }
    inp { if (/<\/pre>/) { sub(/<\/pre>.*/, ""); print; closed = 1; exit } print }
    END { exit closed ? 0 : 3 }') || prc=$?
  page_block=$(printf '%s\n' "$page_block" | sed -e 's/&lt;/</g' -e 's/&gt;/>/g' -e 's/&quot;/"/g' -e 's/&amp;/\&/g')
  if [ "$prc" -ne 0 ]; then
    err docs/index.html "no <pre> block (closed by </pre>, comments ignored); the landing page's trial command is gone or reshaped"
  elif ! printf '%s\n' "$page_block" | grep -q -F -- "$RECORD"; then
    err docs/index.html "the first <pre> (comments ignored) has no '$RECORD' command; the quick start and the landing page's trial command must both carry it at the release version"
  else
    page_ok=1
  fi
fi
if [ "$readme_ok" -eq 1 ] && [ "$page_ok" -eq 1 ] && [ "$readme_block" != "$page_block" ]; then
  err docs/index.html "the trial block differs from the README's quick start block; edit both together"
fi

# 2c. The JBang files are the only place a tool, not a reader, takes a version from, so every
# scope-tracer coordinate in them has a literal X.Y.Z (JBang reads a bare group:artifact, RELEASE
# or ${...} as "whatever is newest" and would move under the user), no URL says "latest", and the
# catalog itself names a pinned scope-tracer artifact (the demo's //DEPS line alone is not enough:
# an alias could then point at an unrelated or moving jar).
jbang_named=0
for f in $JBANG_FILES; do
  [ -f "$f" ] || continue
  tr -d '\r' < "$f" | grep -q -i -E "releases/latest|/latest/download" \
    && err "$f" "a releases/latest URL moves under the user; name the release with releases/download/vX.Y.Z"
  while IFS=: read -r line tok; do
    [ -n "$line" ] || continue
    [ "$f" = "jbang-catalog.json" ] && jbang_named=$((jbang_named + 1))
    # com.ionutbanu:scope-tracer-<module>:X.Y.Z, optionally followed by :classifier
    echo "$tok" | grep -q -E "^com\.ionutbanu:scope-tracer-[a-z]+:[0-9]+\.[0-9]+\.[0-9]+(:[a-z]+)?$" \
      || err "$f" "line $line: coordinate '$tok' has no literal X.Y.Z version after the module name"
  done < <(tr -d '\r' < "$f" | grep -n -o -E "com\.ionutbanu:scope-tracer-[a-z]+[^[:space:]\"',)]*")
  # A release asset URL on this repository names an artifact too (section 2 checks its version);
  # the same path on another host is not scope-tracer's.
  urls=$(tr -d '\r' < "$f" | grep -c -E "https://github\.com/ionut-banu/scope-tracer/releases/download/v$VER_RE/scope-tracer-")
  [ "$f" = "jbang-catalog.json" ] && jbang_named=$((jbang_named + urls))
done
if [ "$jbang_named" -lt 1 ]; then
  err jbang-catalog.json "no scope-tracer artifact (com.ionutbanu:scope-tracer-<module>:X.Y.Z or a https://github.com/ionut-banu/scope-tracer/releases/download/vX.Y.Z/ URL) found in jbang-catalog.json; an alias is gone, reshaped or points at an unrelated jar"
fi
if [ -f scope-tracer-demos/jbang/ScopeTracerDemo.java ] \
  && ! tr -d '\r' < scope-tracer-demos/jbang/ScopeTracerDemo.java | grep -q -E "^//DEPS com\.ionutbanu:scope-tracer-agent:$VER_RE:agent"; then
  err scope-tracer-demos/jbang/ScopeTracerDemo.java "no '//DEPS com.ionutbanu:scope-tracer-agent:X.Y.Z:agent' line; the demo cannot find the agent"
fi

# 3. CHANGELOG: [Unreleased] renamed and linked.
if [ -f CHANGELOG.md ]; then
  checked=$((checked + 1))
  tr -d '\r' < CHANGELOG.md | grep -q -E "^## \[$V\] - [0-9]{4}-[0-9]{2}-[0-9]{2}" \
    || err CHANGELOG.md "no '## [$V] - YYYY-MM-DD' heading; [Unreleased] was not renamed"
  tr -d '\r' < CHANGELOG.md | grep -q -E "^\[$V\]: " \
    || err CHANGELOG.md "no '[$V]: ...' link reference at the foot"
else
  err CHANGELOG.md "file not found; the release docs check expects it"
fi

# 4. Spec section 5 records the release. The "Released:" bullet may wrap over several lines.
if [ -f _docs/spec.md ]; then
  checked=$((checked + 1))
  rel=$(awk '
    /^- Released:/ { p = 1; print; next }
    p && /^(- |#|---|$)/ { exit }
    p { print }' <(tr -d '\r' < _docs/spec.md) | tr '\n' ' ' | tr -s ' ')
  case "$rel" in
    "") err _docs/spec.md "no '- Released:' bullet found in section 5" ;;
    *" $V ("*) ;;
    *) err _docs/spec.md "section 5 'Released:' does not list $V with a date" ;;
  esac
else
  err _docs/spec.md "file not found; section 5 'Released:' cannot be checked"
fi

if [ "$fail" -eq 0 ]; then
  echo "release docs name $V everywhere they should ($checked files checked)"
fi
exit "$fail"
