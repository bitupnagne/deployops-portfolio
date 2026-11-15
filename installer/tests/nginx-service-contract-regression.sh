#!/usr/bin/env bash
# Service failures are mocked; generated configs and logs stay in an own temp tree.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
task_tmp=$(mktemp -d)
trap 'rm -rf "$task_tmp"' EXIT
. "$SCRIPT_DIR/lib/ui.sh"
init_ui
. "$SCRIPT_DIR/components/nginx.sh"
saved_status=$(declare -f nginx_status)
eval "${saved_status/nginx_status/actual_nginx_status}"
nginx_status() { printf installed; }
OS_FAMILY=rhel
NGINX_UPSTREAM_HOST=127.0.0.1
NGINX_CUSTOM_SERVICES='fixture|8088|8080|127.0.0.1|no'
NGINX_STATIC_ROOT=''
ask() { printf '%s' "${!1:-${3:-}}"; }
ask_confirm() { return 1; }
pkg_is_installed() { return 0; }
nginx() { [[ "$*" != -t || "$failure" != syntax ]]; }
systemctl() {
  printf '%s\n' "$*" >> "$task_tmp/systemctl-calls"
  case "$*" in
    'enable nginx') [[ "$failure" != enable ]] ;;
    'restart nginx') [[ "$failure" != restart ]] ;;
    'is-active --quiet nginx') [[ "$failure" != active ]] ;;
    'is-enabled --quiet nginx')
      enabled_calls=$((enabled_calls+1))
      [[ "$failure" != enabled && ( "$failure" != enabled-after || "$enabled_calls" -lt 2 ) ]] ;;
    *) return 1 ;;
  esac
}
for failure in enable enabled syntax restart active enabled-after normal; do
  NGINX_CONF_RHEL="$task_tmp/$failure.conf"
  printf 'existing configuration\n' > "$NGINX_CONF_RHEL"
  enabled_calls=0
  if nginx_install > "$task_tmp/$failure.log" 2>&1; then
    [[ "$failure" == normal ]] || { echo "Accepted nginx failure: $failure" >&2; exit 1; }
    grep -Fq 'Nginx DMZ 프록시 적용 완료' "$task_tmp/$failure.log"
  else
    [[ "$failure" != normal ]] || { cat "$task_tmp/$failure.log"; exit 1; }
    if grep -Fq 'Nginx DMZ 프록시 적용 완료' "$task_tmp/$failure.log"; then exit 1; fi
    if [[ "$failure" == enable || "$failure" == enabled ]]; then
      [[ $(cat "$NGINX_CONF_RHEL") == 'existing configuration' ]]
    fi
  fi
done
# Existing config cannot mask an invalid, stopped or disabled service.
for failure in syntax active enabled; do
  enabled_calls=0
  [[ $(actual_nginx_status) == drift ]]
done
failure=normal;enabled_calls=0
[[ $(actual_nginx_status) == installed ]]
# A repeat successful invocation keeps config bytes and validates runtime again.
before=$(sha256sum "$NGINX_CONF_RHEL")
nginx_install > "$task_tmp/idempotent.log" 2>&1
[[ $(sha256sum "$NGINX_CONF_RHEL") == "$before" ]]
grep -Fxq 'restart nginx' "$task_tmp/systemctl-calls"
echo 'nginx-service-contract-regression: PASS (enable, enabled, syntax, restart, active, final-enabled, drift and repeat success)'
