#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
fixture="$(mktemp -d /tmp/mariadb-empty-datadir.XXXXXX)"
trap '[[ "$fixture" == /tmp/mariadb-empty-datadir.* && -d "$fixture" && ! -L "$fixture" ]] && rm -rf -- "$fixture"' EXIT
# Exercise the real component function; redirect only its fixed data path to a
# private fixture. Package installation, DB initializer and service manager are
# explicit test boundaries. No real service, package or user database is changed.
sed "s@/var/lib/mysql@$fixture/data@g" "$ROOT/components/mariadb.sh" > "$fixture/component.sh"
source "$fixture/component.sh"
info() { :; }
err() { printf '%s\n' "$*" >> "$fixture/errors"; }
pkg_is_installed() { return 0; }
bundle_dir() { printf '%s\n' "$fixture"; }
mariadb_rpm_install_guard() { return 0; }
mariadb_rpm_state() { echo installed; }
systemctl() { return 1; } # Stop after the initialization boundary.
chown() { :; }
install() { command mkdir -p -- "${@: -1}"; }
mariadb-install-db() { echo init >> "$fixture/init"; }
mysql_install_db() { echo fallback >> "$fixture/init"; }
OS_FAMILY=debian
mkdir "$fixture/data"
printf 'must preserve\n' > "$fixture/data/ibdata1"
before="$(sha256sum "$fixture/data/ibdata1")"
if mariadb_install; then echo 'unexpected successful startup' >&2; exit 1; fi
[[ "$(sha256sum "$fixture/data/ibdata1")" == "$before" && ! -e "$fixture/init" ]]
grep -q '기존 파일' "$fixture/errors"
# Empty means empty, including hidden files.
mv "$fixture/data/ibdata1" "$fixture/preserved"
printf hidden > "$fixture/data/.interrupted"
if mariadb_install; then exit 1; fi
[[ -f "$fixture/data/.interrupted" && ! -e "$fixture/init" ]]
mv "$fixture/data/.interrupted" "$fixture/hidden-preserved"
if mariadb_install; then exit 1; fi
[[ "$(cat "$fixture/init")" == init ]]
# A link cannot redirect initialization to another directory.
mv "$fixture/data" "$fixture/real-data"
ln -s "$fixture/real-data" "$fixture/data"
if mariadb_install; then exit 1; fi
[[ "$(wc -l < "$fixture/init")" -eq 1 ]]
grep -q '일반 디렉터리' "$fixture/errors"
# Rocky follows its existing RPM path and never enters the Debian initializer.
OS_FAMILY=rhel
if mariadb_install; then exit 1; fi
[[ "$(wc -l < "$fixture/init")" -eq 1 ]]
printf 'PASS existing/hidden files preserved; empty initialized; symlink refused; Rocky unchanged\n'
