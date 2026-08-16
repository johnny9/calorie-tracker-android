#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
properties="$repo_root/.secrets/continuous-signing.properties"

if [[ ! -f "$properties" ]]; then
  echo "Run scripts/generate-continuous-signing-key.sh first" >&2
  exit 1
fi

set -a
source "$properties"
set +a

gh auth status >/dev/null
base64 -w 0 "$ANDROID_KEYSTORE_PATH" | gh secret set --env continuous-signing ANDROID_SIGNING_KEYSTORE_BASE64
printf '%s' "$ANDROID_STORE_PASSWORD" | gh secret set --env continuous-signing ANDROID_SIGNING_STORE_PASSWORD
printf '%s' "$ANDROID_KEY_ALIAS" | gh secret set --env continuous-signing ANDROID_SIGNING_KEY_ALIAS
printf '%s' "$ANDROID_KEY_PASSWORD" | gh secret set --env continuous-signing ANDROID_SIGNING_KEY_PASSWORD

echo "Uploaded continuous-signing environment secrets. The local backup remains authoritative."
