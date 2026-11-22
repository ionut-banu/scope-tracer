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

sha() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'; else shasum -a 256 "$1" | awk '{print $1}'; fi
}

R=docs/gallery/reports/fail-fast.html
P=docs/img/readme-report.png
S=docs/img/readme-report.source

# A tree the check accepts: the sidecar holds two rows in sha256sum's own format.
make_good() {
  rm -rf "$1"
  mkdir -p "$1/docs/gallery/reports" "$1/docs/img"
  echo '<html>fail-fast</html>' > "$1/$R"
  printf 'not really a png, but long enough to truncate\n' > "$1/$P"
  printf '%s  %s\n%s  %s\n' "$(sha "$1/$R")" "$R" "$(sha "$1/$P")" "$P" > "$1/$S"
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
m_png_empty() { : > "$1/$P"; }
m_png_truncated() { head -c 10 "$1/$P" > "$1/x" && mv "$1/x" "$1/$P"; }
m_png_edited() { echo 'x' >> "$1/$P"; }
m_png_row_missing() { grep -v -F "  $P" "$1/$S" > "$1/x"; mv "$1/x" "$1/$S"; }
m_report_row_missing() { grep -v -F "  $R" "$1/$S" > "$1/x"; mv "$1/x" "$1/$S"; }
m_sidecar_empty() { : > "$1/$S"; }
m_sidecar_old_format() { sha "$1/$R" > "$1/$S"; }
m_sidecar_short_hex() { sed 's/^\([0-9a-f]\{10\}\)[0-9a-f]*/\1/' "$1/$S" > "$1/x"; mv "$1/x" "$1/$S"; }
m_sidecar_upper() { tr 'a-f' 'A-F' < "$1/$S" | sed "s|$(echo "$R" | tr 'a-f' 'A-F')|$R|;s|$(echo "$P" | tr 'a-f' 'A-F')|$P|" > "$1/x"; mv "$1/x" "$1/$S"; }
m_report_hex_upper() { awk -v r="$R" '$2==r { $1=toupper($1) } { print $1 "  " $2 }' "$1/$S" > "$1/x"; mv "$1/x" "$1/$S"; }
m_rows_swapped() { awk '{ print $1 }' "$1/$S" | { read -r a; read -r b; printf '%s  %s\n%s  %s\n' "$b" "$R" "$a" "$P"; } > "$1/x"; mv "$1/x" "$1/$S"; }
m_sidecar_crlf() { sed 's/$/\r/' "$1/docs/img/readme-report.source" > "$1/x" && mv "$1/x" "$1/docs/img/readme-report.source"; }

run_case "report and sidecar agree"        0 "matches"                     m_none
run_case "sidecar with CRLF still agrees"  0 "matches"                     m_sidecar_crlf
run_case "report changed"                  1 "run .github/scripts/readme-image.sh" m_report_changed
run_case "PNG empty"                       1 "readme-report.png does not match docs/img/readme-report.source" m_png_empty
run_case "PNG truncated"                   1 "readme-report.png does not match" m_png_truncated
run_case "PNG edited"                      1 "readme-report.png does not match" m_png_edited
run_case "PNG hash row missing"            1 "has no valid SHA-256 row for docs/img/readme-report.png" m_png_row_missing
run_case "report hash row missing"         1 "has no valid SHA-256 row for docs/gallery/reports/fail-fast.html" m_report_row_missing
run_case "sidecar empty"                   1 "has no valid SHA-256 row" m_sidecar_empty
run_case "old one-row sidecar"             1 "has no valid SHA-256 row" m_sidecar_old_format
run_case "hash row shorter than 64 hex"    1 "has no valid SHA-256 row" m_sidecar_short_hex
run_case "sidecar hex upper case"          0 "matches"                     m_sidecar_upper
run_case "report hex upper case only"      0 "matches"                     m_report_hex_upper
run_case "both rows swapped"               1 "does not match"              m_rows_swapped
run_case "PNG missing"                     1 "readme-report.png is missing" m_png_missing
run_case "sidecar missing"                 1 "readme-report.source is missing" m_sidecar_missing

echo "$cases cases, $failures failed"
[ "$failures" -eq 0 ]
