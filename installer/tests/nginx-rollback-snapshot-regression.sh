#!/bin/bash
# Uses only disposable container tmpfs fixed paths; no live role or key read.
set -Eeuo pipefail
source_root="${1:?installer root}"
tmp="$(mktemp -d)"; started=0; b1=''; b2=''
cleanup(){ ((started)) && command nginx -p "$tmp/" -c "$tmp/nginx.conf" -s quit >/dev/null 2>&1 || true; for pid in "$b1" "$b2"; do [[ -z "$pid" ]] || kill "$pid" 2>/dev/null || true; done; }
trap cleanup EXIT
source <(sed '/^\[\[ "\$EUID" -eq 0 \]\]/,$d' "$source_root/deploy-agent/web/deploy-nginx-policy.sh")
mkdir -p /etc/nginx/conf.d /etc/nginx/tls "$BACKUP_ROOT"
chmod 700 "$BACKUP_ROOT"
# Public descriptor hashes private fixture bytes, never emits them.
mkdir -m 700 "$tmp/tls" "$tmp/tls/current"
printf 'public-fixture' > "$tmp/tls/current/fullchain.pem";chmod 644 "$tmp/tls/current/fullchain.pem"
printf 'private-fixture-not-a-key' > "$tmp/tls/current/privkey.pem";chmod 600 "$tmp/tls/current/privkey.pem"
snapshot_tree "$tmp/absent.conf" "$tmp/tls";tls_hash="$SNAPSHOT_HASH";emit_snapshot TEST_ > "$tmp/tls.out"
! grep -q 'private-fixture' "$tmp/tls.out"
printf 'different-private-fixture' > "$tmp/tls/current/privkey.pem"
snapshot_tree "$tmp/absent.conf" "$tmp/tls";[[ "$SNAPSHOT_HASH" != "$tls_hash" ]]
chmod 644 "$tmp/tls/current/privkey.pem";if (snapshot_tree "$tmp/absent.conf" "$tmp/tls") >"$tmp/tls-mode.out" 2>&1;then exit 1;fi
chmod 600 "$tmp/tls/current/privkey.pem";snapshot_tree "$tmp/absent.conf" "$tmp/tls";mode700_hash="$SNAPSHOT_HASH"
chmod 755 "$tmp/tls";snapshot_tree "$tmp/absent.conf" "$tmp/tls";[[ "$SNAPSHOT_HASH" != "$mode700_hash" ]]
chmod 777 "$tmp/tls";if (snapshot_tree "$tmp/absent.conf" "$tmp/tls") >"$tmp/tls-parent.out" 2>&1;then exit 1;fi
cat > "$tmp/backend.py" <<'PY'
import http.server,sys
class Handler(http.server.BaseHTTPRequestHandler):
 def do_GET(self):
  self.send_response(200); self.end_headers(); self.wfile.write(sys.argv[2].encode())
 def log_message(self,*args): pass
http.server.HTTPServer(('127.0.0.1',int(sys.argv[1])),Handler).serve_forever()
PY
/usr/libexec/platform-python -I -B "$tmp/backend.py" 18481 A & b1=$!
/usr/libexec/platform-python -I -B "$tmp/backend.py" 18482 B & b2=$!
cat > "$tmp/nginx.conf" <<EOF
user root;
pid $tmp/nginx.pid;
error_log $tmp/error.log;
events {}
http { access_log off; client_body_temp_path $tmp/client; proxy_temp_path $tmp/proxy;
 fastcgi_temp_path $tmp/fastcgi; uwsgi_temp_path $tmp/uwsgi; scgi_temp_path $tmp/scgi;
 include /etc/nginx/conf.d/*.conf;
}
EOF
FAIL_SYNTAX=false FAIL_RELOAD=false FAIL_POST=false
nginx(){ if [[ "$FAIL_SYNTAX" == true && "$*" == *'-t'* ]]; then return 1; fi; command nginx -p "$tmp/" -c "$tmp/nginx.conf" "$@"; }
systemctl(){ [[ "$1 $2" == 'reload nginx' ]] || return 1; [[ "$FAIL_RELOAD" == false ]] || return 1; nginx -s reload; sleep .15; if [[ "$FAIL_POST" == true ]]; then FAIL_POST=false;echo '# post-copy drift' >> /etc/nginx/conf.d/deploy-ha.conf;fi; }
nginx;started=1
REQUEST_ID=101 POLICY_NAME=ha CERTIFICATE_NAME=certificate SERVER_NAME=localhost LISTEN_PORT=18480 UPSTREAM_HOST=127.0.0.1 UPSTREAM_PORT=18481 TLS=false
apply_policy > "$tmp/a.out"; empty_backup="$BACKUP_DIR"; empty_hash="$(sed -n 's/^BACKUP_SNAPSHOT_SHA=//p' "$tmp/a.out")"
cp /etc/nginx/conf.d/deploy-ha.conf "$tmp/expected-a"
fetch(){ curl --noproxy '*' --silent --show-error --max-time 5 -H 'Host: localhost' http://127.0.0.1:18480/; }
[[ "$(fetch)" == A ]]
REQUEST_ID=102 UPSTREAM_PORT=18482
apply_policy > "$tmp/b.out"; target_backup="$BACKUP_DIR"; target_hash="$(sed -n 's/^BACKUP_SNAPSHOT_SHA=//p' "$tmp/b.out")"; current_hash="$SNAPSHOT_HASH"
cp /etc/nginx/conf.d/deploy-ha.conf "$tmp/expected-b"; [[ "$(fetch)" == B ]]
EXPECTED_CURRENT_REQUEST_ID=102 EXPECTED_CURRENT_SNAPSHOT="$current_hash" BACKUP_DIR="$target_backup"
describe_backup > "$tmp/describe.out"; grep -qx "TARGET_SNAPSHOT_SHA=$target_hash" "$tmp/describe.out";grep -qx 'TARGET_REQUEST_ID=101' "$tmp/describe.out"
EXPECTED_SNAPSHOT="$target_hash"
cp "$target_backup/config.conf" "$tmp/target-original"
echo '# tampered body' >> "$target_backup/config.conf"
if (rollback_policy) >"$tmp/tamper.out" 2>&1;then exit 1;fi
cmp "$tmp/expected-b" /etc/nginx/conf.d/deploy-ha.conf
cp "$tmp/target-original" "$target_backup/config.conf"
touch "$target_backup/.unexpected";if (describe_backup) >"$tmp/extra.out" 2>&1;then exit 1;fi;rm "$target_backup/.unexpected"
mv "$target_backup/config.conf" "$target_backup/config.saved";ln -s "$tmp/target-original" "$target_backup/config.conf"
if (describe_backup) >"$tmp/symlink.out" 2>&1;then exit 1;fi
rm "$target_backup/config.conf";mv "$target_backup/config.saved" "$target_backup/config.conf"
if (BACKUP_DIR=/tmp/outside;describe_backup) >"$tmp/path.out" 2>&1;then exit 1;fi
if (EXPECTED_CURRENT_REQUEST_ID=999;rollback_policy) >"$tmp/generation.out" 2>&1;then exit 1;fi
for fault in syntax reload post; do
  if [[ "$fault" == syntax ]];then FAIL_SYNTAX=true;elif [[ "$fault" == reload ]];then FAIL_RELOAD=true;else FAIL_POST=true;fi
  if (rollback_policy) >"$tmp/$fault.out" 2>&1;then exit 1;fi
  FAIL_SYNTAX=false FAIL_RELOAD=false FAIL_POST=false
  cmp "$tmp/expected-b" /etc/nginx/conf.d/deploy-ha.conf
  snapshot_tree /etc/nginx/conf.d/deploy-ha.conf /etc/nginx/tls/ha;[[ "$SNAPSHOT_HASH" == "$current_hash" ]]
done
rollback_policy > "$tmp/rollback.out"
cmp "$tmp/expected-a" /etc/nginx/conf.d/deploy-ha.conf;[[ "$(fetch)" == A ]]
grep -qx "CURRENT_SNAPSHOT_SHA=$target_hash" "$tmp/rollback.out";grep -qx 'CURRENT_REQUEST_ID=101' "$tmp/rollback.out"
# A rollback's DB generation is not its restored file header: reverse via the exact safety backup.
BACKUP_DIR="$(sed -n 's/^BACKUP_PATH=//p' "$tmp/rollback.out")" EXPECTED_CURRENT_REQUEST_ID=101 EXPECTED_CURRENT_SNAPSHOT="$target_hash" EXPECTED_SNAPSHOT="$current_hash"
rollback_policy > "$tmp/reverse.out";cmp "$tmp/expected-b" /etc/nginx/conf.d/deploy-ha.conf;[[ "$(fetch)" == B ]]
BACKUP_DIR="$empty_backup" EXPECTED_CURRENT_REQUEST_ID=102 EXPECTED_CURRENT_SNAPSHOT="$current_hash" EXPECTED_SNAPSHOT="$empty_hash"
rollback_policy > "$tmp/removal.out";[[ ! -e /etc/nginx/conf.d/deploy-ha.conf ]];grep -qx 'CURRENT_CONFIG_PRESENT=false' "$tmp/removal.out";grep -qx 'CURRENT_REQUEST_ID=absent' "$tmp/removal.out"
REQUEST_ID=103 UPSTREAM_PORT=18481;apply_policy > "$tmp/after-removal.out";grep -qx 'CURRENT_REQUEST_ID=103' "$tmp/after-removal.out";[[ "$(fetch)" == A ]]
echo 'ACTUAL_NGINX_A_B_A_REVERSE_REMOVAL_TAMPER_PATH_SYMLINK_GENERATION_SYNTAX_RELOAD_SAFETY_DIGEST_PASS'
