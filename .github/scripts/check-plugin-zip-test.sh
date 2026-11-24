#!/usr/bin/env bash
# Self-test for check-plugin-zip.sh. Builds small fake plugin zips with zip and asserts the exit
# code and the annotations: one zip that passes, and one failure per rule. Never reads a real
# plugin zip. Run from anywhere:
#   .github/scripts/check-plugin-zip-test.sh
# Plain bash, zip, unzip, grep; runs under macOS bash 3.2 and Ubuntu bash 5.
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
CHECK="$HERE/check-plugin-zip.sh"
V=9.9.9
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

failures=0
cases=0

PROPS="$TMP/gradle.properties"
printf 'pluginGroup = com.ionutbanu.scopetracer\n# a comment\npluginSinceBuild = 263\n' > "$PROPS"

# make_zip OUT.zip [KEY=VALUE...] - a plugin zip for version $V, every rule holding, except what
# the arguments change:
#   id= version= since= notes= (plugin.xml fields; notes=EMPTY gives <change-notes/>,
#   notes=NONE leaves the element out), icon=no, agents="a.jar b.jar" (agent/*.jar names),
#   agentname= (agent-jar-name.txt; NONE leaves it out), analyzer=no, analyzerversion=,
#   pomprops=no, pluginxml=no (no jar holds plugin.xml), twoplugins=yes
make_zip() {
  local out="$1"
  shift
  local id="com.ionutbanu.scopetracer.plugin" version="$V" since="263"
  local notes="<p>See the GitHub Release $V.</p>" icon=yes
  local agents="scope-tracer-agent-$V-agent.jar" agentname="scope-tracer-agent-$V-agent.jar"
  local analyzer=yes analyzerversion="$V" pomprops=yes pluginxml=yes twoplugins=no
  local kv
  for kv in "$@"; do
    case "$kv" in
      id=*) id="${kv#id=}" ;;
      version=*) version="${kv#version=}" ;;
      since=*) since="${kv#since=}" ;;
      notes=*) notes="${kv#notes=}" ;;
      icon=*) icon="${kv#icon=}" ;;
      agents=*) agents="${kv#agents=}" ;;
      agentname=*) agentname="${kv#agentname=}" ;;
      analyzer=*) analyzer="${kv#analyzer=}" ;;
      analyzerversion=*) analyzerversion="${kv#analyzerversion=}" ;;
      pomprops=*) pomprops="${kv#pomprops=}" ;;
      pluginxml=*) pluginxml="${kv#pluginxml=}" ;;
      twoplugins=*) twoplugins="${kv#twoplugins=}" ;;
      *) echo "make_zip: unknown $kv" >&2; exit 3 ;;
    esac
  done

  local d="$TMP/tree"
  rm -rf "$d" "$out"
  mkdir -p "$d/analyzer-jar/META-INF/maven/com.ionutbanu/scope-tracer-analyzer" \
    "$d/plugin-jar/META-INF" "$d/plugin-jar/agent" "$d/plugin-jar/analyzer" \
    "$d/model-jar/com" "$d/zip/scope-tracer-plugin/lib"

  if [ "$pomprops" = yes ]; then
    printf 'artifactId=scope-tracer-analyzer\ngroupId=com.ionutbanu\nversion=%s\n' "$analyzerversion" \
      > "$d/analyzer-jar/META-INF/maven/com.ionutbanu/scope-tracer-analyzer/pom.properties"
  fi
  echo x > "$d/analyzer-jar/Main.class"
  (cd "$d/analyzer-jar" && zip -qr ../analyzer.jar .)
  if [ "$analyzer" = yes ]; then cp "$d/analyzer.jar" "$d/plugin-jar/analyzer/analyzer.jar"; fi

  local a
  for a in $agents; do echo x > "$d/plugin-jar/agent/$a"; done
  if [ "$agentname" != NONE ]; then printf '%s' "$agentname" > "$d/plugin-jar/agent/agent-jar-name.txt"; fi
  if [ "$icon" = yes ]; then echo '<svg/>' > "$d/plugin-jar/META-INF/pluginIcon.svg"; fi

  local notes_xml
  case "$notes" in
    EMPTY) notes_xml="  <change-notes><![CDATA[  ]]></change-notes>" ;;
    NONE) notes_xml="" ;;
    *) notes_xml="  <change-notes><![CDATA[$notes]]></change-notes>" ;;
  esac
  # The description names another id and version: the script must read the real elements.
  {
    echo "<idea-plugin>"
    echo "  <idea-version since-build=\"$since\" />"
    [ -n "$notes_xml" ] && echo "$notes_xml"
    echo "  <description><![CDATA[<p>Not <id>other.id</id>, not <version>0.0.1</version>.</p>]]></description>"
    echo "  <version>$version</version>"
    echo "  <id>$id</id>"
    echo "  <name>Scope Tracer</name>"
    echo "</idea-plugin>"
  } > "$d/plugin-xml"
  if [ "$pluginxml" = yes ]; then cp "$d/plugin-xml" "$d/plugin-jar/META-INF/plugin.xml"; fi
  echo x > "$d/plugin-jar/META-INF/MANIFEST.MF"
  (cd "$d/plugin-jar" && zip -qr "$d/zip/scope-tracer-plugin/lib/scope-tracer-plugin-$V.jar" .)
  if [ "$twoplugins" = yes ]; then
    cp "$d/zip/scope-tracer-plugin/lib/scope-tracer-plugin-$V.jar" "$d/zip/scope-tracer-plugin/lib/copy.jar"
  fi

  echo x > "$d/model-jar/com/Model.class"
  (cd "$d/model-jar" && zip -qr "$d/zip/scope-tracer-plugin/lib/model.jar" .)
  (cd "$d/zip" && zip -qr "$out" scope-tracer-plugin)
}

# expect NAME EXPECTED_EXIT GREP_PATTERN_OR_EMPTY ARGS...
expect() {
  local name="$1" want="$2" pat="$3" out rc
  shift 3
  cases=$((cases + 1))
  out="$(bash "$CHECK" "$@" 2>&1)"
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

# check NAME PATTERN make_zip-ARGS... - builds the zip, expects exit 1 and PATTERN
check_fails() {
  local name="$1" pat="$2"
  shift 2
  make_zip "$TMP/case.zip" "$@"
  expect "$name" 1 "$pat" "$TMP/case.zip" "$V" "$PROPS"
}

make_zip "$TMP/good.zip"
expect "good zip passes" 0 "plugin zip OK" "$TMP/good.zip" "$V" "$PROPS"
n="$(bash "$CHECK" "$TMP/good.zip" "$V" "$PROPS" 2>&1 | grep -c '^::error')"
cases=$((cases + 1))
if [ "$n" -eq 0 ]; then echo "ok   good zip: no annotations"; else
  echo "FAIL good zip: $n annotations, wanted 0"
  failures=$((failures + 1))
fi

check_fails "no plugin.xml fails" "::error.*plugin-jar: no jar" pluginxml=no
check_fails "two plugin jars fail" "::error.*plugin-jar: 2 jars" twoplugins=yes
check_fails "wrong id fails" "::error.*id: plugin.xml <id> is 'com.example.other'" id=com.example.other
check_fails "wrong version fails" "::error.*version: plugin.xml <version> is '9.9.8'" version=9.9.8
check_fails "wrong since-build fails" "::error.*since-build: plugin.xml since-build is '252'" since=252
check_fails "empty change notes fail" "::error.*change-notes:" notes=EMPTY
check_fails "missing change notes fail" "::error.*change-notes:" notes=NONE
check_fails "missing icon fails" "::error.*icon: .*pluginIcon.svg" icon=no
check_fails "two agent jars fail" "::error.*agent-jar: 2 agent/\*.jar" \
  "agents=scope-tracer-agent-$V-agent.jar scope-tracer-agent-0.0.1-agent.jar"
check_fails "no agent jar fails" "::error.*agent-jar: 0 agent/\*.jar" agents=
check_fails "agent jar of another version fails" "::error.*agent-jar: agent/scope-tracer-agent-0.0.1-agent.jar is not named for $V" \
  agents=scope-tracer-agent-0.0.1-agent.jar agentname=scope-tracer-agent-0.0.1-agent.jar
check_fails "agent-jar-name.txt naming another jar fails" "::error.*agent-name: .*says 'scope-tracer-agent-0.0.1-agent.jar'" \
  agentname=scope-tracer-agent-0.0.1-agent.jar
check_fails "missing agent-jar-name.txt fails" "::error.*agent-name: .*no agent/agent-jar-name.txt" agentname=NONE
check_fails "no analyzer jar fails" "::error.*analyzer: the plugin jar has no analyzer/analyzer.jar" analyzer=no
check_fails "wrong analyzer version fails" "::error.*analyzer: analyzer/analyzer.jar is version '9.9.8'" analyzerversion=9.9.8
check_fails "analyzer without pom.properties fails" "::error.*analyzer: .*has no META-INF/maven" pomprops=no

# Every failure is reported, not only the first.
make_zip "$TMP/many.zip" version=9.9.8 icon=no analyzerversion=9.9.8
n="$(bash "$CHECK" "$TMP/many.zip" "$V" "$PROPS" 2>&1 | grep -c '^::error')"
cases=$((cases + 1))
if [ "$n" -eq 3 ]; then echo "ok   three broken rules: exactly three annotations"; else
  echo "FAIL three broken rules: $n annotations, wanted 3"
  failures=$((failures + 1))
fi

# Arguments.
expect "SNAPSHOT version is refused" 2 "SNAPSHOT" "$TMP/good.zip" "9.9.9-SNAPSHOT" "$PROPS"
expect "no version exits 2" 2 "no version" "$TMP/good.zip"
expect "nonexistent zip exits 2" 2 "" "$TMP/nope.zip" "$V" "$PROPS"
expect "no argument exits 2" 2 "" ""
echo "not a zip" > "$TMP/bad.zip"
expect "not a zip exits 2" 2 "" "$TMP/bad.zip" "$V" "$PROPS"
expect "missing gradle.properties exits 2" 2 "gradle.properties" "$TMP/good.zip" "$V" "$TMP/nope.properties"
echo "pluginGroup = x" > "$TMP/nosince.properties"
expect "gradle.properties without pluginSinceBuild exits 2" 2 "pluginSinceBuild" "$TMP/good.zip" "$V" "$TMP/nosince.properties"
# Without the third argument the script reads the repository's own gradle.properties.
expect "default gradle.properties is the repository's" 0 "plugin zip OK" "$TMP/good.zip" "$V"

echo "$cases checks, $failures failed"
[ "$failures" -eq 0 ]
