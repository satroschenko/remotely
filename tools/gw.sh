#!/usr/bin/env bash
# Gradle wrapper launcher: JDK 21 + optional HTTP(S)_PROXY from the environment (nothing is stored on disk).
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
OPTS=()
P=""; [[ "${GW_USE_PROXY:-0}" == "1" ]] && P="${HTTPS_PROXY:-${https_proxy:-}}"
if [[ -n "$P" ]]; then
  rest="${P#*://}"
  creds=""; hostport="$rest"
  if [[ "$rest" == *@* ]]; then creds="${rest%@*}"; hostport="${rest##*@}"; fi
  host="${hostport%:*}"; port="${hostport##*:}"; port="${port%/}"
  for s in http https; do OPTS+=("-D$s.proxyHost=$host" "-D$s.proxyPort=$port" "-D$s.nonProxyHosts=localhost|127.0.0.1|*.tv.zala.by"); done
  if [[ -n "$creds" ]]; then
    for s in http https; do OPTS+=("-D$s.proxyUser=${creds%%:*}" "-D$s.proxyPassword=${creds#*:}"); done
    OPTS+=("-Djdk.http.auth.tunneling.disabledSchemes=" "-Djdk.http.auth.proxying.disabledSchemes=")
  fi
fi
exec ./gradlew ${OPTS[@]+"${OPTS[@]}"} "$@"
