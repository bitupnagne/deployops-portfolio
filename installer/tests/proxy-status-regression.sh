#!/usr/bin/env bash
# Real status entry point, isolated command stubs; no host nginx or lock writes.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
source <(sed 's/\r$//' "$repo/installer/deploy-agent/web/deploy-web-proxy.sh" | head -n -1)
fixture="$(mktemp -d)"
trap 'rm -rf -- "$fixture"' EXIT
require_root(){ :; }
nginx(){ echo 'status must not invoke nginx' >&2; exit 91; }
flock(){ echo 'status must not acquire a write lock' >&2; exit 92; }
systemctl(){ [[ "$*" == 'is-active --quiet nginx' ]]; return "${NGINX_STATUS:-0}"; }
curl(){
  printf '%s\n' "$@" > "$fixture/curl-args"
  printf 'call\n' >> "$fixture/calls"
  return "${HTTP_STATUS:-0}"
}
LOCK_FILE="$fixture/lock"
HEALTH_HOST='' HEALTH_PORT='' HEALTH_PATH='/'
main status --health-port 8081 --health-path '/{bad,good}' --health-host localhost > "$fixture/output"
grep -qx 'NGINX_ACTIVE=true' "$fixture/output"
grep -qx 'HTTP_HEALTHY=true' "$fixture/output"
[[ ! -e "$LOCK_FILE" && "$(wc -l < "$fixture/calls")" == 1 ]]
[[ "$(head -n 1 "$fixture/curl-args")" == '-q' ]]
grep -qx -- '--globoff' "$fixture/curl-args"
grep -qx 'http://127.0.0.1:8081/{bad,good}' "$fixture/curl-args"
grep -qx 'Host: localhost' "$fixture/curl-args"
printf '%s\n' '-q' '--globoff' '--noproxy' '*' '-fsS' '--connect-timeout' '3' '--max-time' '10' '-H' 'Host: localhost' 'http://127.0.0.1:8081/{bad,good}' > "$fixture/expected-args"
cmp "$fixture/expected-args" "$fixture/curl-args"
(HTTP_STATUS=22; main status) > "$fixture/unhealthy"
grep -qx 'NGINX_ACTIVE=true' "$fixture/unhealthy"
grep -qx 'HTTP_HEALTHY=false' "$fixture/unhealthy"
(NGINX_STATUS=3 HTTP_STATUS=7; main status) > "$fixture/stopped"
grep -qx 'NGINX_ACTIVE=false' "$fixture/stopped"
grep -qx 'HTTP_HEALTHY=false' "$fixture/stopped"
[[ ! -e "$LOCK_FILE" && "$(wc -l < "$fixture/calls")" == 3 ]]
for bad in 'http://other/' '//other/' '/bad path'; do
  if (main status --health-path "$bad") >/dev/null 2>&1; then exit 1; fi
done
for bad in 'bad:80' '*' $'localhost\r\nInjected:true'; do
  if (main status --health-host "$bad") >/dev/null 2>&1; then exit 1; fi
done
[[ ! -e "$LOCK_FILE" && "$(wc -l < "$fixture/calls")" == 3 ]]
echo 'PROXY_STATUS_REGRESSION_PASS: healthy, HTTP failure, stopped, literal bounded curl, no lock/reload, invalid inputs'
