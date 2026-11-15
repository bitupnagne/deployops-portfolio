#!/usr/bin/env bash
# Execute the real reload/health functions without mutating host nginx or root config.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
source <(sed 's/\r$//' "$repo/installer/deploy-agent/web/deploy-web-proxy.sh" | head -n -1)
fixture="$(mktemp -d)"
trap 'rm -rf -- "$fixture"' EXIT
nginx(){ printf 'nginx %s\n' "$*" >> "$fixture/trace"; return "${NGINX_EXIT:-0}"; }
systemctl(){ printf 'systemctl %s\n' "$*" >> "$fixture/trace"; return "${RELOAD_EXIT:-0}"; }
curl(){ printf 'curl %s\n' "$*" >> "$fixture/trace"; return "${CURL_EXIT:-0}"; }
sleep(){ printf 'sleep %s\n' "$*" >> "$fixture/trace"; }
HEALTH_PORT=48080 HEALTH_PATH='/ready?probe=1'
validate_health
run_reload > "$fixture/output"
grep -F 'http://127.0.0.1:48080/ready?probe=1' "$fixture/trace" >/dev/null
grep -F -- 'curl -q --globoff --noproxy * -fsS --connect-timeout 3 --max-time 10' "$fixture/trace" >/dev/null
grep -F 'Proxy HTTP health check passed' "$fixture/output" >/dev/null
[[ "$(grep -c '^curl ' "$fixture/trace")" == 1 ]]
: > "$fixture/trace"
if (CURL_EXIT=22; run_reload) > "$fixture/failure" 2>&1; then
  echo 'Unhealthy HTTP response was accepted after a successful nginx reload.' >&2; exit 1
fi
[[ "$(grep -c '^curl ' "$fixture/trace")" == 10 ]]
grep -F 'failed after 10 attempts' "$fixture/failure" >/dev/null
[[ "$(grep -c '^sleep 3$' "$fixture/trace")" == 9 ]]
# 각 호출이 제한 시간까지 실패해도 SSH 외부 240초를 넘지 않는 유한 상한.
[[ "$HEALTH_ATTEMPTS" -eq 10 && "$HEALTH_MAX_TIME" -eq 10 ]]
[[ "$((HEALTH_ATTEMPTS * HEALTH_MAX_TIME + (HEALTH_ATTEMPTS - 1) * 3))" -eq 127 ]]
[[ "$((HEALTH_ATTEMPTS * HEALTH_MAX_TIME + (HEALTH_ATTEMPTS - 1) * 3))" -lt 240 ]]
for bad in 0 -1 65536 abc; do
  if (HEALTH_PORT="$bad"; validate_health) > /dev/null 2>&1; then exit 1; fi
done
for bad in 'http://other/' '//other/' '/has space' $'/line\nheader' '/a#fragment'; do
  if (HEALTH_PORT=80 HEALTH_PATH="$bad"; validate_health) > /dev/null 2>&1; then exit 1; fi
done
for option in --health-port --health-path; do
  if (parse_args reload "$option") > /dev/null 2>&1; then exit 1; fi
done
: > "$fixture/trace"
if (NGINX_EXIT=1; run_reload) > /dev/null 2>&1; then exit 1; fi
! grep -qE '^(systemctl|curl) ' "$fixture/trace"
: > "$fixture/trace"
if (RELOAD_EXIT=1; run_reload) > /dev/null 2>&1; then exit 1; fi
! grep -q '^curl ' "$fixture/trace"
HEALTH_PORT=80 HEALTH_PATH=/
validate_health
# Legacy deploy follows its explicit listener; explicit health port still overrides it.
(ACTION=deploy HEALTH_PORT='' LISTEN_PORT=18081; validate_health; [[ "$HEALTH_PORT" == 18081 ]])
(ACTION=reload HEALTH_PORT='' LISTEN_PORT=18081; validate_health; [[ "$HEALTH_PORT" == 80 ]])
(ACTION=deploy HEALTH_PORT=19090 LISTEN_PORT=18081; validate_health; [[ "$HEALTH_PORT" == 19090 ]])
for bad in 0 -1 65536; do
  if (ACTION=deploy HEALTH_PORT='' LISTEN_PORT="$bad"; validate_health) >/dev/null 2>&1; then exit 1; fi
  if (UPSTREAM=was:8080 LISTEN_PORT="$bad"; validate_inputs) >/dev/null 2>&1; then exit 1; fi
done
# Curl must receive each configured path as one literal URL, without glob expansion or curlrc overrides.
curl(){
  [[ "$#" == 10 && "$1" == '-q' && "$2" == '--globoff' && "${10}" == "$EXPECTED_URL" ]]
}
for literal in '/[1-1000000000]' '/{bad,good}'; do
  (HEALTH_PATH="$literal" EXPECTED_URL="http://127.0.0.1:80${literal}"; validate_health; check_proxy_health) >/dev/null
done
curl(){ printf 'curl %s\n' "$*" >> "$fixture/trace"; return "${CURL_EXIT:-0}"; }
require_root(){ :; }
flock(){ :; }
LOCK_FILE="$fixture/lock"
: > "$fixture/trace"
if (main reload --health-port 65536) > /dev/null 2>&1; then exit 1; fi
[[ ! -s "$fixture/trace" ]]
if (command(){ if [[ "$*" == '-v curl' ]]; then return 1; else builtin command "$@"; fi; }; main reload) > /dev/null 2>&1; then
  echo 'Missing curl was accepted.' >&2; exit 1
fi
# Config-changing legacy deploy also restores its prior config and fails on HTTP error.
resolve_conf_path(){ printf '%s\n' "$fixture/proxy.conf"; }
assert_owns_config(){ :; }
assert_no_conflicting_server(){ :; }
render_config(){ printf '%s\n' 'new config' > "$1"; }
prune_backups(){ echo 'pruned' >> "$fixture/trace"; }
printf '%s\n' 'old config' > "$fixture/proxy.conf"
BACKUP_ROOT="$fixture/backups" UPSTREAM='was:8080'
if (CURL_EXIT=7; run_deploy) > "$fixture/deploy-failure" 2>&1; then exit 1; fi
[[ "$(cat "$fixture/proxy.conf")" == 'old config' ]]
! grep -q '^pruned' "$fixture/trace"
for bad in 'http://host/' 'host:80' '*.internal' 'bad host' $'host\r\nX: y' '-host' 'host..internal'; do
  if (HEALTH_HOST="$bad"; validate_health) >/dev/null 2>&1; then exit 1; fi
done
# Real HTTP fixture: same socket serves a healthy named vhost and an unhealthy default.
command -v python3 >/dev/null || { echo 'python3 is required for the HTTP vhost fixture.' >&2; exit 1; }
cat > "$fixture/vhost.py" <<'PY'
import http.server, pathlib, sys, time
class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == '/failover': time.sleep(3.05)
        self.send_response(200 if self.headers.get('Host') == 'portal.internal' else 503)
        self.end_headers()
    def log_message(self, *_): pass
server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
pathlib.Path(sys.argv[1]).write_text(str(server.server_port))
server.serve_forever()
PY
python3 "$fixture/vhost.py" "$fixture/port" &
fixture_pid=$!
trap 'kill "$fixture_pid" 2>/dev/null || true; wait "$fixture_pid" 2>/dev/null || true; rm -rf -- "$fixture"' EXIT
for ((i=0; i<100; i++)); do
  if [[ -s "$fixture/port" ]]; then break; fi
  command sleep 0.05
done
[[ -s "$fixture/port" ]]
unset -f curl
HEALTH_PORT="$(cat "$fixture/port")" HEALTH_PATH=/ HEALTH_HOST=portal.internal
validate_health
check_proxy_health > "$fixture/real-success"
grep -q 'health check passed' "$fixture/real-success"
if (HEALTH_HOST=wrong.internal; check_proxy_health) >/dev/null 2>&1; then
  echo 'Wrong virtual host was accepted on the same HTTP port.' >&2; exit 1
fi
if (HEALTH_HOST=''; check_proxy_health) >/dev/null 2>&1; then
  echo 'Unhealthy default was accepted on the same HTTP port.' >&2; exit 1
fi
# 현재 connect=3 / next_upstream_timeout=6 계약: 3초 client는 failover 응답 전에 끊긴다.
if command curl -q --noproxy '*' -fsS --connect-timeout 3 --max-time 3 \
    -H 'Host: portal.internal' "http://127.0.0.1:${HEALTH_PORT}/failover" >/dev/null 2>&1; then
  echo 'The old three-second deadline unexpectedly accepted a delayed failover response.' >&2; exit 1
else
  [[ "$?" == 28 ]]
fi
HEALTH_PATH=/failover
run_status > "$fixture/delayed-status"
grep -qx 'HTTP_HEALTHY=true' "$fixture/delayed-status"
run_reload > "$fixture/delayed-reload"
grep -q 'health check passed' "$fixture/delayed-reload"
[[ "$HEALTH_MAX_TIME" -gt 6 && "$HEALTH_MAX_TIME" -eq 10 ]]
printf 'PASS: proxy reload enforces configured bounded HTTP health and propagates failures\n'
