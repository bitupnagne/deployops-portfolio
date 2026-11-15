#!/usr/bin/env bash
# Actual component functions, temporary files and command failures; no host changes.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
temp="$(mktemp -d)"; trap 'rm -rf "$temp"' EXIT
SCRIPT_DIR="$ROOT"
. "$ROOT/components/deploy-system.sh"
err() { :; }
mkdir -p "$temp/home/config" "$temp/home/app"
printf old-env > "$temp/home/config/deploy-ops.env"
printf old-jar > "$temp/home/app/deploy-ops.jar"
printf old-unit > "$temp/old.service"
fail_point=''
install() {
  if [[ "$fail_point" == create && "$1" == -d ]]; then return 9; fi
  if [[ "$fail_point" == jar && "$*" == *deploy-ops.jar* ]]; then return 9; fi
  # Git for Windows cannot reproduce POSIX install chmod; preserve copy semantics in this command spy.
  if [[ "$1" == -d ]]; then builtin command mkdir -p "${@: -1}"
  else builtin command cp "${@: -2:1}" "${@: -1}"; fi
}
cp() {
  if [[ "$fail_point" == config && "$*" == *'/config '* ]]; then return 9; fi
  if [[ "$fail_point" == unit && "$*" == *old.service* ]]; then return 9; fi
  builtin command cp "$@"
}
for fail_point in create config jar unit; do
  if deploy_system_preserve_current_install "$temp/home" "$temp/pre-$fail_point" "$temp/old.service"; then
    echo 'Preimage write failure accepted' >&2; exit 1
  fi
  [[ "$(cat "$temp/home/config/deploy-ops.env")" == old-env && "$(cat "$temp/home/app/deploy-ops.jar")" == old-jar ]]
done
fail_point=''
deploy_system_preserve_current_install "$temp/home" "$temp/pre-success" "$temp/old.service"
cmp "$temp/home/config/deploy-ops.env" "$temp/pre-success/config/deploy-ops.env"
cmp "$temp/home/app/deploy-ops.jar" "$temp/pre-success/deploy-ops.jar"
cmp "$temp/old.service" "$temp/pre-success/old.service"
printf 'PASS: each failed preimage write stops and preserves existing env/JAR; complete snapshot matches\n'

db_calls=0
db_ensure_schema_and_user() { db_calls=$((db_calls+1)); }
if deploy_system_provision_local_db remote-db 3306 db user pw root; then exit 1; fi
if deploy_system_provision_local_db 127.0.0.1 13306 db user pw root; then exit 1; fi
[[ "$db_calls" == 0 ]]
deploy_system_provision_local_db 127.0.0.1 3306 db user pw root
deploy_system_provision_local_db localhost 3306 db user pw root
[[ "$db_calls" == 2 ]]
printf 'PASS: remote/nondefault DB creation is rejected without local SQL; explicit local pair is allowed\n'

# Full install function must return before DB/user/candidate replacement if snapshot fails.
DEPLOY_APP_HOME="$temp/home"; BUNDLE_ROOT="$temp"; BOM_DEPLOYOPS_JAR=candidate.jar
printf candidate > "$temp/candidate.jar"
deploy_system_status() { printf drift; }
deploy_system_docker_cli() { printf /bin/true; }
java_status() { printf installed; }
ask() { printf 2g; }
ask_confirm() { touch "$temp/forbidden-db-prompt"; return 1; }
ensure_system_user() { touch "$temp/forbidden-user-write"; }
command() {
  if [[ "$1" == -v && "$2" == /usr/libexec/platform-python ]]; then return 1; fi
  if [[ "$1" == -v && "$2" == python3 ]]; then printf task_python; return 0; fi
  builtin command "$@"
}
task_python() {
  if [[ "$2" == fields ]]; then
    printf '%s\0' 'jdbc:mariadb://127.0.0.1:3306/fixture' user pw 9090 existing-key primary '' same-sso admin "$temp/uploads" push
  fi
}
deploy_system_preserve_current_install() { return 9; }
if deploy_system_install force; then echo 'Install ignored failed preimage' >&2; exit 1; fi
[[ ! -e "$temp/forbidden-db-prompt" && ! -e "$temp/forbidden-user-write" ]]
[[ "$(cat "$temp/home/config/deploy-ops.env")" == old-env && "$(cat "$temp/home/app/deploy-ops.jar")" == old-jar ]]
printf 'PASS: full install stops before DB prompt/user/app mutation on preimage failure\n'
