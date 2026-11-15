#!/usr/bin/env bash
# Actual policy renderer + isolated nginx HTTP; never touches installed configs/services.
set -euo pipefail
test_source="${1:?installer source root required}"
test_tmp="$(mktemp -d)"; test_started=0
cleanup(){ if ((test_started)); then nginx -p "$test_tmp/" -c "$test_tmp/nginx.conf" -s quit 2>/dev/null || true; fi; rm -rf "$test_tmp"; }
trap cleanup EXIT
source <(sed '/^\[\[ "\$EUID" -eq 0 \]\]/,$d' "$test_source/deploy-agent/web/deploy-nginx-policy.sh")
REQUEST_ID=41 POLICY_NAME=react-api CERTIFICATE_NAME=react-api SERVER_NAME=react-api.local LISTEN_PORT=18080
POLICY_TYPE=STATIC_SPA WEB_ROOT=/var/www/react-api TLS=false
API_PROXY=true API_PREFIX=/api/ API_UPSTREAM_HOST=127.0.0.1 API_UPSTREAM_PORT=18081
validate_apply
for prefix in / /api '/api/../' '/api/;return 200;' '/api/$uri/' '/api//'; do
  if (API_PREFIX="$prefix"; validate_apply) >/dev/null 2>&1; then echo 'unsafe prefix accepted' >&2; exit 1; fi
done
if (POLICY_TYPE=REVERSE_PROXY; validate_apply) >/dev/null 2>&1; then echo 'reverse API accepted' >&2; exit 1; fi
render_config "$test_tmp/rendered.conf" '' ''
mkdir -p "$test_tmp/site/current" "$test_tmp/conf.d"
chmod 755 "$test_tmp" "$test_tmp/site" "$test_tmp/site/current"
printf 'react-static-index' > "$test_tmp/site/current/index.html"
printf 'static-css' > "$test_tmp/site/current/app.css"
sed -e 's/listen 18080;/listen 127.0.0.1:18080;/' -e "s|/var/www/react-api/current|$test_tmp/site/current|" "$test_tmp/rendered.conf" > "$test_tmp/conf.d/policy.conf"
cat > "$test_tmp/nginx.conf" <<EOF
pid $test_tmp/nginx.pid;
error_log $test_tmp/error.log;
events {}
http {
  access_log off;
  client_body_temp_path $test_tmp/client_temp;
  proxy_temp_path $test_tmp/proxy_temp;
  fastcgi_temp_path $test_tmp/fastcgi_temp;
  uwsgi_temp_path $test_tmp/uwsgi_temp;
  scgi_temp_path $test_tmp/scgi_temp;
  include $test_tmp/conf.d/*.conf;
  server {
    listen 127.0.0.1:18081;
    location / { return 200 'api-uri=\$request_uri|host=\$http_host|xff=\$http_x_forwarded_for|proto=\$http_x_forwarded_proto|forwarded=\$http_forwarded'; }
  }
}
EOF
nginx -p "$test_tmp/" -c "$test_tmp/nginx.conf" -t
nginx -p "$test_tmp/" -c "$test_tmp/nginx.conf"; test_started=1
fetch(){ curl --noproxy '*' --silent --show-error --max-time 3 "$@"; }
[[ $(fetch http://127.0.0.1:18080/) == react-static-index ]]
[[ $(fetch http://127.0.0.1:18080/deep/react-route) == react-static-index ]]
[[ $(fetch http://127.0.0.1:18080/app.css) == static-css ]]
[[ $(fetch -H 'Host: react-api.local:18080' -H 'X-Forwarded-For: 10.0.0.1' -H 'Forwarded: proto=https' http://127.0.0.1:18080/api/value?q=kept) == 'api-uri=/api/value?q=kept|host=react-api.local:18080|xff=127.0.0.1|proto=http|forwarded=' ]]
# Config backup includes both static and API locations; literal restoration is deterministic.
cp "$test_tmp/conf.d/policy.conf" "$test_tmp/backup.conf"
API_PROXY=false API_PREFIX='' API_UPSTREAM_HOST='' API_UPSTREAM_PORT=1 POLICY_TYPE=STATIC_FILES
validate_apply; render_config "$test_tmp/static.conf" '' ''
! grep -q 'proxy_pass' "$test_tmp/static.conf"
sed -e 's/listen 18080;/listen 127.0.0.1:18080;/' -e "s|/var/www/react-api/current|$test_tmp/site/current|" "$test_tmp/static.conf" > "$test_tmp/conf.d/policy.conf"
nginx -p "$test_tmp/" -c "$test_tmp/nginx.conf" -t
nginx -p "$test_tmp/" -c "$test_tmp/nginx.conf" -s reload
sleep 0.2
[[ $(fetch -o /dev/null -w '%{http_code}' http://127.0.0.1:18080/api/value) == 404 ]]
cp "$test_tmp/backup.conf" "$test_tmp/conf.d/policy.conf"
nginx -p "$test_tmp/" -c "$test_tmp/nginx.conf" -t
nginx -p "$test_tmp/" -c "$test_tmp/nginx.conf" -s reload
sleep 0.2
cmp "$test_tmp/backup.conf" "$test_tmp/conf.d/policy.conf"
[[ $(fetch http://127.0.0.1:18080/api/value) == api-uri=/api/value* ]]
# ACL must apply equally to the API location, not just the static branch.
ALLOWED_CIDRS=192.0.2.1
render_config "$test_tmp/deny.conf" '' ''
sed -e 's/listen 18080;/listen 127.0.0.1:18080;/' -e "s|/var/www/react-api/current|$test_tmp/site/current|" "$test_tmp/deny.conf" > "$test_tmp/conf.d/policy.conf"
nginx -p "$test_tmp/" -c "$test_tmp/nginx.conf" -t
nginx -p "$test_tmp/" -c "$test_tmp/nginx.conf" -s reload
sleep 0.2
[[ $(fetch -o /dev/null -w '%{http_code}' http://127.0.0.1:18080/api/value) == 403 ]]
[[ $(fetch -o /dev/null -w '%{http_code}' http://127.0.0.1:18080/) == 403 ]]
echo 'nginx-static-api-regression: PASS (real HTTP static/deep/API URI+query/header trust, off404, literal restore, both-location ACL, unsafe prefixes)'
