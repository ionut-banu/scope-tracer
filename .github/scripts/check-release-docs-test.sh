#!/usr/bin/env bash
# Self-test for check-release-docs.sh. Builds a minimal good tree in a temp directory for a
# fake version, asserts the check passes, then applies one mutation at a time and asserts it
# fails with the expected message. Never reads the repository's own docs. Run from anywhere:
#   .github/scripts/check-release-docs-test.sh
# Plain bash, grep, sed, awk; runs under macOS bash 3.2 and Ubuntu bash 5.
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
CHECK="$HERE/check-release-docs.sh"
V=9.9.9
OLD=9.8.0
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

failures=0
cases=0

# A tree that the check accepts for $V.
make_good() {
  local d="$1"
  rm -rf "$d"
  mkdir -p "$d/docs" "$d/_docs"
  cat > "$d/README.md" <<EOT
# scope-tracer

\`\`\`xml
<dependency>
  <groupId>com.ionutbanu</groupId>
  <artifactId>scope-tracer-core</artifactId>
  <version>$V</version>
</dependency>
\`\`\`

javac -cp scope-tracer-core-$V.jar Demo.java
java -javaagent:scope-tracer-agent-\${VERSION}-agent.jar Demo
curl -LO https://github.com/example/scope-tracer/releases/download/v$V/scope-tracer-agent-$V-agent.jar
EOT
  printf '# Contributing\n\nSee scope-tracer-core-%s.jar.\n' "$V" > "$d/CONTRIBUTING.md"
  printf '# Central\n\nUse com.ionutbanu:scope-tracer-core:%s here.\n' "$V" > "$d/docs/maven-central-setup.md"
  printf '# Events\n\nNo version here.\n' > "$d/docs/jfr-events.md"
  printf '<html><body>https://example.com/releases/download/v%s/scope-tracer-agent-%s-agent.jar</body></html>\n' "$V" "$V" > "$d/docs/index.html"
  cat > "$d/CHANGELOG.md" <<EOT
# Changelog

## [Unreleased]

## [$V] - 2030-01-02

### Added

- something

[Unreleased]: https://example.com/compare/v$V...HEAD
[$V]: https://example.com/compare/v$OLD...v$V
[$OLD]: https://example.com/compare/v9.7.0...v$OLD
EOT
  cat > "$d/_docs/spec.md" <<EOT
## §5 Build

- Something before.
- Released: 9.7.0 (2030-01-01), $OLD (2030-01-02), $V
  (2030-01-03). \`main\` is 9.10.0-SNAPSHOT.
- Something after.
EOT
}

# run_case NAME EXPECTED_EXIT EXPECTED_TEXT MUTATION_FUNCTION [ARG]
# EXPECTED_TEXT is a fixed string that must appear in the output ("" for none).
run_case() {
  local name="$1" want="$2" text="$3" mut="$4" arg="${5-$V}"
  local d="$TMP/case" out got ok=1
  cases=$((cases + 1))
  make_good "$d"
  "$mut" "$d"
  out="$(cd "$d" && bash "$CHECK" "$arg" 2>&1)"
  got=$?
  if [ "$got" -ne "$want" ]; then
    ok=0
    echo "FAIL $name: exit $got, wanted $want"
  fi
  if [ -n "$text" ] && ! printf '%s\n' "$out" | grep -q -F -- "$text"; then
    ok=0
    echo "FAIL $name: output lacks '$text'"
  fi
  if printf '%s\n' "$out" | grep -q -E 'syntax error|command not found|unexpected|awk:|grep:'; then
    ok=0
    echo "FAIL $name: output contains a shell or tool error"
  fi
  if [ "$ok" -eq 1 ]; then
    echo "ok   $name"
  else
    printf '%s\n' "$out" | sed 's/^/     | /'
    failures=$((failures + 1))
  fi
}

m_none() { :; }
m_readme_version() { sed "s|<version>$V</version>|<version>$OLD</version>|" "$1/README.md" > "$1/x" && mv "$1/x" "$1/README.md"; }
m_readme_snippet_deleted() { grep -v -E '<artifactId>|<version>' "$1/README.md" > "$1/x" && mv "$1/x" "$1/README.md"; }
m_readme_jar() { echo "java -javaagent:scope-tracer-agent-$OLD-agent.jar" >> "$1/README.md"; }
m_readme_core_jar() { sed "s|javac -cp scope-tracer-core-$V.jar|javac -cp scope-tracer-core-$OLD.jar|" "$1/README.md" > "$1/x" && mv "$1/x" "$1/README.md"; }
m_readme_snapshot_jar() { echo "scope-tracer-core-$V-SNAPSHOT.jar" >> "$1/README.md"; }
m_readme_download() { echo "https://github.com/example/scope-tracer/releases/download/v$OLD/a.jar" >> "$1/README.md"; }
m_readme_coordinate() { echo "implementation com.ionutbanu:scope-tracer-core:$OLD" >> "$1/README.md"; }
m_contributing_jar() { echo "scope-tracer-analyzer-$OLD.jar" >> "$1/CONTRIBUTING.md"; }
m_docs_md() { echo "com.ionutbanu:scope-tracer-agent:$OLD" >> "$1/docs/jfr-events.md"; }
m_docs_index() { echo "<p>scope-tracer-agent-$OLD-agent.jar</p>" >> "$1/docs/index.html"; }
m_docs_index_no_url() { printf '<html><body>scope-tracer-agent-${VERSION}-agent.jar</body></html>\n' > "$1/docs/index.html"; }
m_jbang_stale() { echo "{\"scripts\":{\"x\":{\"script-ref\":\"com.ionutbanu:scope-tracer-analyzer:$OLD\"}}}" > "$1/jbang-catalog.json"; }
m_jbang_good() { echo "{\"scripts\":{\"x\":{\"script-ref\":\"com.ionutbanu:scope-tracer-analyzer:$V\"}}}" > "$1/jbang-catalog.json"; }
m_changelog_heading() { grep -v "^## \[$V\]" "$1/CHANGELOG.md" > "$1/x" && mv "$1/x" "$1/CHANGELOG.md"; }
m_changelog_link() { grep -v "^\[$V\]:" "$1/CHANGELOG.md" > "$1/x" && mv "$1/x" "$1/CHANGELOG.md"; }
m_changelog_missing() { rm "$1/CHANGELOG.md"; }
m_spec_released_wrapped() { sed "s|, $V\$||" "$1/_docs/spec.md" > "$1/x" && mv "$1/x" "$1/_docs/spec.md"; }
m_spec_released_oneline() {
  cat > "$1/_docs/spec.md" <<EOT
- Released: 9.7.0 (2030-01-01), $V (2030-01-03). \`main\` is next.
- Next.
EOT
}
m_spec_released_missing() {
  cat > "$1/_docs/spec.md" <<EOT
- Released: 9.7.0 (2030-01-01), $OLD (2030-01-02).
- Next.
EOT
}
m_spec_prefix_version() {
  cat > "$1/_docs/spec.md" <<EOT
- Released: 19.9.9 (2030-01-01).
- Next.
EOT
}
m_spec_missing() { rm "$1/_docs/spec.md"; }
m_crlf_all() {
  local f
  for f in README.md CONTRIBUTING.md CHANGELOG.md _docs/spec.md docs/maven-central-setup.md docs/jfr-events.md docs/index.html; do
    sed 's/$/\r/' "$1/$f" > "$1/x" && mv "$1/x" "$1/$f"
  done
}
m_crlf_stale() { m_crlf_all "$1"; m_readme_version "$1"; }
m_readme_missing() { rm "$1/README.md"; }

run_case "good tree passes"                          0 "release docs name $V"        m_none
run_case "good tree with a current jbang catalog"    0 ""                            m_jbang_good
run_case "README <version> wrong"                    1 "README.md::line 7"           m_readme_version
run_case "README snippet deleted"                    1 "README.md::no scope-tracer"  m_readme_snippet_deleted
run_case "README stale agent jar"                    1 "README.md::line"             m_readme_jar
run_case "README stale core jar"                     1 "README.md::line 11"          m_readme_core_jar
run_case "README -SNAPSHOT jar"                      1 "README.md::line"             m_readme_snapshot_jar
run_case "README stale download URL"                 1 "README.md::line"             m_readme_download
run_case "README stale coordinate"                   1 "README.md::line"             m_readme_coordinate
run_case "CONTRIBUTING stale jar"                    1 "CONTRIBUTING.md::line"       m_contributing_jar
run_case "docs/*.md stale coordinate"                1 "docs/jfr-events.md::line"    m_docs_md
run_case "docs/index.html stale jar"                 1 "docs/index.html::line"       m_docs_index
run_case "docs/index.html names no download URL"     1 "docs/index.html::no releases/download" m_docs_index_no_url
run_case "jbang-catalog.json stale"                  1 "jbang-catalog.json::line"    m_jbang_stale
run_case "CHANGELOG heading removed"                 1 "CHANGELOG.md::no '## [$V]"   m_changelog_heading
run_case "CHANGELOG link removed"                    1 "CHANGELOG.md::no '[$V]:"     m_changelog_link
run_case "CHANGELOG missing"                         1 "CHANGELOG.md::file not found" m_changelog_missing
run_case "spec Released: version removed (wrapped)"  1 "_docs/spec.md::section 5"    m_spec_released_wrapped
run_case "spec Released: on one line passes"         0 ""                            m_spec_released_oneline
run_case "spec Released: lacks the version"          1 "_docs/spec.md::section 5"    m_spec_released_missing
run_case "spec Released: 19.9.9 is not 9.9.9"        1 "_docs/spec.md::section 5"    m_spec_prefix_version
run_case "spec missing is one error line"            1 "_docs/spec.md::file not found" m_spec_missing
run_case "README missing"                            1 "README.md::file not found"   m_readme_missing
run_case "CRLF files pass"                           0 "release docs name $V"        m_crlf_all
run_case "CRLF files, stale README still fails"      1 "README.md::line 7"           m_crlf_stale
run_case "argument missing"                          2 "usage:"                      m_none ""
run_case "argument -SNAPSHOT"                        2 "usage:"                      m_none "$V-SNAPSHOT"
run_case "argument with v prefix"                    2 "usage:"                      m_none "v$V"
run_case "argument with qualifier"                   2 "usage:"                      m_none "$V-rc1"

# The docs root can be another directory than the working directory.
cases=$((cases + 1))
make_good "$TMP/case"
elsewhere="$(mktemp -d)"
out="$(cd "$elsewhere" && bash "$CHECK" "$V" "$TMP/case" 2>&1)"; got=$?
rmdir "$elsewhere"
if [ "$got" -eq 0 ]; then echo "ok   docs root given as an argument"; else echo "FAIL docs root argument: exit $got"; echo "$out"; failures=$((failures + 1)); fi
cases=$((cases + 1))
out="$(cd "$TMP" && bash "$CHECK" "$V" "$TMP/does-not-exist" 2>&1)"; got=$?
if [ "$got" -eq 2 ]; then echo "ok   docs root that is not a directory exits 2"; else echo "FAIL missing docs root: exit $got"; failures=$((failures + 1)); fi

# Several problems at once are all listed, not only the first.
multi() { m_readme_version "$1"; m_changelog_heading "$1"; m_spec_released_missing "$1"; }
cases=$((cases + 1))
make_good "$TMP/case"
multi "$TMP/case"
out="$(cd "$TMP/case" && bash "$CHECK" "$V" 2>&1)"
n=$(printf '%s\n' "$out" | grep -c '^::error')
if [ "$n" -ge 3 ]; then echo "ok   several problems reported in one run ($n)"; else echo "FAIL several problems: $n"; failures=$((failures + 1)); fi

echo "$cases cases, $failures failed"
[ "$failures" -eq 0 ]
