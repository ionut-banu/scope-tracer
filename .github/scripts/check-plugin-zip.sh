#!/usr/bin/env bash
# Fails when an IntelliJ plugin zip is not the release it claims to be: the wrong plugin id or
# version, an IDE range other than the one gradle.properties declares, no change notes, no icon,
# or bundled analyzer and agent jars of another version (or more than one agent jar).
#
# Usage: .github/scripts/check-plugin-zip.sh PLUGIN.zip X.Y.Z [GRADLE_PROPERTIES]
#   PLUGIN.zip is scope-tracer-plugin/build/distributions/scope-tracer-plugin-X.Y.Z.zip, from
#   `./gradlew buildPlugin` (or the signed zip from `signPlugin`).
#   X.Y.Z is the release version, without the 'v'. A version containing SNAPSHOT is refused.
#   GRADLE_PROPERTIES is the plugin's gradle.properties to read `pluginSinceBuild` from; it
#   defaults to scope-tracer-plugin/gradle.properties in the tree this script is in. release.yml
#   runs the dispatched ref's script on the tag's zip, so it passes the tag's file.
#
# Rules, each reported by name when it fails:
#   plugin-jar     exactly one jar in lib/ holds META-INF/plugin.xml (the plugin jar)
#   id             plugin.xml has <id>com.ionutbanu.scopetracer.plugin</id>
#   version        plugin.xml has <version>X.Y.Z</version>
#   since-build    plugin.xml's since-build is the pluginSinceBuild of gradle.properties
#   change-notes   plugin.xml has a non-empty <change-notes>
#   icon           the plugin jar has META-INF/pluginIcon.svg
#   agent-jar      exactly one agent/*.jar, named scope-tracer-agent-X.Y.Z-agent.jar
#   agent-name     agent/agent-jar-name.txt names that jar
#   analyzer       analyzer/analyzer.jar exists, and its
#                  META-INF/maven/com.ionutbanu/scope-tracer-analyzer/pom.properties says
#                  version=X.Y.Z
#
# Exit codes: 0 every rule holds, 1 at least one rule failed (every failure is listed, not only
# the first), 2 bad arguments or an unreadable file (nothing is checked).
#
# Output is one GitHub annotation per failure: ::error file=PLUGIN.zip::RULE: message
# Plain bash, unzip, sed, awk, grep; runs under macOS bash 3.2 and Ubuntu bash 5. No `set -e`, on
# purpose: one run must report every failure.
set -u

PLUGIN_ID="com.ionutbanu.scopetracer.plugin"
HERE="$(cd "$(dirname "$0")" && pwd)"

usage() {
  echo "usage: check-plugin-zip.sh PLUGIN.zip X.Y.Z [GRADLE_PROPERTIES]" >&2
  echo "  $1" >&2
  exit 2
}

ZIP="${1:-}"
V="${2:-}"
PROPS="${3:-$HERE/../../scope-tracer-plugin/gradle.properties}"

if [ -z "$ZIP" ] || [ ! -f "$ZIP" ] || [ ! -r "$ZIP" ]; then
  usage "'$ZIP' is not a readable file"
fi
if [ -z "$V" ]; then
  usage "no version given"
fi
case "$V" in
  *SNAPSHOT*) usage "'$V' is a SNAPSHOT version; only a release zip is checked" ;;
esac
if [ ! -f "$PROPS" ] || [ ! -r "$PROPS" ]; then
  usage "gradle.properties '$PROPS' is not a readable file"
fi
SINCE_BUILD="$(sed -n 's/^[[:space:]]*pluginSinceBuild[[:space:]]*=[[:space:]]*\([^[:space:]]*\)[[:space:]]*$/\1/p' "$PROPS" | head -1)"
if [ -z "$SINCE_BUILD" ]; then
  usage "'$PROPS' declares no pluginSinceBuild"
fi

LISTING="$(unzip -Z1 "$ZIP" 2>/dev/null)" || {
  echo "check-plugin-zip.sh: '$ZIP' is not a readable zip" >&2
  exit 2
}

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

fail=0
err() {
  echo "::error file=$ZIP::$1: $2"
  fail=1
}

# The plugin jar is the one jar in lib/ that holds META-INF/plugin.xml (lib/ also holds :model).
PLUGIN_JARS=""
n=0
for entry in $(printf '%s\n' "$LISTING" | grep -E '^[^/]+/lib/[^/]+\.jar$'); do
  unzip -p "$ZIP" "$entry" > "$TMP/candidate.jar" 2>/dev/null || continue
  if unzip -Z1 "$TMP/candidate.jar" 2>/dev/null | grep -qx 'META-INF/plugin.xml'; then
    PLUGIN_JARS="$PLUGIN_JARS $entry"
    n=$((n + 1))
    cp "$TMP/candidate.jar" "$TMP/plugin.jar"
  fi
done

if [ "$n" -ne 1 ]; then
  if [ "$n" -eq 0 ]; then
    err plugin-jar "no jar in lib/ holds META-INF/plugin.xml"
  else
    err plugin-jar "$n jars in lib/ hold META-INF/plugin.xml:$PLUGIN_JARS"
  fi
  exit 1
fi

JAR="$TMP/plugin.jar"
JAR_LISTING="$(unzip -Z1 "$JAR")"

# plugin.xml on one line, and a copy without <description> (free text that could name anything).
XML="$(unzip -p "$JAR" META-INF/plugin.xml | tr '\r\n' '  ')"
XML_NODESC="$(printf '%s' "$XML" | awk '{
  s = $0
  while ((i = index(s, "<description")) > 0) {
    j = index(substr(s, i), "</description>")
    if (j == 0) { s = substr(s, 1, i - 1); break }
    s = substr(s, 1, i - 1) substr(s, i + j - 1 + length("</description>"))
  }
  print s
}')"

ID="$(printf '%s' "$XML_NODESC" | grep -o '<id>[^<]*</id>' | head -1 | sed 's#<id>\(.*\)</id>#\1#')"
if [ "$ID" != "$PLUGIN_ID" ]; then
  err id "plugin.xml <id> is '$ID', expected '$PLUGIN_ID'"
fi

XML_VERSION="$(printf '%s' "$XML_NODESC" | grep -o '<version>[^<]*</version>' | head -1 | sed 's#<version>\(.*\)</version>#\1#')"
if [ "$XML_VERSION" != "$V" ]; then
  err version "plugin.xml <version> is '$XML_VERSION', expected '$V'"
fi

XML_SINCE="$(printf '%s' "$XML_NODESC" | grep -o 'since-build="[^"]*"' | head -1 | sed 's#since-build="\(.*\)"#\1#')"
if [ "$XML_SINCE" != "$SINCE_BUILD" ]; then
  err since-build "plugin.xml since-build is '$XML_SINCE', expected '$SINCE_BUILD' (pluginSinceBuild in $PROPS)"
fi

NOTES="$(printf '%s' "$XML" | awk '{
  i = index($0, "<change-notes>")
  if (i == 0) exit
  s = substr($0, i + length("<change-notes>"))
  j = index(s, "</change-notes>")
  if (j == 0) exit
  print substr(s, 1, j - 1)
}' | sed -e 's/<!\[CDATA\[//g' -e 's/\]\]>//g' | tr -d '[:space:]')"
if [ -z "$NOTES" ]; then
  err change-notes "plugin.xml has no <change-notes>, or they are empty"
fi

if ! printf '%s\n' "$JAR_LISTING" | grep -qx 'META-INF/pluginIcon.svg'; then
  err icon "the plugin jar has no META-INF/pluginIcon.svg"
fi

AGENT_NAME="scope-tracer-agent-$V-agent.jar"
AGENT_JARS="$(printf '%s\n' "$JAR_LISTING" | grep -E '^agent/[^/]+\.jar$' | sed 's#^agent/##')"
AGENT_COUNT="$(printf '%s\n' "$AGENT_JARS" | grep -c .)"
if [ "$AGENT_COUNT" -ne 1 ]; then
  err agent-jar "$AGENT_COUNT agent/*.jar in the plugin jar, expected exactly one ($AGENT_NAME): $(printf '%s' "$AGENT_JARS" | tr '\n' ' ')"
elif [ "$AGENT_JARS" != "$AGENT_NAME" ]; then
  err agent-jar "agent/$AGENT_JARS is not named for $V (expected agent/$AGENT_NAME)"
fi

if ! printf '%s\n' "$JAR_LISTING" | grep -qx 'agent/agent-jar-name.txt'; then
  err agent-name "the plugin jar has no agent/agent-jar-name.txt"
else
  NAMED="$(unzip -p "$JAR" agent/agent-jar-name.txt | tr -d '[:space:]')"
  if [ "$NAMED" != "$AGENT_NAME" ]; then
    err agent-name "agent/agent-jar-name.txt says '$NAMED', expected '$AGENT_NAME'"
  elif ! printf '%s\n' "$AGENT_JARS" | grep -qx -- "$NAMED"; then
    err agent-name "agent/agent-jar-name.txt names '$NAMED', which is not in the plugin jar"
  fi
fi

POM_PROPS="META-INF/maven/com.ionutbanu/scope-tracer-analyzer/pom.properties"
if ! printf '%s\n' "$JAR_LISTING" | grep -qx 'analyzer/analyzer.jar'; then
  err analyzer "the plugin jar has no analyzer/analyzer.jar"
else
  unzip -p "$JAR" analyzer/analyzer.jar > "$TMP/analyzer.jar"
  if ! unzip -Z1 "$TMP/analyzer.jar" 2>/dev/null | grep -qx "$POM_PROPS"; then
    err analyzer "analyzer/analyzer.jar has no $POM_PROPS"
  else
    A_VERSION="$(unzip -p "$TMP/analyzer.jar" "$POM_PROPS" | tr -d '\r' | sed -n 's/^version=//p' | head -1)"
    if [ "$A_VERSION" != "$V" ]; then
      err analyzer "analyzer/analyzer.jar is version '$A_VERSION', expected '$V'"
    fi
  fi
fi

if [ "$fail" -eq 0 ]; then
  echo "plugin zip OK: $PLUGIN_ID $V, since-build $SINCE_BUILD, agent/$AGENT_NAME, analyzer $V"
fi
exit "$fail"
