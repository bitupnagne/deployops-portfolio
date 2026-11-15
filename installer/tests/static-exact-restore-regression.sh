#!/usr/bin/env bash
# Local filesystem fixture: no Nginx configuration or live service is changed.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
tmp="$(mktemp -d /tmp/deploy-static-exact.XXXXXXXX)"
trap 'rm -rf -- "$tmp"' EXIT
source <(sed '/^main "\$@"$/d' "$repo/installer/deploy-agent/web/deploy-web-static.sh")
WEB_ROOT="$tmp/site"
mkdir -p "$WEB_ROOT/releases"/{old,new,third} "$tmp/other/releases/foreign"
chmod 755 "$tmp" "$WEB_ROOT" "$WEB_ROOT/releases" "$WEB_ROOT/releases/"*
for release in old new third; do printf '%s\n' "$release" > "$WEB_ROOT/releases/$release/index.html"; done
HEALTHCHECK_SCRIPT="$tmp/health"
cat > "$HEALTHCHECK_SCRIPT" <<'EOF'
#!/bin/bash
[[ ! -f "${0%/*}/health-failure" ]]
EOF
chmod 755 "$HEALTHCHECK_SCRIPT"
HEALTH_PORT=80 HEALTH_PATH=/ RELOAD_NGINX=1
nginx(){ [[ ! -f "$tmp/nginx-failure" ]]; }
systemctl(){
  [[ "$1" == reload && "$2" == nginx ]] || return 1
  if [[ -f "$tmp/reload-failure" ]]; then rm "$tmp/reload-failure"; return 1; fi
}
warn_if_web_root_not_served(){ return 0; }
active_health_host_for_web_root(){ echo fixture.example.invalid; }
seed(){
  rm -f "$WEB_ROOT/current" "$tmp/health-failure" "$tmp/nginx-failure" "$tmp/reload-failure"
  ln -s "$WEB_ROOT/releases/new" "$WEB_ROOT/current"
  EXPECTED_RELEASE="$WEB_ROOT/releases/new"
  RESTORE_RELEASE="$WEB_ROOT/releases/old"
}
seed; run_restore_exact > "$tmp/success.log"
[[ "$(readlink -e "$WEB_ROOT/current")" == "$WEB_ROOT/releases/old" ]]
[[ -d "$WEB_ROOT/releases/new" ]]

seed; ln -sfn "$WEB_ROOT/releases/third" "$WEB_ROOT/current"
if run_restore_exact > /dev/null 2>&1; then echo 'changed current accepted'; exit 1; fi
[[ "$(readlink -e "$WEB_ROOT/current")" == "$WEB_ROOT/releases/third" ]]

seed; RESTORE_RELEASE="$tmp/other/releases/foreign"
if run_restore_exact > /dev/null 2>&1; then echo 'foreign root restored'; exit 1; fi
[[ "$(readlink -e "$WEB_ROOT/current")" == "$WEB_ROOT/releases/new" ]]

seed; ln -s "$tmp/other/releases/foreign" "$WEB_ROOT/releases/linked"
RESTORE_RELEASE="$WEB_ROOT/releases/linked"
if run_restore_exact > /dev/null 2>&1; then echo 'symlink escape accepted'; exit 1; fi
rm "$WEB_ROOT/releases/linked"

seed; chmod 777 "$WEB_ROOT/releases/old"
if run_restore_exact > /dev/null 2>&1; then echo 'writable release accepted'; exit 1; fi
chmod 755 "$WEB_ROOT/releases/old"

seed; touch "$tmp/reload-failure"
if run_restore_exact > /dev/null 2>&1; then echo 'reload failure accepted'; exit 1; fi
[[ "$(readlink -e "$WEB_ROOT/current")" == "$WEB_ROOT/releases/new" ]]

seed; touch "$tmp/health-failure"
if run_restore_exact > /dev/null 2>&1; then echo 'health failure accepted'; exit 1; fi
[[ "$(readlink -e "$WEB_ROOT/current")" == "$WEB_ROOT/releases/new" ]]

seed; RESTORE_RELEASE=ABSENT
run_restore_exact > "$tmp/absent.log"
[[ ! -e "$WEB_ROOT/current" && ! -L "$WEB_ROOT/current" ]]
[[ -d "$WEB_ROOT/releases/new" ]]

seed; KEEP_RELEASES=1
touch -d '2020-01-01' "$WEB_ROOT/releases/old"
touch -d '2021-01-01' "$WEB_ROOT/releases/third"
touch -d '2022-01-01' "$WEB_ROOT/releases/new"
prune_releases "$WEB_ROOT/releases" "$WEB_ROOT/releases/old"
[[ -d "$WEB_ROOT/releases/new" && -d "$WEB_ROOT/releases/old" && ! -e "$WEB_ROOT/releases/third" ]]
echo 'PASS: exact CAS restore, foreign/symlink/permission rejection, reload/health compensation, first-install absence and retained previous release'
