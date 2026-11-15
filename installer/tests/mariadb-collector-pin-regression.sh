#!/usr/bin/env bash
# Pure collector contracts: mocked dispatcher and temporary package locks, no network/RPM commands.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
. "$root/bundles/fetch-bundles.sh" --os rocky8 --packages
task_tmp="$(mktemp -d)"; trap 'rm -rf "$task_tmp"' EXIT
_dnf_download() { printf '%s\n' "$@" > "$task_tmp/request"; }
fetch_mariadb
[[ "$MARIADB_VERSION" == 11.4.13 ]]
[[ "$MARIADB_RPM_RELEASE" == 1.el8 ]]
grep -Fx -- '--repofrompath=deploy-mariadb-archive,https://archive.mariadb.org/mariadb-11.4.13/yum/rhel8-amd64' "$task_tmp/request" >/dev/null
grep -Fx -- '--setopt=deploy-mariadb-archive.module_hotfixes=true' "$task_tmp/request" >/dev/null
for name in MariaDB-server MariaDB-client MariaDB-shared MariaDB-common MariaDB-backup; do
  grep -Fx -- "$name-0:11.4.13-1.el8.x86_64" "$task_tmp/request" >/dev/null
done
[[ "$(grep -c '^MariaDB-' "$task_tmp/request")" == 5 ]]
if grep -E '^mariadb-server$|allowerasing|skip-broken|nobest|nodeps' "$task_tmp/request"; then exit 1; fi
printf '%s\n' 'PASS fixed archive, exact five vendor EVR/arch requests and modular hotfix option'

lock="$task_tmp/PACKAGES.lock"
for name in MariaDB-server MariaDB-client MariaDB-shared MariaDB-common MariaDB-backup; do
  printf '%s|0:11.4.13-1.el8|x86_64|%064d|%s.rpm\n' "$name" 0 "$name" >> "$lock"
done
cp "$lock" "$task_tmp/valid.lock"
verify_collected_mariadb_lock "$task_tmp"
printf '%s\n' 'PASS matching vendor core and backup EVR'
for invalid in patch release backup missing mixed compat; do
  cp "$task_tmp/valid.lock" "$lock"
  case "$invalid" in
    patch) sed 's/11.4.13/11.4.12/g' "$task_tmp/valid.lock" > "$lock" ;;
    release) sed 's/1.el8/2.el8/g' "$task_tmp/valid.lock" > "$lock" ;;
    backup) sed 's/^MariaDB-backup|0:11.4.13/MariaDB-backup|0:11.4.12/' "$task_tmp/valid.lock" > "$lock" ;;
    missing) sed '/^MariaDB-backup|/d' "$task_tmp/valid.lock" > "$lock" ;;
    mixed) printf 'mariadb-server|3:10.3-fixture|x86_64|%064d|old.rpm\n' 0 >> "$lock" ;;
    compat) printf 'MariaDB-client-compat|0:11.4.12-1.el8|noarch|%064d|compat.rpm\n' 0 >> "$lock" ;;
  esac
  if verify_collected_mariadb_lock "$task_tmp"; then echo "Invalid collector $invalid accepted" >&2; exit 1; fi
done
printf '%s\n' 'PASS wrong patch/release, mismatched or missing backup and OS-family mixing are rejected'
[[ "$(rpm_key_contracts | tail -n 1)" == 'mariadb|177F4010FE56CA3336300305F1656F24C74CD1D8|893f3856fbdab24ab610731247ac06c1c520aead4b5e0dfef412f1395171037b|https://supplychain.mariadb.com/MariaDB-Server-GPG-KEY' ]]
printf '%s\n' 'PASS reviewed Community 2023+ key added to existing optional authentication contracts'
