#!/usr/bin/env bash
# Actual selection/guard/router functions, synthetic locks and mocked RPM/DNF only.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
. "$root/lib/os.sh"
. "$root/components/mariadb.sh"
task_tmp="$(mktemp -d)"; trap 'rm -rf "$task_tmp"' EXIT
vendor="$task_tmp/vendor/mariadb"; rocky="$task_tmp/rocky/mariadb"
mkdir -p "$vendor/repodata" "$rocky/repodata" "$task_tmp/empty-data"
printf fixture > "$vendor/repodata/repomd.xml"
printf fixture > "$rocky/repodata/repomd.xml"
vendor_names=(MariaDB-server MariaDB-client MariaDB-shared MariaDB-common)
rocky_names=(mariadb-server mariadb mariadb-common)
write_lock() {
  local dir="$1" version="$2" name; shift 2
  : > "$dir/PACKAGES.lock"
  for name in "$@"; do
    printf '%s|%s|x86_64|%064d|%s.rpm\n' "$name" "$version" 0 "$name" >> "$dir/PACKAGES.lock"
    printf fixture > "$dir/$name.rpm"
  done
}
write_lock "$vendor" '0:99.1.2-fixture' "${vendor_names[@]}"
write_lock "$rocky" '3:9.1.2-fixture' "${rocky_names[@]}"
cp "$vendor/PACKAGES.lock" "$task_tmp/vendor.lock"
: > "$task_tmp/installed"
rpm() {
  local package="${!#}" row
  row="$(awk -F'|' -v p="$package" '$1==p {print $2 "|" $3; n++} END {if(n!=1) exit 1}' "$task_tmp/installed")" || return 1
  [[ "$*" != *--qf* ]] || printf '%s' "$row"
}
OS_FAMILY=rhel; PKG=mock_dnf; OFFLINE_VERIFY_AUTHENTICITY=0
selected="$vendor"; solver_result=0
mock_dnf() {
  printf '%s\n' "$@" > "$task_tmp/args"
  (( solver_result == 0 )) || return "$solver_result"
  awk -F'|' '$1 ~ /^(MariaDB-(server|client|shared|common))$/ {print $1 "|" $2 "|" $3}' "$vendor/PACKAGES.lock" > "$task_tmp/installed"
  if [[ "${wrong_postinstall:-0}" == 1 ]]; then
    sed 's/0:99.1.2-fixture/0:98.0-fixture/g' "$task_tmp/installed" > "$task_tmp/wrong-installed"
    mv "$task_tmp/wrong-installed" "$task_tmp/installed"
  fi
}
bundle_dir() { printf '%s' "$selected"; }
err() { printf '%s\n' "$*" >&2; }
info() { :; }

[[ "$(mariadb_rpm_package_names "$vendor")" == $'MariaDB-server\nMariaDB-client\nMariaDB-shared\nMariaDB-common' ]]
[[ "$(mariadb_rpm_package_names "$rocky")" == $'mariadb-server\nmariadb\nmariadb-common' ]]
printf '%s\n' 'PASS exact vendor/OS family names'

for invalid in mixed duplicate missing version extra; do
  cp "$task_tmp/vendor.lock" "$vendor/PACKAGES.lock"
  case "$invalid" in
    mixed) head -n 1 "$rocky/PACKAGES.lock" >> "$vendor/PACKAGES.lock" ;;
    duplicate) head -n 1 "$task_tmp/vendor.lock" >> "$vendor/PACKAGES.lock" ;;
    missing) sed '/^MariaDB-client|/d' "$task_tmp/vendor.lock" > "$vendor/PACKAGES.lock" ;;
    version) sed 's/^MariaDB-client|0:99.1.2-fixture/MariaDB-client|0:99.1.3-fixture/' "$task_tmp/vendor.lock" > "$vendor/PACKAGES.lock" ;;
    extra) sed '1s/$/|unexpected/' "$task_tmp/vendor.lock" > "$vendor/PACKAGES.lock" ;;
  esac
  if mariadb_rpm_package_names "$vendor" >/dev/null 2>&1; then echo "Invalid $invalid lock accepted" >&2; exit 1; fi
done
cp "$task_tmp/vendor.lock" "$vendor/PACKAGES.lock"
printf 'mariadb-connector-c|0:3.0-fixture|x86_64|%064d|connector.rpm\n' 0 >> "$vendor/PACKAGES.lock"
[[ "$(mariadb_rpm_package_names "$vendor" | wc -l | tr -d ' ')" == 4 ]]
cp "$task_tmp/vendor.lock" "$vendor/PACKAGES.lock"
printf '%s\n' 'PASS mixed/duplicate/incomplete/mismatched/extra-column refusal; connector dependency allowed'

[[ "$(mariadb_rpm_state "$vendor")" == missing ]]
mariadb_rpm_install_guard "$vendor" "$task_tmp/empty-data"
awk -F'|' '{print $1 "|" $2 "|" $3}' "$task_tmp/vendor.lock" > "$task_tmp/installed"
[[ "$(mariadb_rpm_state "$vendor")" == installed ]]
mariadb_rpm_install_guard "$vendor" "$task_tmp/empty-data"
[[ "$(mariadb_status)" == installed ]]
printf '%s\n' 'PASS missing and exact installed cohort states'

for invalid in old-version foreign-family partial; do
  case "$invalid" in
    old-version) awk -F'|' '{print $1 "|0:98.0-fixture|" $3}' "$task_tmp/vendor.lock" > "$task_tmp/installed" ;;
    foreign-family) printf 'mariadb-server|3:9.1.2-fixture|x86_64\n' > "$task_tmp/installed" ;;
    partial) printf 'MariaDB-client|0:99.1.2-fixture|x86_64\n' > "$task_tmp/installed" ;;
  esac
  [[ "$(mariadb_rpm_state "$vendor")" == mismatch && "$(mariadb_status)" == mismatch ]]
  if mariadb_rpm_install_guard "$vendor" "$task_tmp/empty-data" 2>/dev/null; then echo 'In-place mismatch accepted' >&2; exit 1; fi
  if mariadb_install force 2>/dev/null; then echo 'Force bypassed migration guard' >&2; exit 1; fi
  [[ ! -e "$task_tmp/args" ]]
done
printf '%s\n' 'PASS foreign/old/partial cohort and FORCE reject before any DNF or service work'

: > "$task_tmp/installed"
for old_data in schema innodb aria; do
  mkdir -p "$task_tmp/$old_data"
  case "$old_data" in schema) mkdir "$task_tmp/$old_data/mysql" ;; innodb) : > "$task_tmp/$old_data/ibdata1" ;; aria) : > "$task_tmp/$old_data/aria_log_control" ;; esac
  if mariadb_rpm_install_guard "$vendor" "$task_tmp/$old_data" 2>/dev/null; then echo 'Old data directory accepted without server RPM' >&2; exit 1; fi
done
printf '%s\n' 'PASS retained standard database files block fresh installation'

install_local_packages "$vendor"
grep -Fx -- '--disablerepo=*' "$task_tmp/args" >/dev/null
grep -Fx -- '--setopt=offline-bundle.module_hotfixes=true' "$task_tmp/args" >/dev/null
for name in "${vendor_names[@]}"; do grep -Fx -- "$name-0:99.1.2-fixture.x86_64" "$task_tmp/args" >/dev/null; done
if grep -E 'https?://|allowerasing|skip-broken|nobest|nodeps|^mariadb-server-' "$task_tmp/args"; then exit 1; fi
[[ "$(mariadb_rpm_state "$vendor")" == installed ]]
printf '%s\n' 'PASS exact vendor EVR requests, offline-only solver and real postcondition check'

solver_result=7
if install_local_packages "$vendor"; then echo 'Failed solver accepted' >&2; exit 1; fi
printf '%s\n' 'PASS solver failure propagates'
solver_result=0; wrong_postinstall=1
if install_local_packages "$vendor" 2>/dev/null; then echo 'Wrong installed EVR accepted after solver success' >&2; exit 1; fi
printf '%s\n' 'PASS successful solver with wrong installed EVR is rejected'
