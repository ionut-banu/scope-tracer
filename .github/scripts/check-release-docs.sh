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

VER_RE='[0-9]+\.[0-9]+\.[0-9]+(-SNAPSHOT)?'
checked=0

# Files a reader copies a version from. jbang-catalog.json does not exist yet (#57).
FILES="README.md CONTRIBUTING.md"
for f in docs/*.md docs/index.html jbang-catalog.json; do
  [ -f "$f" ] && FILES="$FILES $f"
done

for f in $FILES; do
  if [ ! -f "$f" ]; then
    err "$f" "file not found; the release docs check expects it"
    continue
  fi
  checked=$((checked + 1))

  # 1. A <version> within two lines after a scope-tracer <artifactId> (Maven snippets).
  found=0
  while IFS=: read -r line ver; do
    [ -n "$line" ] || continue
    found=$((found + 1))
    [ "$ver" = "$V" ] || err "$f" "line $line: dependency <version> is '$ver', releasing '$V'"
  done < <(tr -d '\r' < "$f" | awk '
    /<artifactId>scope-tracer-/ { a = NR }
    /<version>/ && a && NR - a <= 2 {
      s = $0
      sub(/.*<version>/, "", s)
      sub(/<\/version>.*/, "", s)
      print NR ":" s
      a = 0
    }')
  if [ "$f" = "docs/index.html" ] && ! tr -d '\r' < "$f" | grep -q -E "releases/download/v$VER_RE"; then
    err docs/index.html "no releases/download/vX.Y.Z URL found; the landing page's download links are gone or use a variable instead of a literal version"
  fi
  if [ "$f" = "README.md" ] && [ "$found" -lt 1 ]; then
    err README.md "no scope-tracer dependency <version> found; the quick-start snippet is gone or reshaped"
  fi

  # 2. Literal jar names, Maven coordinates and release download URLs.
  while IFS=: read -r line tok; do
    [ -n "$line" ] || continue
    ver=$(echo "$tok" | grep -oE "$VER_RE" | head -1)
    [ "$ver" = "$V" ] || err "$f" "line $line: '$tok' names version '$ver', releasing '$V'"
  done < <(tr -d '\r' < "$f" | grep -n -o -E "scope-tracer-(core|analyzer|agent|demos)-$VER_RE|com\.ionutbanu:scope-tracer-[a-z]+:$VER_RE|releases/download/v$VER_RE")
done

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
