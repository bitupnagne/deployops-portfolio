#!/bin/bash
# Isolated renderer/real HTTP only; no installed config/service modification.
set -euo pipefail
source_root="${1:?source installer root}" old="${2:?old agent path}"
tmp="$(mktemp -d)"; started=0; b1=''; b2=''
cleanup(){ ((started)) && nginx -p "$tmp/" -c "$tmp/nginx.conf" -s quit >/dev/null 2>&1 || true; for pid in "$b1" "$b2"; do [[ -z "$pid" ]] || kill "$pid" 2>/dev/null || true; done; }
trap cleanup EXIT
functions(){ sed '/^\[\[ "\$EUID" -eq 0 \]\]/,$d' "$1"; }
source <(functions "$source_root/deploy-agent/web/deploy-nginx-policy.sh")
REQUEST_ID=88 POLICY_NAME=ha CERTIFICATE_NAME=ha SERVER_NAME=localhost LISTEN_PORT=18480
UPSTREAM_HOST=127.0.0.1 UPSTREAM_PORT=18481 TLS=false
validate_apply
render_config "$tmp/new-single" /fixture/public.pem /fixture/private.pem
(source <(functions "$old"); REQUEST_ID=88 POLICY_NAME=ha CERTIFICATE_NAME=ha SERVER_NAME=localhost LISTEN_PORT=18480 UPSTREAM_HOST=127.0.0.1 UPSTREAM_PORT=18481 TLS=false; render_config "$tmp/old-single" /fixture/public.pem /fixture/private.pem)
cmp "$tmp/new-single" "$tmp/old-single"
TLS=true;render_config "$tmp/new-tls" /fixture/public.pem /fixture/private.pem
(source <(functions "$old"); REQUEST_ID=88 POLICY_NAME=ha CERTIFICATE_NAME=ha SERVER_NAME=localhost LISTEN_PORT=18480 UPSTREAM_HOST=127.0.0.1 UPSTREAM_PORT=18481 TLS=true; render_config "$tmp/old-tls" /fixture/public.pem /fixture/private.pem)
cmp "$tmp/new-tls" "$tmp/old-tls"; TLS=false
for bad in 'host;return 200' 'host name' '$uri'; do if (SECONDARY_HOST="$bad" SECONDARY_PORT=18482;validate_apply) >/dev/null 2>&1;then echo unsafe-secondary-accepted >&2;exit 1;fi;done
if (SECONDARY_HOST=127.0.0.1 SECONDARY_PORT=18481;validate_apply) >/dev/null 2>&1;then exit 1;fi
if (SECONDARY_HOST=127.0.0.1 SECONDARY_PORT='';validate_apply) >/dev/null 2>&1;then exit 1;fi
if (CONNECT_TIMEOUT=31;validate_apply) >/dev/null 2>&1;then exit 1;fi
POLICY_TYPE="${3:-REVERSE_PROXY}"
if [[ "$POLICY_TYPE" != "REVERSE_PROXY" ]]; then
  [[ "$POLICY_TYPE" == "STATIC_SPA" || "$POLICY_TYPE" == "STATIC_FILES" ]] || exit 1
  API_PROXY=true API_PREFIX=/api/ API_UPSTREAM_HOST=127.0.0.1 API_UPSTREAM_PORT=18481
  WEB_ROOT=/var/www/react UPSTREAM_HOST=static UPSTREAM_PORT=1
  mkdir -p "$tmp/site/current"; chmod 755 "$tmp" "$tmp/site" "$tmp/site/current"
  printf static-index > "$tmp/site/current/index.html"; printf static-css > "$tmp/site/current/app.css"
fi
SECONDARY_HOST=127.0.0.1 SECONDARY_PORT=18482;validate_apply;render_config "$tmp/policy.conf" '' ''
if [[ "$POLICY_TYPE" != "REVERSE_PROXY" ]]; then
  sed -i "s|/var/www/react/current|$tmp/site/current|" "$tmp/policy.conf"
fi
! grep -q non_idempotent "$tmp/policy.conf"
grep -q 'proxy_next_upstream_tries 2;' "$tmp/policy.conf"
cat > "$tmp/backend.py" <<'PY'
import http.server,sys
class Handler(http.server.BaseHTTPRequestHandler):
 def do_GET(self):
  body=(sys.argv[2]+'|'+self.path+'|'+self.headers.get('Host','')+'|'+self.headers.get('X-Forwarded-For','')+'|'+self.headers.get('X-Forwarded-Proto','')+'|'+self.headers.get('Forwarded','')).encode()
  self.send_response(200);self.end_headers();self.wfile.write(body)
 def log_message(self,*args):pass
http.server.HTTPServer(('127.0.0.1',int(sys.argv[1])),Handler).serve_forever()
PY
/usr/libexec/platform-python -I -B "$tmp/backend.py" 18481 A & b1=$!
/usr/libexec/platform-python -I -B "$tmp/backend.py" 18482 B & b2=$!
cat > "$tmp/nginx.conf" <<EOF
pid $tmp/nginx.pid;
error_log $tmp/error.log;
events {}
http {
 access_log off;
 client_body_temp_path $tmp/client_temp;
 proxy_temp_path $tmp/proxy_temp;
 fastcgi_temp_path $tmp/fastcgi_temp;
 uwsgi_temp_path $tmp/uwsgi_temp;
 scgi_temp_path $tmp/scgi_temp;
 include $tmp/policy.conf;
}
EOF
sleep .3;nginx -p "$tmp/" -c "$tmp/nginx.conf" -t;nginx -p "$tmp/" -c "$tmp/nginx.conf";started=1
fetch(){ curl --noproxy '*' --silent --show-error --max-time 8 -H 'Host: localhost:38443' -H 'X-Forwarded-For: 10.0.0.1' -H 'Forwarded: proto=https' http://127.0.0.1:18480/${API_PROXY_PREFIX:-}value?q=kept; }
API_PROXY_PREFIX=''; [[ "$API_PROXY" != true ]] || API_PROXY_PREFIX='api/'
if [[ "$POLICY_TYPE" != "REVERSE_PROXY" ]]; then
 [[ $(curl --noproxy '*' --silent --max-time 8 http://127.0.0.1:18480/) == static-index ]]
 [[ $(curl --noproxy '*' --silent --max-time 8 http://127.0.0.1:18480/app.css) == static-css ]]
 if [[ "$POLICY_TYPE" == STATIC_SPA ]]; then [[ $(curl --noproxy '*' --silent --max-time 8 http://127.0.0.1:18480/deep/route) == static-index ]]; fi
fi
rows="$(for n in 1 2 3 4;do fetch;echo;done)"
grep -q "^A|/${API_PROXY_PREFIX}value?q=kept|localhost:38443|127.0.0.1|http|$" <<< "$rows"
grep -q "^B|/${API_PROXY_PREFIX}value?q=kept|localhost:38443|127.0.0.1|http|$" <<< "$rows"
cp "$tmp/policy.conf" "$tmp/backup.conf"
kill "$b1";wait "$b1" 2>/dev/null || true;b1=''
for n in 1 2 3;do [[ "$(fetch)" == B\|* ]];done
/usr/libexec/platform-python -I -B "$tmp/backend.py" 18481 A & b1=$!
kill "$b2";wait "$b2" 2>/dev/null || true;b2=''
# Reload a literal backup resets passive state; then A serves with B unavailable.
cp "$tmp/backup.conf" "$tmp/policy.conf";nginx -p "$tmp/" -c "$tmp/nginx.conf" -s reload;sleep .3
for n in 1 2 3;do [[ "$(fetch)" == A\|* ]];done
kill "$b1";wait "$b1" 2>/dev/null || true;b1=''
[[ $(curl --noproxy '*' --silent --max-time 8 -o /dev/null -w '%{http_code}' -H 'Host: localhost' http://127.0.0.1:18480/${API_PROXY_PREFIX}) == 502 ]]
cmp "$tmp/backup.conf" "$tmp/policy.conf"
echo "POLICY_TYPE=$POLICY_TYPE"
echo TWO_REAL_BACKENDS_ROUNDROBIN_EACH_FAILURE_BOTH_DOWN_HOST_TRUST_LITERAL_RESTORE_SINGLE_HTTP_TLS_BYTES_PASS
