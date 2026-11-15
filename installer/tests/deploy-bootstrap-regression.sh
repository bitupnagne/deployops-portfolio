#!/usr/bin/env bash
# Installer control-flow regression. All host operations are mocked; files stay in a temporary directory.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
task_tmp="$(mktemp -d)"
trap 'rm -rf "$task_tmp"' EXIT
chmod 0755 "$task_tmp"

. "$SCRIPT_DIR/lib/ui.sh"
init_ui
. "$SCRIPT_DIR/components/deploy-system.sh"
. "$SCRIPT_DIR/components/nginx.sh"
export UNATTENDED=1

mkdir -p "$task_tmp/bin" "$task_tmp/bundle"
printf '#!/bin/sh\nexit 0\n' > "$task_tmp/bin/docker"
chmod +x "$task_tmp/bin/docker"
export PATH="$task_tmp/bin:$PATH"
MOCK_DOCKER_GROUP=1
MOCK_UNIT_EXISTS=0
MOCK_SERVICE_ACTIVE=1
MOCK_SUPPLEMENTARY_GROUPS="docker"
getent() { [[ "$*" == 'group docker' && "$MOCK_DOCKER_GROUP" == 1 ]]; }
unit_exists() { [[ "$MOCK_UNIT_EXISTS" == 1 ]]; }
systemctl() {
  case "$1" in
    show) printf '%s\n' "$MOCK_SUPPLEMENTARY_GROUPS" ;;
    is-active) [[ "$MOCK_SERVICE_ACTIVE" == 1 ]] ;;
    *) printf '%s\n' "$*" >> "$task_tmp/systemctl-calls" ;;
  esac
}
java_status() { printf installed; }
ensure_system_user() { printf '%s\n' "$*" >> "$task_tmp/user-calls"; }
usermod() { echo 'Unexpected persistent group membership change' >&2; return 1; }
chown() { :; }
install() {
  local args=()
  while (( $# )); do
    case "$1" in -o|-g) shift 2 ;; *) args+=("$1"); shift ;; esac
  done
  command install "${args[@]}"
}
write_systemd_service() { printf '%s\n' "$2" > "$task_tmp/app.service"; }
app_healthcheck() { :; }
_deploy_system_install_backup() { :; }
deploy_system_install_nexus_backup_broker() { printf '%s\n' nexus-backup-broker >> "$task_tmp/broker-install-calls"; }
_deploy_system_write_credentials() { :; }

DEPLOY_APP_HOME="$task_tmp/app"
DEPLOY_APP_USER=deployops
BUNDLE_ROOT="$task_tmp/bundle"
BOM_DEPLOYOPS_JAR=app.jar
printf fixture > "$BUNDLE_ROOT/$BOM_DEPLOYOPS_JAR"
LOCK_DEPLOYOPS_SHA256="$(sha256sum "$BUNDLE_ROOT/$BOM_DEPLOYOPS_JAR" | awk '{print $1}')"
JAVA_HOME_DIR="$task_tmp/java"
DEPLOYOPS_DB_PASSWORD=regression-only-fixture
DEPLOYOPS_ADMIN_PASSWORD=regression-only-fixture
SSO_SHARED_SECRET=regression-only-fixture
DEPLOYOPS_CRYPTO_KEY=regression-only-fixture
DEPLOYOPS_CREATE_DB=n

# 선택한 전달 방식은 응답파일과 직접 설치 모두 제품 변경 전에 검증한다.
for mode in '' push registry; do
  printf 'DEPLOYOPS_IMAGE_TRANSFER_MODE=%s\n' "$mode" > "$task_tmp/transfer.env"
  load_answers "$task_tmp/transfer.env"
  [[ "$DEPLOYOPS_IMAGE_TRANSFER_MODE" == "$mode" ]]
done
printf 'DEPLOYOPS_IMAGE_TRANSFER_MODE=invalid\n' > "$task_tmp/transfer.env"
if load_answers "$task_tmp/transfer.env"; then echo 'Accepted invalid answer mode' >&2; exit 1; fi
DEPLOYOPS_IMAGE_TRANSFER_MODE=invalid
if deploy_system_install; then echo 'Accepted invalid direct-install mode' >&2; exit 1; fi
[[ ! -e "$DEPLOY_APP_HOME" && ! -e "$task_tmp/user-calls" ]]
DEPLOYOPS_IMAGE_TRANSFER_MODE=

# Missing prerequisites are caught before application files or accounts are installed.
command() {
  if [[ "$*" == '-v docker' ]]; then return 1; fi
  builtin command "$@"
}
if deploy_system_install; then echo 'Accepted a missing Docker CLI' >&2; exit 1; fi
unset -f command
[[ ! -e "$DEPLOY_APP_HOME" && ! -e "$task_tmp/user-calls" ]]
MOCK_DOCKER_GROUP=0
if deploy_system_install; then echo 'Accepted a missing Docker group' >&2; exit 1; fi
[[ ! -e "$DEPLOY_APP_HOME" && ! -e "$task_tmp/user-calls" ]]
MOCK_DOCKER_GROUP=1
deploy_system_install
grep -Fxq 'APP_BOOTSTRAP_IMAGE_TRANSFER_MODE=' "$DEPLOY_APP_HOME/config/deploy-ops.env"
grep -Fxq 'User=deployops' "$task_tmp/app.service"
grep -Fxq 'SupplementaryGroups=docker' "$task_tmp/app.service"
grep -Fxq "ExecStartPre=$task_tmp/bin/docker info --format {{.ServerVersion}}" "$task_tmp/app.service"
[[ "$(cat "$task_tmp/user-calls")" == "deployops $DEPLOY_APP_HOME" ]]

DEPLOYOPS_IMAGE_TRANSFER_MODE=push
deploy_system_install
grep -Fxq 'APP_BOOTSTRAP_IMAGE_TRANSFER_MODE=push' "$DEPLOY_APP_HOME/config/deploy-ops.env"
DEPLOYOPS_IMAGE_TRANSFER_MODE=registry
deploy_system_install
grep -Fxq 'APP_BOOTSTRAP_IMAGE_TRANSFER_MODE=registry' "$DEPLOY_APP_HOME/config/deploy-ops.env"
[[ "$(wc -l < "$task_tmp/broker-install-calls")" == 3 ]]
grep -Fxq 'DEPLOYOPS_IMAGE_TRANSFER_MODE=push' "$SCRIPT_DIR/profiles/depnet-deploy.env"

# A healthy application with the old unit is drift until service-scoped Docker access is configured.
MOCK_UNIT_EXISTS=1
MOCK_SUPPLEMENTARY_GROUPS=''
[[ "$(deploy_system_status)" == drift ]]
MOCK_SUPPLEMENTARY_GROUPS='other docker'
[[ "$(deploy_system_status)" == installed ]]
MOCK_SERVICE_ACTIVE=0
[[ "$(deploy_system_status)" == drift ]]
MOCK_SERVICE_ACTIVE=1

if [[ "${1:-}" == --deploy-only ]]; then
  echo 'deploy-bootstrap-regression: PASS (optional transfer modes, enum refusal, env propagation, service Docker access, unit drift)'
  exit 0
fi

nginx_status() { printf installed; }
nginx() { printf '%s\n' "$*" >> "$task_tmp/nginx-calls"; }
_nginx_disable_stock_default() { printf '%s\n' "$*" >> "$task_tmp/default-disabled"; }
NGINX_CONF_RHEL="$task_tmp/nginx.conf"
OS_FAMILY=rhel
NGINX_REPLAY_LOCK_ROOT="$task_tmp/nginx-locks"
load_answers "$SCRIPT_DIR/profiles/target-web.env"
NGINX_STATIC_ROOT="$task_tmp/web"
NGINX_UPSTREAM_HOST=127.0.0.1

# A live configuration is preserved even when new answer values would collide.
printf 'existing configuration\n' > "$NGINX_CONF_RHEL"
NGINX_CUSTOM_SERVICES='app|80|8080||yes'
nginx_install
[[ "$(cat "$NGINX_CONF_RHEL")" == 'existing configuration' ]]
grep -Fxq 'reload nginx' "$task_tmp/systemctl-calls"
[[ ! -e "$task_tmp/default-disabled" ]]

# On a fresh target, collisions fail before publishing a configuration or changing defaults.
NGINX_CONF_RHEL="$task_tmp/fresh-nginx.conf"
if nginx_install; then echo 'Accepted static/proxy port collision' >&2; exit 1; fi
[[ ! -e "$NGINX_CONF_RHEL" ]]
[[ ! -e "$task_tmp/default-disabled" ]]

# Different textual forms of the same TCP port also collide.
NGINX_CUSTOM_SERVICES='first|8080|8080||yes;second|08080|8081||no'
if nginx_install; then echo 'Accepted duplicate custom proxy ports' >&2; exit 1; fi
[[ ! -e "$NGINX_CONF_RHEL" ]]
[[ ! -e "$task_tmp/default-disabled" ]]

NGINX_PROXY_DEPLOY=y
NGINX_CUSTOM_SERVICES='app|9090|8080||yes'
if nginx_install; then echo 'Accepted built-in/custom proxy collision' >&2; exit 1; fi
[[ ! -e "$NGINX_CONF_RHEL" ]]
[[ ! -e "$task_tmp/default-disabled" ]]
NGINX_PROXY_DEPLOY=n

# The shipped WEB profile generates a fresh static and distinct proxy listener.
[[ ! -e "$NGINX_CONF_RHEL" ]]
load_answers "$SCRIPT_DIR/profiles/target-web.env"
NGINX_STATIC_ROOT="$task_tmp/web"
NGINX_UPSTREAM_HOST=127.0.0.1
nginx_install
[[ "$(grep -c '^    listen 80 default_server;' "$NGINX_CONF_RHEL")" == 1 ]]
[[ "$(grep -c '^    listen 8080;' "$NGINX_CONF_RHEL")" == 1 ]]
grep -Fq "root $NGINX_STATIC_ROOT/current;" "$NGINX_CONF_RHEL"
grep -Fq 'proxy_pass http://127.0.0.1:8080;' "$NGINX_CONF_RHEL"
grep -Fxq 'restart nginx' "$task_tmp/systemctl-calls"

echo 'deploy-bootstrap-regression: PASS (service Docker access, missing CLI/group, unit drift, WEB listener collisions, default profile)'
