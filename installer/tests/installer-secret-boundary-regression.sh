#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
. "$ROOT/components/common.sh"
. "$ROOT/components/mariadb.sh"
BOM_RUNNER_IMAGE=fixture BOM_RUNNER_HELPER_IMAGE=fixture BOM_RUNNER_BUILD_IMAGE=fixture
. "$ROOT/components/gitlab-runner.sh"
task="$(mktemp -d)"; trap 'rm -rf -- "$task"' EXIT
err() { :; }
password=$'quote\'back\\slash";&$fixture'
mysql_cli() { echo client_fixture; }
client_fixture() {
  [[ "$#" == 2 && "$1" == --defaults-file=* && "$2" == --protocol=socket ]]
  local option_file="${1#*=}"
  [[ "$(stat -c %a "$option_file")" == 600 ]]
  printf '%s' "$option_file" > "$task/option-path"
  cat "$option_file" > "$task/options"
  cat > "$task/sql"
  return "${client_rc:-0}"
}
db_root_exec "SELECT '$password';" "$password"
[[ "$(cat "$task/sql")" == "SELECT '$password';" ]]
[[ ! -e "$(cat "$task/option-path")" ]]
expected='password="quote'\''back\\slash\";&$fixture"'
grep -Fx "$expected" "$task/options" >/dev/null
client_rc=19
rc=0; db_root_exec 'SELECT 1;' "$password" || rc=$?
[[ "$rc" == 19 && ! -e "$(cat "$task/option-path")" ]]
# Curl's argv cannot contain header value; config stdin is escaped and exact.
curl() {
  [[ "$*" != *"$password"* && "$1" == --config && "$2" == - ]]
  cat > "$task/curl-config"
  return 23
}
rc=0; runner_api_curl "$password" -fsS http://fixture/api || rc=$?
[[ "$rc" == 23 ]]
grep -F 'PRIVATE-TOKEN: quote' "$task/curl-config" >/dev/null
# Execute the actual fixed shell against a fake Runner executable.
mkdir "$task/bin"
cat > "$task/bin/gitlab-runner" <<'SH'
#!/bin/bash
[[ "$*" != *fixture-secret* ]]
[[ "${CI_SERVER_TOKEN:-${REGISTRATION_TOKEN:-}}" == *fixture-secret ]]
exit 29
SH
chmod 700 "$task/bin/gitlab-runner"
export PATH="$task/bin:$PATH"
docker() { [[ "$1" == exec && "$2" == -i ]]; shift 3; "$@"; }
for token in glrt-fixture-secret legacy-fixture-secret; do
  rc=0; runner_register_secret "$token" register --non-interactive || rc=$?
  [[ "$rc" == 29 ]]
done
# Root SQL failure must not be turned into success. First auth via socket,
# subsequent calls with desired password, exact quote/backslash preservation.
db_root_exec() {
  if [[ "$1" == 'SELECT 1;' ]]; then
    [[ "${auth_failure:-0}" != 1 ]] || return 18
    return 0
  fi
  printf '%s' "$1" > "$task/root-sql"
  [[ "$2" == "$password" ]]
  return "${alter_rc:-0}"
}
mariadb_configure_root_password "$password"
grep -F "NO_BACKSLASH_ESCAPES" "$task/root-sql" >/dev/null
quoted="${password//\'/\'\'}"
grep -F "IDENTIFIED BY '$quoted';" "$task/root-sql" >/dev/null
alter_rc=31
if mariadb_configure_root_password "$password"; then exit 1; fi
auth_failure=1
if mariadb_configure_root_password "$password"; then exit 1; fi
if mariadb_configure_root_password $'bad\npassword'; then exit 1; fi
# Verify no sourced malicious component executes before trust rejection.
fixture="$task/installer"; mkdir -p "$fixture/lib" "$fixture/components"
cp "$ROOT/install.sh" "$fixture/install.sh"
printf 'touch "%s"\n' "$task/unsafe-executed" > "$fixture/lib/ui.sh"
chmod 666 "$fixture/lib/ui.sh"
if bash "$fixture/install.sh" --list > "$task/trust.log" 2>&1; then exit 1; fi
[[ ! -e "$task/unsafe-executed" ]]
grep -F 'root-owned' "$task/trust.log" >/dev/null
chmod 600 "$fixture/lib/ui.sh"; chown 1234 "$fixture/lib/ui.sh"
if bash "$fixture/install.sh" --list > "$task/trust.log" 2>&1; then exit 1; fi
[[ ! -e "$task/unsafe-executed" ]]
echo 'PASS: no secret argv, exact escaping, failure propagation, root source-before-trust rejection'
