#!/usr/bin/env bash
# Actual component/replay functions, fixture package/service commands. No OS mutation.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
. "$root/components/nginx.sh"
task_tmp="$(mktemp -d)"; trap 'rm -rf "$task_tmp"' EXIT
OS_FAMILY=rhel
NGINX_CONF_RHEL="$task_tmp/officesystem.conf"
NGINX_REPLAY_LOCK_ROOT="$task_tmp/locks"
printf 'server { listen 8080; location / { proxy_pass http://203.0.113.11:18080; } }\n# retained UI policy\n' > "$NGINX_CONF_RHEL"
printf 'server { listen 8088; }\n' > "$task_tmp/another-site.conf"
before="$(sha256sum "$NGINX_CONF_RHEL" "$task_tmp/another-site.conf")"
info() { :; }; err() { :; }
flock() { :; } # Existing lock protocol has a separate fixture; no host locks here.
nginx() { [[ "${nginx_valid:-1}" == 1 ]]; }
pkg_is_installed() { return 0; }
bundle_dir() { printf '%s' "$task_tmp/packages"; }
bundle_has_packages() { return 0; }
command() {
  if [[ "$*" == '-v openssl' ]]; then [[ -f "$task_tmp/openssl-cli" ]]; return; fi
  builtin command "$@"
}
stat() {
  if [[ "$*" == '-c %u '* ]]; then printf '0\n'; else builtin command stat "$@"; fi
}
systemctl() {
  printf '%s\n' "$*" >> "$task_tmp/service.log"
  case "$1" in
    is-active|is-enabled|enable|reload) return 0;;
    restart) [[ "${restart_result:-0}" == 0 ]];;
    *) return 99;;
  esac
}
install_local_packages() {
  printf '%s\n' "$1" >> "$task_tmp/packages.log"
  [[ "${package_result:-0}" == 0 ]] || return 1
  [[ "${provide_cli:-1}" == 0 ]] || : > "$task_tmp/openssl-cli"
}

# Valid Nginx alone is insufficient; ordinary replay must repair the CLI prerequisite.
[[ "$(nginx_status)" == drift ]]
nginx_install
[[ "$(nginx_status)" == installed ]]
[[ "$(wc -l < "$task_tmp/packages.log")" == 1 ]]
grep -qx 'restart nginx' "$task_tmp/service.log"
[[ "$(sha256sum "$NGINX_CONF_RHEL" "$task_tmp/another-site.conf")" == "$before" ]]

# A fully ready normal replay does not run a package transaction again.
: > "$task_tmp/service.log"
nginx_install
[[ "$(wc -l < "$task_tmp/packages.log")" == 1 ]]
grep -qx 'reload nginx' "$task_tmp/service.log"
if grep -qx 'restart nginx' "$task_tmp/service.log"; then exit 1; fi

# Failed solver, falsely reported package success, and failed service activation stay failures.
rm "$task_tmp/openssl-cli"; : > "$task_tmp/service.log"
package_result=1
if nginx_install; then echo 'Package failure was ignored' >&2; exit 1; fi
[[ ! -s "$task_tmp/service.log" ]]
unset package_result
provide_cli=0
if nginx_install; then echo 'Missing CLI after install was accepted' >&2; exit 1; fi
[[ ! -s "$task_tmp/service.log" ]]
unset provide_cli
restart_result=1
if nginx_install; then echo 'Failed repaired Nginx restart was accepted' >&2; exit 1; fi
unset restart_result
[[ "$(sha256sum "$NGINX_CONF_RHEL" "$task_tmp/another-site.conf")" == "$before" ]]
echo 'nginx OpenSSL reconciliation: PASS (missing CLI drift, normal repair, config/site preservation, no needless reinstall, solver/CLI/restart failure)'
