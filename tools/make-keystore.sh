#!/usr/bin/env bash
# Creates the release signing key once: release.jks + keystore.properties (both git-ignored).
# Keep both files safe: app updates must be signed with the same key.
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -f keystore.properties ]]; then echo "keystore.properties already exists"; exit 0; fi
PASS="$(openssl rand -hex 24)"
"$(/usr/libexec/java_home -v 21)/bin/keytool" -genkeypair -keystore release.jks -alias zalatv \
  -storepass "$PASS" -keypass "$PASS" -keyalg RSA -keysize 2048 -validity 36500 -dname "CN=Zala TV" >/dev/null
umask 077
cat > keystore.properties <<PROPS
storeFile=release.jks
storePassword=$PASS
keyAlias=zalatv
keyPassword=$PASS
PROPS
echo "Created release.jks and keystore.properties (git-ignored). Back them up."
