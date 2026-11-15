#!/usr/bin/env bash
# Real nginx, temporary config/pid/files, random high ports. No live config/systemctl changes.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
for tool in nginx curl openssl ss; do command -v "$tool" >/dev/null || { echo "$tool is required" >&2; exit 1; }; done
task_tmp=$(mktemp -d)
task_started=0
cleanup() {
  if ((task_started)); then nginx -p "$task_tmp/" -c "$task_tmp/nginx.conf" -s quit 2>/dev/null || true; fi
  rm -rf "$task_tmp"
}
trap cleanup EXIT
. "$SCRIPT_DIR/components/nginx.sh"
# Source actual policy functions without its root/lock/dispatch entry point.
source <(sed '/^\[\[ "\$EUID" -eq 0 \]\]/,$d' "$SCRIPT_DIR/deploy-agent/web/deploy-nginx-policy.sh")
task_port=$((20000 + RANDOM % 20000))
task_tls_port=$((task_port + 1)); task_upstream_port=$((task_port + 2))
for port in "$task_port" "$task_tls_port" "$task_upstream_port"; do
  if ss -ltn | grep -qE ":${port}[[:space:]]"; then echo 'Random test port is busy; retry test' >&2; exit 1; fi
done
chmod 755 "$task_tmp"
mkdir -p "$task_tmp/conf.d" "$task_tmp/static/current"
printf 'STATIC-DEFAULT\n' > "$task_tmp/static/current/index.html"
_nginx_static_block "$task_tmp/static" "$task_port" 50m > "$task_tmp/conf.d/officesystem.conf"
REQUEST_ID=1 SERVER_NAME=http.named.test LISTEN_PORT=$task_port UPSTREAM_HOST=127.0.0.1 UPSTREAM_PORT=$task_upstream_port TLS=false
render_config "$task_tmp/conf.d/deploy-http.conf" '' ''
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj /CN=tls.named.test -keyout "$task_tmp/key.pem" -out "$task_tmp/cert.pem" >/dev/null 2>&1
REQUEST_ID=2 SERVER_NAME=tls.named.test LISTEN_PORT=$task_tls_port TLS=true REDIRECT_HTTP=true HTTP2=false
render_config "$task_tmp/conf.d/deploy-tls.conf" "$task_tmp/cert.pem" "$task_tmp/key.pem"
# Map only the generator's fixed public HTTP redirect listener to the isolated test port.
sed -i "s/listen 80; server_name/listen ${task_port}; server_name/" "$task_tmp/conf.d/deploy-tls.conf"
cat > "$task_tmp/nginx.conf" <<EOF
pid $task_tmp/nginx.pid;
error_log $task_tmp/error.log;
events {}
http {
  access_log off;
  client_body_temp_path $task_tmp/client_temp;
  proxy_temp_path $task_tmp/proxy_temp;
  include $task_tmp/conf.d/*.conf;
  server { listen 127.0.0.1:$task_upstream_port; return 200 'NAMED-UPSTREAM'; }
}
EOF
# Bind generated listeners only to loopback in this temporary test configuration.
sed -i "s/listen ${task_port}/listen 127.0.0.1:${task_port}/g; s/listen ${task_tls_port}/listen 127.0.0.1:${task_tls_port}/g" "$task_tmp/conf.d/"*.conf
nginx -p "$task_tmp/" -c "$task_tmp/nginx.conf" -t
nginx -p "$task_tmp/" -c "$task_tmp/nginx.conf"
task_started=1
fetch() { curl --noproxy '*' --silent --show-error --max-time 3 "$@"; }
[[ $(fetch "http://127.0.0.1:$task_port/") == STATIC-DEFAULT ]]
[[ $(fetch -H 'Host: unknown.test' "http://127.0.0.1:$task_port/") == STATIC-DEFAULT ]]
[[ $(fetch -H 'Host: http.named.test' "http://127.0.0.1:$task_port/") == NAMED-UPSTREAM ]]
fetch -D "$task_tmp/redirect.headers" -o /dev/null -H 'Host: tls.named.test' "http://127.0.0.1:$task_port/path?q=1"
grep -qE '^HTTP/1.1 301 ' "$task_tmp/redirect.headers"
grep -Fq 'Location: https://tls.named.test/path?q=1' "$task_tmp/redirect.headers"
[[ $(fetch --insecure -H 'Host: tls.named.test' "https://127.0.0.1:$task_tls_port/") == NAMED-UPSTREAM ]]
echo 'nginx-default-server-regression: PASS (implicit-host static, unknown-host static, named HTTP proxy, named HTTP-to-HTTPS redirect, named TLS proxy)'
