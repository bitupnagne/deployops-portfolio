#!/usr/bin/env bash
# Real renderer and nginx on temporary loopback high ports; live configs/services are untouched.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
for tool in nginx curl ss; do command -v "$tool" >/dev/null || { echo "$tool is required" >&2; exit 1; }; done
task_tmp=$(mktemp -d); task_started=0
cleanup() {
  if ((task_started)); then nginx -p "$task_tmp/" -c "$task_tmp/nginx.conf" -s quit 2>/dev/null || true; fi
  rm -rf "$task_tmp"
}
trap cleanup EXIT
source <(sed '/^\[\[ "\$EUID" -eq 0 \]\]/,$d' "$SCRIPT_DIR/deploy-agent/web/deploy-nginx-policy.sh")
task_port=$((20000 + RANDOM % 20000)); task_upstream_port=$((task_port + 1))
for port in "$task_port" "$task_upstream_port"; do
  if ss -ltn | grep -qE ":${port}[[:space:]]"; then echo 'Random test port is busy; retry test' >&2; exit 1; fi
done
chmod 755 "$task_tmp"; mkdir -p "$task_tmp/conf.d"
REQUEST_ID=1 SERVER_NAME=demo-app.localhost LISTEN_PORT=$task_port UPSTREAM_HOST=127.0.0.1 UPSTREAM_PORT=$task_upstream_port TLS=false
render_config "$task_tmp/rendered-first.conf" '' ''
render_config "$task_tmp/rendered-second.conf" '' ''
cmp "$task_tmp/rendered-first.conf" "$task_tmp/rendered-second.conf"
sed "s/listen ${task_port};/listen 127.0.0.1:${task_port};/" "$task_tmp/rendered-first.conf" > "$task_tmp/conf.d/deploy-demo-app.conf"
cat > "$task_tmp/nginx.conf" <<EOF
pid $task_tmp/nginx.pid;
error_log $task_tmp/error.log;
events {}
http {
  access_log off;
  client_body_temp_path $task_tmp/client_temp;
  proxy_temp_path $task_tmp/proxy_temp;
  include $task_tmp/conf.d/*.conf;
  server {
    listen 127.0.0.1:$task_upstream_port;
    location = /headers {
      return 200 'host=\$http_host|xfh=\$http_x_forwarded_host|xff=\$http_x_forwarded_for|proto=\$http_x_forwarded_proto|forwarded=\$http_forwarded|xfport=\$http_x_forwarded_port|xfprefix=\$http_x_forwarded_prefix|xfssl=\$http_x_forwarded_ssl';
    }
    location = /login { return 302 http://\$http_host/after-login; }
  }
}
EOF
nginx -p "$task_tmp/" -c "$task_tmp/nginx.conf" -t
nginx -p "$task_tmp/" -c "$task_tmp/nginx.conf"; task_started=1
fetch() { curl --noproxy '*' --silent --show-error --max-time 3 "$@"; }
[[ $(fetch -H 'Host: demo-app.localhost:18088' "http://127.0.0.1:$task_port/headers") == 'host=demo-app.localhost:18088|xfh=demo-app.localhost:18088|xff=127.0.0.1|proto=http|forwarded=|xfport=|xfprefix=|xfssl=' ]]
[[ $(fetch -H 'Host: demo-app.localhost' "http://127.0.0.1:$task_port/headers") == 'host=demo-app.localhost|xfh=demo-app.localhost|xff=127.0.0.1|proto=http|forwarded=|xfport=|xfprefix=|xfssl=' ]]
[[ $(fetch --http1.0 -H 'Host:' "http://127.0.0.1:$task_port/headers") == 'host=demo-app.localhost|xfh=demo-app.localhost|xff=127.0.0.1|proto=http|forwarded=|xfport=|xfprefix=|xfssl=' ]]
[[ $(fetch -H 'Host: demo-app.localhost:18088' -H 'X-Forwarded-For: 10.0.0.1' -H 'X-Forwarded-Host: evil.test:4444' -H 'X-Forwarded-Proto: https' -H 'Forwarded: for=10.0.0.1;host=evil.test;proto=https' -H 'X-Forwarded-Port: 4444' -H 'X-Forwarded-Prefix: /evil-prefix' -H 'X-Forwarded-Ssl: on' "http://127.0.0.1:$task_port/headers") == 'host=demo-app.localhost:18088|xfh=demo-app.localhost:18088|xff=127.0.0.1|proto=http|forwarded=|xfport=|xfprefix=|xfssl=' ]]
fetch -D "$task_tmp/login.headers" -o /dev/null -H 'Host: demo-app.localhost:18088' "http://127.0.0.1:$task_port/login"
grep -qE '^HTTP/1.1 302 ' "$task_tmp/login.headers"
grep -Fq 'Location: http://demo-app.localhost:18088/after-login' "$task_tmp/login.headers"
# A backup restores literal prior bytes, regardless of which renderer is now installed.
cp "$task_tmp/conf.d/deploy-demo-app.conf" "$task_tmp/approved-config.conf"
sed 's/set \$deploy_request_host \$http_host;/set $deploy_request_host $host;/' "$task_tmp/approved-config.conf" > "$task_tmp/conf.d/deploy-demo-app.conf"
nginx -p "$task_tmp/" -c "$task_tmp/nginx.conf" -t
nginx -p "$task_tmp/" -c "$task_tmp/nginx.conf" -s reload
cp "$task_tmp/approved-config.conf" "$task_tmp/conf.d/deploy-demo-app.conf"
nginx -p "$task_tmp/" -c "$task_tmp/nginx.conf" -t
nginx -p "$task_tmp/" -c "$task_tmp/nginx.conf" -s reload
cmp "$task_tmp/approved-config.conf" "$task_tmp/conf.d/deploy-demo-app.conf"
echo 'nginx-host-port-regression: PASS (port and no-port Host, empty-Host fallback, spoofed forwarding headers, absolute login redirect, deterministic renderer, literal config restore)'
