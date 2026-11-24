#!/usr/bin/env bash
# Self-test for check-plugin-secrets.sh. Sets the 'is it set' flags the workflow passes and
# asserts the exit code, the annotations and the marketplace output. No secret is involved.
# Run from anywhere:
#   .github/scripts/check-plugin-secrets-test.sh
# Plain bash and grep; runs under macOS bash 3.2 and Ubuntu bash 5.
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
CHECK="$HERE/check-plugin-secrets.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

failures=0
cases=0

# expect NAME EXPECTED_EXIT GREP_PATTERN_OR_EMPTY EXPECTED_MARKETPLACE_OR_EMPTY CHAIN KEY PASSWORD TOKEN VARIABLE
expect() {
  local name="$1" want="$2" pat="$3" want_mp="$4" out rc mp
  cases=$((cases + 1))
  : > "$TMP/output"
  out="$(env -i PATH="$PATH" GITHUB_OUTPUT="$TMP/output" \
    JETBRAINS_CERTIFICATE_CHAIN_SET="$5" JETBRAINS_PRIVATE_KEY_SET="$6" \
    JETBRAINS_PRIVATE_KEY_PASSWORD_SET="$7" JETBRAINS_MARKETPLACE_TOKEN_SET="$8" \
    PLUGIN_MARKETPLACE_PUBLISH="$9" bash "$CHECK" 2>&1)"
  rc=$?
  mp="$(sed -n 's/^marketplace=//p' "$TMP/output")"
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
  if [ "$mp" != "$want_mp" ]; then
    echo "FAIL $name: marketplace output '$mp', wanted '$want_mp'"
    failures=$((failures + 1))
    return
  fi
  echo "ok   $name"
}

expect "all set, variable true: marketplace" 0 "JetBrains Marketplace" true true true true true true
expect "signing set, variable unset: notice, no marketplace" 0 "::notice::.*#198" false true true true false ""
expect "signing set, variable false: notice, no marketplace" 0 "::notice::.*PLUGIN_MARKETPLACE_PUBLISH" false true true true true false
expect "signing set, token unset, variable unset: fine" 0 "" false true true true false ""
expect "chain missing fails and names it" 1 "::error::.*JETBRAINS_CERTIFICATE_CHAIN is not set" "" false true true true true
expect "key missing fails and names it" 1 "::error::.*JETBRAINS_PRIVATE_KEY is not set" "" true false true true true
expect "password missing fails and names it" 1 "::error::.*JETBRAINS_PRIVATE_KEY_PASSWORD is not set" "" true true false true true
expect "variable true, token missing fails and names it" 1 "::error::.*JETBRAINS_MARKETPLACE_TOKEN is not set" "" true true true false true

# Every missing secret is named, not only the first.
out="$(env -i PATH="$PATH" JETBRAINS_MARKETPLACE_TOKEN_SET=false PLUGIN_MARKETPLACE_PUBLISH=true bash "$CHECK" 2>&1)"
n="$(printf '%s\n' "$out" | grep -c '^::error::')"
cases=$((cases + 1))
if [ "$n" -eq 4 ]; then echo "ok   nothing set: four annotations"; else
  echo "FAIL nothing set: $n annotations, wanted 4"
  echo "$out"
  failures=$((failures + 1))
fi

echo "$cases checks, $failures failed"
[ "$failures" -eq 0 ]
