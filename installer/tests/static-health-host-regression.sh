#!/usr/bin/env bash
# Ensure static deployment health checks address the Nginx vhost that serves the release.
set -euo pipefail

repo="$(cd "$(dirname "$0")/../.." && pwd)"
source <(sed 's/\r$//' "$repo/installer/deploy-agent/web/deploy-web-static.sh" | head -n -1)

fixture=''
nginx(){
  [[ "${1:-}" == '-T' ]] || return 2
  printf '%s\n' "$fixture"
}

fixture=$'server {\n    listen 80 default_server;\n    server_name _;\n    root /var/www/legacy;\n}\nserver {\n    listen 80;\n    server_name uiqa.local www.uiqa.local;\n    location / {\n        root /var/www/app/current;\n        try_files $uri $uri/ /index.html;\n    }\n}'
[[ "$(active_health_host_for_web_root /var/www/app)" == 'uiqa.local' ]]

fixture=$'server {\n    listen 80 default_server;\n    server_name _;\n    location / {\n        root /var/www/app/current;\n        try_files $uri $uri/ =404;\n    }\n}'
[[ "$(active_health_host_for_web_root /var/www/app)" == 'deployops-healthcheck.invalid' ]]

fixture=$'server {\n    listen 80;\n    server_name uiqa.local;\n    root /var/www/app;\n}'
if active_health_host_for_web_root /var/www/app >/dev/null 2>&1; then
  echo 'A non-default vhost without the release root was accepted.' >&2
  exit 1
fi

source <(sed 's/\r$//' "$repo/installer/deploy-agent/local-healthcheck.sh" | head -n -1)
CONFIG_FILE="$repo/installer/tests/no-healthcheck-config"
CURL_ARGS="$(mktemp)"
trap 'rm -f -- "$CURL_ARGS"' EXIT
curl(){ printf '%s\n' "$*" > "$CURL_ARGS"; return 0; }
export -f curl
(main --port 80 --path / --host uiqa.local --retries 1 --sleep 0) >/dev/null
grep -F -- '-H Host: uiqa.local' "$CURL_ARGS" >/dev/null
if (main --port 80 --path / --host 'bad host' --retries 1 --sleep 0) > /dev/null 2>&1; then
  echo 'The health checker accepted an invalid Host header.' >&2
  exit 1
fi

printf 'PASS: static WEB health check selects the active vhost and rejects invalid Host values\n'
