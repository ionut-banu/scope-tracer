#!/usr/bin/env bash
# Fails when the Maven Central bundle holds an artifact that is not meant to be published, or
# lacks one that is. The published set is the parent POM, core, analyzer and agent.
#
# Usage: .github/scripts/check-release-bundle.sh BUNDLE.zip
#   BUNDLE.zip is target/central-publishing/central-bundle.zip, built by
#   `mvn -P release deploy -DskipUpload=true` (needs a non-SNAPSHOT version: a SNAPSHOT stages
#   nothing).
#
# Exit codes: 0 the bundle holds exactly the expected artifacts, 1 at least one problem (every
# offender is listed, not only the first), 2 missing or unreadable file (nothing is checked).
#
# Output is one GitHub annotation per problem: ::error file=BUNDLE::message
# Plain bash and unzip; runs under macOS bash 3.2 and Ubuntu bash 5. No `set -e`, on purpose:
# one run must report every offender.
set -u

ZIP="${1:-}"
if [ -z "$ZIP" ] || [ ! -f "$ZIP" ] || [ ! -r "$ZIP" ]; then
  echo "usage: check-release-bundle.sh BUNDLE.zip" >&2
  echo "  '$ZIP' is not a readable file" >&2
  exit 2
fi

EXPECTED="scope-tracer-parent scope-tracer-core scope-tracer-analyzer scope-tracer-agent"

LISTING="$(unzip -Z1 "$ZIP" 2>/dev/null)" || {
  echo "check-release-bundle.sh: '$ZIP' is not a readable zip" >&2
  exit 2
}

# The artifactId is the path segment after com/ionutbanu/ (one directory per artifact).
FOUND="$(printf '%s\n' "$LISTING" | sed -n 's#^com/ionutbanu/\([^/][^/]*\)/.*#\1#p' | sort -u)"

fail=0
err() {
  echo "::error file=$ZIP::$1"
  fail=1
}

for a in $FOUND; do
  case " $EXPECTED " in
    *" $a "*) ;;
    *) err "bundle holds '$a', which is not published to Maven Central (expected only: $EXPECTED)" ;;
  esac
done

for e in $EXPECTED; do
  case "
$FOUND
" in
    *"
$e
"*) ;;
    *) err "bundle is missing '$e'" ;;
  esac
done

# Anything outside com/ionutbanu/ would be a different groupId.
OTHER="$(printf '%s\n' "$LISTING" | grep -v '^com/ionutbanu/' | grep -v '/$' | head -5)"
if [ -n "$OTHER" ]; then
  err "bundle holds files outside com/ionutbanu/: $(printf '%s' "$OTHER" | tr '\n' ' ')"
fi

if [ "$fail" -eq 0 ]; then
  echo "bundle OK: $(printf '%s' "$FOUND" | tr '\n' ' ')"
fi
exit "$fail"
