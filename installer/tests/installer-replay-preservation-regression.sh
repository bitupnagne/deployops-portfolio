#!/usr/bin/env bash
set -e -o pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(mktemp -d /tmp/role-replay-fixture.XXXXXX)"
trap '[[ "$ROOT" == /tmp/role-replay-fixture.* ]] && rm -rf -- "$ROOT"' EXIT
SCRIPT_DIR="$(cd "$HERE/.." && pwd)"
JQ="${REPLAY_FIXTURE_JQ:-$(command -v jq || true)}"
[[ -n "$JQ" && -x "$JQ" ]] || { echo "A real jq executable is required" >&2; exit 1; }
COUNT=0
pass(){ COUNT=$((COUNT+1)); printf 'PASS %s\n' "$1"; }
assert(){ [[ "$1" == "$2" ]] || { echo "ASSERTION FAILED: $3" >&2; exit 1; }; }
info(){ :; }; err(){ :; }; success(){ :; }; warn(){ :; }; section(){ :; }
. "$SCRIPT_DIR/components/nexus.sh"
docker_jq_binary(){ echo "$JQ"; }
docker(){ return 1; }
sleep(){ :; }
ask_secret(){ echo fixture-only-not-real-credential; }
ask(){ printf '%s' "${3:-}"; }
ask_confirm(){ printf '%s=%s\n' "$1" "$3" >> "$CASE/confirm.log"; [[ "$3" == y ]]; }
nexus_setup_maven_if_selected(){ return 0; }
nexus_runtime_contract(){ return 0; }
curl(){
 local method=GET output='' data='' url='' value
 while (( $# )); do
  case "$1" in -X) method="$2";shift 2;; -o) output="$2";shift 2;; -d) data="$2";shift 2;; -u|-H|--max-time) shift 2;; -*) shift;; *) url="$1";shift;; esac
 done
 case "$url" in
  */service/rest/v1/status) value='';;
  */service/rest/v1/system/eula) value='{"accepted":true}';;
  */service/rest/v1/security/realms/active) value='["NexusAuthenticatingRealm","DockerToken"]';;
  */service/rest/v1/repositories)
   if [[ "$MODE" == fresh ]]; then value='[]';else value='[{"name":"docker-hosted"}]';fi;;
  */service/rest/v1/repositories/docker/hosted)
   [[ "$method" == POST ]] || return 98
   printf '%s\n' "$method" >> "$CASE/repository-mutations.log"
   printf '%s' "$data" | "$JQ" '. + {format:"docker",type:"hosted"}' > "$CASE/repository.json";value='';;
  */service/rest/v1/repositories/docker/hosted/docker-hosted)
   if [[ "$method" == PUT ]];then printf '%s\n' "$method" >> "$CASE/repository-mutations.log";printf '%s' "$data" | "$JQ" '. + {format:"docker",type:"hosted"}' > "$CASE/repository.json";fi
   value="$(cat "$CASE/repository.json")";;
  *) return 98;;
 esac
 if [[ -n "$output" ]];then printf '%s' "$value" > "$output";else printf '%s' "$value";fi
}
reset_nexus(){
 MODE="$1";CASE="$(mktemp -d "$ROOT/nexus.XXXXXX")";: > "$CASE/repository-mutations.log";: > "$CASE/confirm.log"
 NEXUS_ADMIN_PASSWORD=fixture-only-not-real-credential
 nexus_repository_payload "$JQ" docker-hosted 5000 | "$JQ" '. + {format:"docker",type:"hosted"}' > "$CASE/repository.json"
}
reset_nexus existing
nexus_auto_setup 8081 5000
assert "$(wc -l < "$CASE/repository-mutations.log")" 0 'matching repository must not mutate'
pass 'Nexus matching immutable existing repository retained without repository PUT/POST'
reset_nexus fresh
nexus_auto_setup 8081 5000
assert "$(cat "$CASE/repository-mutations.log")" POST 'fresh repository creation'
"$JQ" -e '.storage.writePolicy == "ALLOW_ONCE" and .storage.latestPolicy == false and .storage.strictContentTypeValidation == true' "$CASE/repository.json" >/dev/null
pass 'Nexus fresh creation immutable strict latest-disabled default'
for mode in mutable wrong-name wrong-port latest-override wrong-port-type;do
 reset_nexus "$mode"
 case "$mode" in
  mutable) filter='.storage.writePolicy="ALLOW"';;
  wrong-name) filter='.name="other-repository"';;
  wrong-port) filter='.docker.httpPort=5001';;
  latest-override) filter='.storage.latestPolicy=true';;
  wrong-port-type) filter='.docker.httpPort="5000"';;
 esac
 "$JQ" "$filter" "$CASE/repository.json" > "$CASE/modified.json";mv "$CASE/modified.json" "$CASE/repository.json"
 before="$(sha256sum "$CASE/repository.json")"
 if nexus_auto_setup 8081 5000;then echo 'Invalid repository accepted' >&2;exit 1;fi
 assert "$(wc -l < "$CASE/repository-mutations.log")" 0 'drift must not silently repair'
 assert "$(sha256sum "$CASE/repository.json")" "$before" 'drift file retained'
 grep -qx 'NEXUS_REPAIR_DRIFT=n' "$CASE/confirm.log"
 pass "Nexus $mode refusal with no repository mutation/weakening"
done

. "$SCRIPT_DIR/components/nginx.sh"
OS_FAMILY=rhel
nginx(){
 printf '%s\n' "$*" >> "$CASE/nginx.log"
 if [[ "$1" == -t && ( "$VALID" != yes || ( "$POST_VALID" != yes && -s "$CASE/packages.log" ) ) ]];then return 1;fi
}
systemctl(){
 printf '%s\n' "$*" >> "$CASE/systemctl.log"
 case "$1" in
  is-active) [[ "$ACTIVE" == yes ]];;
  is-enabled|enable) return 0;;
  reload) [[ "$RELOAD" == yes ]];;
  start|restart) [[ "$START" == yes ]] || return 1;ACTIVE=yes;;
  *) return 98;;
 esac
}
bundle_dir(){ echo "$ROOT/packages"; }
bundle_has_packages(){ return 0; }
install_local_packages(){ printf 'packages\n' >> "$CASE/packages.log";return "$PACKAGE_RESULT"; }
pkg_is_installed(){ return 0; }
ask(){ case "$1" in NGINX_UPSTREAM_HOST) echo 203.0.113.11;;NGINX_MAX_BODY) echo 0;;*) printf '%s' "${3:-}";;esac; }
ask_confirm(){ [[ "$1" == NGINX_PROXY_DEPLOY ]]; }
reset_nginx(){
 CASE="$(mktemp -d "$ROOT/nginx.XXXXXX")";: > "$CASE/nginx.log";: > "$CASE/systemctl.log";: > "$CASE/packages.log"
 NGINX_CONF_RHEL="$CASE/officesystem.conf";NGINX_CONF_DEB="$CASE/officesystem.conf"
 VALID=yes;POST_VALID=yes;PACKAGE_RESULT=0;ACTIVE=yes;RELOAD=yes;START=yes;NGINX_REPLAY_LOCK_ROOT="$CASE/locks"
}
existing(){ printf 'server { listen 18080; location / { proxy_pass http://203.0.113.11:9090; } }\n# UI TLS/static routing remains intact\n' > "$NGINX_CONF_RHEL";chmod 0644 "$NGINX_CONF_RHEL"; }
for force in normal force;do
 reset_nginx;existing;before="$(sha256sum "$NGINX_CONF_RHEL")"
 if [[ "$force" == force ]];then nginx_install force;else nginx_install;fi
 assert "$(sha256sum "$NGINX_CONF_RHEL")" "$before" 'existing UI routing preserved'
 assert "$(stat -c %a "$NGINX_CONF_RHEL")" 644 'existing mode preserved'
 if [[ "$force" == force ]];then
  grep -qx 'restart nginx' "$CASE/systemctl.log"
  assert "$(wc -l < "$CASE/packages.log")" 1 'force updates packages while retaining UI configuration'
 else
  grep -qx 'reload nginx' "$CASE/systemctl.log"
  assert "$(wc -l < "$CASE/packages.log")" 0 'normal replay retains packages'
 fi
 pass "Nginx $force replay preserves bytes/mode and activates validated configuration"
done
for failure in package post-validation restart;do
 reset_nginx;existing;before="$(sha256sum "$NGINX_CONF_RHEL")"
 case "$failure" in package) PACKAGE_RESULT=41;;post-validation) POST_VALID=no;;restart) START=no;;esac
 if nginx_install force;then echo "Unexpected force success: $failure" >&2;exit 1;fi
 assert "$(sha256sum "$NGINX_CONF_RHEL")" "$before" 'force failure preserves current UI configuration'
 if [[ "$failure" != restart ]];then ! grep -q '^restart nginx$' "$CASE/systemctl.log";fi
 pass "Nginx force $failure failure propagates with current UI configuration retained"
done
reset_nginx;existing;VALID=no;before="$(sha256sum "$NGINX_CONF_RHEL")"
if nginx_install;then exit 1;fi
assert "$(wc -l < "$CASE/systemctl.log")" 0 'invalid existing config must not enable/reload'
assert "$(sha256sum "$NGINX_CONF_RHEL")" "$before" 'invalid config retained'
pass 'Nginx existing validation failure leaves config and service actions untouched'
reset_nginx;existing;chmod 0664 "$NGINX_CONF_RHEL"
if nginx_install;then exit 1;fi
assert "$(wc -l < "$CASE/nginx.log")" 0 'writable config must refuse before validation'
assert "$(wc -l < "$CASE/systemctl.log")" 0 'writable config must refuse before service actions'
pass 'Nginx group-writable existing file refused'
reset_nginx;existing;mv "$NGINX_CONF_RHEL" "$CASE/actual.conf";ln -s "$CASE/actual.conf" "$NGINX_CONF_RHEL"
if nginx_install;then exit 1;fi
assert "$(wc -l < "$CASE/systemctl.log")" 0 'symlink refused before service actions'
pass 'Nginx existing symlink refused without following replacement'
for failure in reload start;do
 reset_nginx;existing;before="$(sha256sum "$NGINX_CONF_RHEL")"
 printf 'stale-backup-must-not-restore\n' > "$NGINX_CONF_RHEL.bak.old"
 if [[ "$failure" == reload ]];then RELOAD=no;else ACTIVE=no;START=no;fi
 if nginx_install;then exit 1;fi
 assert "$(sha256sum "$NGINX_CONF_RHEL")" "$before" 'failed action never restores stale backup'
 assert "$(cat "$NGINX_CONF_RHEL.bak.old")" stale-backup-must-not-restore 'backup untouched'
 pass "Nginx $failure failure preserves current routes and stale backup"
done
reset_nginx;existing;ACTIVE=no
nginx_install;grep -qx 'start nginx' "$CASE/systemctl.log"
pass 'Nginx stopped valid existing config starts without regeneration'
reset_nginx
nginx_install
[[ -f "$NGINX_CONF_RHEL" ]] && grep -q 'proxy_pass http://203.0.113.11:9090' "$NGINX_CONF_RHEL"
assert "$(stat -c %a "$NGINX_CONF_RHEL")" 644 'fresh default mode'
grep -qx 'restart nginx' "$CASE/systemctl.log"
pass 'Nginx absent configuration takes real default generation path'
printf 'FUNCTIONAL_REPLAY_FIXTURE_PASS %s; isolated command mocks, not native runtime acceptance\n' "$COUNT"
