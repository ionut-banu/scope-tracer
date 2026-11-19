#!/usr/bin/env bash
# The README's lead image: a screenshot of docs/gallery/reports/fail-fast.html.
#
# Usage: .github/scripts/readme-image.sh [--check] [ROOT]
#   (no option)  shoot docs/img/readme-report.png with headless Chrome and write
#                docs/img/readme-report.source, the SHA-256 of the report it was shot from.
#                Chrome is $CHROME, or found among the macOS and common Linux names;
#                none found exits 2.
#   --check      needs no Chrome. Exit 0 when the committed report's SHA-256 equals the
#                sidecar and the PNG exists; exit 1 with one ::error annotation otherwise.
#   ROOT         the tree to work in (default: the repository this script is in).
#
# A PNG cannot be compared byte for byte (times and thread names change every run), so the
# hash of the report it came from is what stops it drifting: regenerate the gallery, and
# --check says to shoot the image again.
# Plain bash; runs under macOS bash 3.2 and Ubuntu bash 5.
set -u

CHECK=0
ROOT=""
for a in "$@"; do
  case "$a" in
    --check) CHECK=1 ;;
    -*) echo "usage: readme-image.sh [--check] [ROOT]" >&2; exit 2 ;;
    *) ROOT="$a" ;;
  esac
done
[ -n "$ROOT" ] || ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT" || exit 2

REPORT=docs/gallery/reports/fail-fast.html
PNG=docs/img/readme-report.png
SIDECAR=docs/img/readme-report.source

sha() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

if [ ! -f "$REPORT" ]; then
  echo "readme-image.sh: $REPORT not found" >&2
  exit 2
fi

if [ "$CHECK" -eq 1 ]; then
  msg="run .github/scripts/readme-image.sh and commit docs/img/readme-report.png and docs/img/readme-report.source"
  if [ ! -f "$PNG" ]; then
    echo "::error file=$PNG::$PNG is missing; $msg"
    exit 1
  fi
  if [ ! -f "$SIDECAR" ]; then
    echo "::error file=$PNG::$SIDECAR is missing; $msg"
    exit 1
  fi
  if [ "$(sha "$REPORT")" != "$(tr -d ' \r\n' < "$SIDECAR")" ]; then
    echo "::error file=$PNG::$REPORT changed since the image was shot; $msg"
    exit 1
  fi
  echo "readme image matches $REPORT"
  exit 0
fi

find_chrome() {
  if [ -n "${CHROME:-}" ]; then
    echo "$CHROME"
    return
  fi
  local c
  for c in \
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
    "/Applications/Chromium.app/Contents/MacOS/Chromium" \
    google-chrome google-chrome-stable chromium chromium-browser chrome; do
    if [ -x "$c" ]; then echo "$c"; return; fi
    if command -v "$c" >/dev/null 2>&1; then command -v "$c"; return; fi
  done
}
BIN="$(find_chrome)"
if [ -z "$BIN" ] || { [ ! -x "$BIN" ] && ! command -v "$BIN" >/dev/null 2>&1; }; then
  echo "readme-image.sh: no Chrome found; set CHROME to a Chrome or Chromium binary" >&2
  exit 2
fi

mkdir -p docs/img
profile="$(mktemp -d)"
trap 'rm -rf "$profile"' EXIT
"$BIN" --headless --disable-gpu --no-sandbox --user-data-dir="$profile" \
  --window-size=1100,700 --hide-scrollbars --force-device-scale-factor=1 \
  --virtual-time-budget=3000 --screenshot="$ROOT/$PNG" "file://$ROOT/$REPORT" >/dev/null 2>&1
if [ ! -s "$PNG" ]; then
  echo "readme-image.sh: Chrome ($BIN) did not write $PNG" >&2
  exit 2
fi
sha "$REPORT" > "$SIDECAR"
echo "wrote $PNG ($(wc -c < "$PNG" | tr -d ' ') bytes) and $SIDECAR"
