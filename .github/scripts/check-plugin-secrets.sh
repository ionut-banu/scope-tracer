#!/usr/bin/env bash
# Decides, before anything is built, whether release.yml's publish-plugin job can sign the plugin
# zip and whether it uploads it to the JetBrains Marketplace. It never sees a secret's value: the
# workflow passes, for each secret, whether it is set ('true' or 'false', from an expression
# `secrets.X != ''`), and the value of the repository variable PLUGIN_MARKETPLACE_PUBLISH.
#
# Environment:
#   JETBRAINS_CERTIFICATE_CHAIN_SET, JETBRAINS_PRIVATE_KEY_SET,
#   JETBRAINS_PRIVATE_KEY_PASSWORD_SET, JETBRAINS_MARKETPLACE_TOKEN_SET  'true' when set
#   PLUGIN_MARKETPLACE_PUBLISH  the repository variable; 'true' turns the Marketplace upload on
#   GITHUB_OUTPUT               when set, receives marketplace=true|false
#
# Rules:
#   - any of the three signing secrets missing: fail, naming every missing one. The job must
#     never upload an unsigned zip, to the GitHub Release or to the Marketplace.
#   - PLUGIN_MARKETPLACE_PUBLISH is 'true' and JETBRAINS_MARKETPLACE_TOKEN is missing: fail,
#     naming it.
#   - PLUGIN_MARKETPLACE_PUBLISH is anything but 'true': marketplace=false and a ::notice:: that
#     the first upload is manual (#198).
#
# Exit codes: 0 the job can go on, 1 a secret it needs is missing (every one is listed).
# Plain bash; runs under macOS bash 3.2 and Ubuntu bash 5.
set -u

fail=0
missing() {
  echo "::error::repository secret $1 is not set: $2"
  fail=1
}

for s in JETBRAINS_CERTIFICATE_CHAIN JETBRAINS_PRIVATE_KEY JETBRAINS_PRIVATE_KEY_PASSWORD; do
  var="${s}_SET"
  if [ "${!var:-false}" != "true" ]; then
    missing "$s" "the plugin zip is never published unsigned (see docs/jetbrains-marketplace-setup.md)"
  fi
done

marketplace=false
if [ "${PLUGIN_MARKETPLACE_PUBLISH:-}" = "true" ]; then
  if [ "${JETBRAINS_MARKETPLACE_TOKEN_SET:-false}" != "true" ]; then
    missing JETBRAINS_MARKETPLACE_TOKEN "PLUGIN_MARKETPLACE_PUBLISH is true, so the zip is uploaded to the JetBrains Marketplace"
  fi
  marketplace=true
fi

if [ "$fail" -ne 0 ]; then
  exit 1
fi

if [ "$marketplace" = "true" ]; then
  echo "Signing secrets set; the signed zip goes to the GitHub Release and the JetBrains Marketplace"
else
  echo "::notice::Not uploading to the JetBrains Marketplace: the repository variable PLUGIN_MARKETPLACE_PUBLISH is not 'true'. The first upload is manual (#198); set the variable after it. The signed zip still goes to the GitHub Release."
fi
if [ -n "${GITHUB_OUTPUT:-}" ]; then
  echo "marketplace=$marketplace" >> "$GITHUB_OUTPUT"
fi
exit 0
