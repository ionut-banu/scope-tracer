#!/usr/bin/env bash
# Self-test for readme-image.sh --check. Builds a tiny tree in a temp directory (the script
# takes the tree as its last argument), asserts the check passes, then breaks one thing at a
# time. Never reads the repository's own docs and needs no Chrome. Run from anywhere:
#   .github/scripts/readme-image-test.sh
# Plain bash, sed, awk; runs under macOS bash 3.2 and Ubuntu bash 5.
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
CHECK="$HERE/readme-image.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

failures=0
cases=0

make_good() {
  rm -rf "$1"
  mkdir -p "$1/docs/gallery/reports" "$1/docs/img"
  echo '<html>fail-fast</html>' > "$1/docs/gallery/reports/fail-fast.html"
  echo 'not really a png' > "$1/docs/img/readme-report.png"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1/docs/gallery/reports/fail-fast.html" | awk '{print $1}' > "$1/docs/img/readme-report.source"
  else
    shasum -a 256 "$1/docs/gallery/reports/fail-fast.html" | awk '{print $1}' > "$1/docs/img/readme-report.source"
  fi
}

# run_case NAME EXPECTED_EXIT EXPECTED_TEXT MUTATION
run_case() {
  local name="$1" want="$2" text="$3" mut="$4" d="$TMP/case" out got ok=1
  cases=$((cases + 1))
  make_good "$d"
  "$mut" "$d"
  out="$(bash "$CHECK" --check "$d" 2>&1)"
  got=$?
  [ "$got" -eq "$want" ] || { ok=0; echo "FAIL $name: exit $got, wanted $want"; }
  if [ -n "$text" ] && ! printf '%s\n' "$out" | grep -q -F -- "$text"; then
    ok=0; echo "FAIL $name: output lacks '$text'"
  fi
  if [ "$want" -eq 1 ] && [ "$(printf '%s\n' "$out" | grep -c '^::error file=docs/img/readme-report.png::')" -ne 1 ]; then
    ok=0; echo "FAIL $name: want exactly one ::error annotation on the PNG"
  fi
  if [ "$ok" -eq 1 ]; then echo "ok   $name"; else printf '%s\n' "$out" | sed 's/^/     | /'; failures=$((failures + 1)); fi
}

m_none() { :; }
m_report_changed() { echo '<!-- edited -->' >> "$1/docs/gallery/reports/fail-fast.html"; }
m_png_missing() { rm "$1/docs/img/readme-report.png"; }
m_sidecar_missing() { rm "$1/docs/img/readme-report.source"; }
m_sidecar_crlf() { sed 's/$/\r/' "$1/docs/img/readme-report.source" > "$1/x" && mv "$1/x" "$1/docs/img/readme-report.source"; }

run_case "report and sidecar agree"        0 "matches"                     m_none
run_case "sidecar with CRLF still agrees"  0 "matches"                     m_sidecar_crlf
run_case "report changed"                  1 "run .github/scripts/readme-image.sh" m_report_changed
run_case "PNG missing"                     1 "readme-report.png is missing" m_png_missing
run_case "sidecar missing"                 1 "readme-report.source is missing" m_sidecar_missing

echo "$cases cases, $failures failed"
[ "$failures" -eq 0 ]
