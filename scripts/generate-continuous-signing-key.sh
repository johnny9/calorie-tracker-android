#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
secret_dir="$repo_root/.secrets"
keystore="$secret_dir/calorie-tracker-continuous.jks"
properties="$secret_dir/continuous-signing.properties"

umask 077
mkdir -p "$secret_dir"

if [[ -e "$keystore" || -e "$properties" ]]; then
  echo "Refusing to replace existing signing material in $secret_dir" >&2
  exit 1
fi

store_password="$(openssl rand -hex 32)"
key_password="$(openssl rand -hex 32)"
key_alias="calorie-tracker"

keytool -genkeypair -noprompt \
  -keystore "$keystore" \
  -storetype JKS \
  -storepass "$store_password" \
  -alias "$key_alias" \
  -keypass "$key_password" \
  -keyalg RSA \
  -keysize 4096 \
  -validity 36500 \
  -dname "CN=Calorie Tracker Continuous Build,O=Calorie Tracker,C=US"

{
  printf 'ANDROID_KEYSTORE_PATH=%q\n' "$keystore"
  printf 'ANDROID_STORE_PASSWORD=%q\n' "$store_password"
  printf 'ANDROID_KEY_ALIAS=%q\n' "$key_alias"
  printf 'ANDROID_KEY_PASSWORD=%q\n' "$key_password"
} > "$properties"

echo "Created one permanent signing identity in $secret_dir"
echo "Back up both files securely before distributing an APK. They cannot be recovered from GitHub."
