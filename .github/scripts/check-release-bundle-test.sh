#!/usr/bin/env bash
# Self-test for check-release-bundle.sh. Builds small fake bundles with zip and asserts the
# exit code and the annotations. Never reads a real bundle. Run from anywhere:
#   .github/scripts/check-release-bundle-test.sh
# Plain bash, zip, unzip, grep; runs under macOS bash 3.2 and Ubuntu bash 5.
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
CHECK="$HERE/check-release-bundle.sh"
V=9.9.9
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

failures=0
cases=0

# make_bundle OUT.zip artifactId...
make_bundle() {
  local out="$1"
  shift
  local d="$TMP/tree"
  rm -rf "$d" "$out"
  mkdir -p "$d"
  local a
  for a in "$@"; do
    mkdir -p "$d/com/ionutbanu/$a/$V"
    echo x > "$d/com/ionutbanu/$a/$V/$a-$V.pom"
    echo x > "$d/com/ionutbanu/$a/$V/$a-$V.pom.asc"
    echo x > "$d/com/ionutbanu/$a/$V/$a-$V.jar"
  done
  (cd "$d" && zip -qr "$out" com)
}

GOOD="scope-tracer-parent scope-tracer-core scope-tracer-analyzer scope-tracer-agent"

# expect NAME EXPECTED_EXIT GREP_PATTERN_OR_EMPTY ZIP
expect() {
  local name="$1" want="$2" pat="$3" zip="$4" out rc
  cases=$((cases + 1))
  out="$(bash "$CHECK" "$zip" 2>&1)"
  rc=$?
  if [ "$rc" -ne "$want" ]; then
    echo "FAIL $name: exit $rc, wanted $want"
    echo "$out"
    failures=$((failures + 1))
    return
  fi
  if [ -n "$pat" ] && ! printf '%s\n' "$out" | grep -q -- "$pat"; then
    echo "FAIL $name: output lacks '$pat'"
    echo "$out"
    failures=$((failures + 1))
    return
  fi
  echo "ok   $name"
}

# shellcheck disable=SC2086
make_bundle "$TMP/good.zip" $GOOD
expect "good set passes" 0 "bundle OK" "$TMP/good.zip"

# shellcheck disable=SC2086
make_bundle "$TMP/demos.zip" $GOOD scope-tracer-demos
expect "plus demos fails and names it" 1 "::error.*scope-tracer-demos" "$TMP/demos.zip"

# shellcheck disable=SC2086
make_bundle "$TMP/stress.zip" $GOOD scope-tracer-stress-tests
expect "plus stress-tests fails and names it" 1 "::error.*scope-tracer-stress-tests" "$TMP/stress.zip"

make_bundle "$TMP/noagent.zip" scope-tracer-parent scope-tracer-core scope-tracer-analyzer
expect "agent missing fails and names it" 1 "::error.*missing 'scope-tracer-agent'" "$TMP/noagent.zip"

# shellcheck disable=SC2086
make_bundle "$TMP/two.zip" $GOOD scope-tracer-demos scope-tracer-stress-tests
expect "two offenders: demos reported" 1 "::error.*scope-tracer-demos" "$TMP/two.zip"
expect "two offenders: stress-tests reported" 1 "::error.*scope-tracer-stress-tests" "$TMP/two.zip"
n="$(bash "$CHECK" "$TMP/two.zip" 2>&1 | grep -c '^::error')"
cases=$((cases + 1))
if [ "$n" -eq 2 ]; then echo "ok   two offenders: exactly two annotations"; else
  echo "FAIL two offenders: $n annotations, wanted 2"
  failures=$((failures + 1))
fi

# a prefix of an expected name is not the expected name
make_bundle "$TMP/prefix.zip" scope-tracer-parent scope-tracer-core scope-tracer-analyzer scope-tracer-agent-extra
expect "near-miss artifactId fails" 1 "::error.*scope-tracer-agent-extra" "$TMP/prefix.zip"

expect "nonexistent path exits 2" 2 "" "$TMP/nope.zip"
expect "no argument exits 2" 2 "" ""
echo "not a zip" > "$TMP/bad.zip"
expect "not a zip exits 2" 2 "" "$TMP/bad.zip"

echo "$cases checks, $failures failed"
[ "$failures" -eq 0 ]
