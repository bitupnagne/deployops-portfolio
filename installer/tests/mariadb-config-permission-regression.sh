#!/usr/bin/env bash
# Isolated real-shell regression. No host service, package or database changes.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[[ "$(id -u)" == 0 ]] || { echo 'Run isolated permission regression as root.' >&2; exit 1; }
tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT
mkdir -p "$tmpdir/etc/my.cnf.d"
# Relocate only filesystem roots in a private copy, retaining the actual install function.
sed "s|/etc/|$tmpdir/etc/|g; s|/var/lib/|$tmpdir/var/lib/|g" "$SCRIPT_DIR/components/mariadb.sh" > "$tmpdir/component.sh"
. "$tmpdir/component.sh"
OS_FAMILY=rocky
UNATTENDED=1
MARIADB_ROOT_PASSWORD=''
restart_rc=0
start_rc=0
successes=0
enables=0
info() { :; }
warn() { :; }
err() { :; }
success() { successes=$((successes + 1)); }
pkg_is_installed() { return 0; }
unit_exists() { echo "Unexpected legacy unit_exists call" >&2; exit 92; }
ask() { printf '%s' 198.18.4.1; }
unit_mode=canonical
systemctl() {
  case "$1" in
    show)
      case "$unit_mode:$2" in
        canonical:mariadb.service) printf 'LoadState=loaded\nId=mariadb.service\n' ;;
        alias:mysql.service) printf 'Id=mariadb.service\nLoadState=loaded\n' ;;
        invalid:*) printf 'LoadState=loaded\nId=other.service\n' ;;
        *) printf 'LoadState=not-found\nId=%s\n' "$2" ;;
      esac ;;
    restart) [[ "$2" == mariadb.service ]] || return 93; return "$restart_rc" ;;
  esac
}
service_enable_now() {
  [[ "$1" == mariadb.service ]] || return 94
  enables=$((enables + 1))
  [[ "$(stat -c '%a %u:%g' "$tmpdir/etc/my.cnf.d/zz-office-system.cnf")" == '644 0:0' ]] || return 90
  return "$start_rc"
}
mysql_cli() { echo 'Unexpected database credential mutation' >&2; exit 91; }
umask 077
printf 'private fixture\n' > "$tmpdir/answers.env"
mariadb_install
[[ "$successes" == 1 ]]
[[ "$(stat -c '%a' "$tmpdir/answers.env")" == 600 ]]
grep -qx 'bind-address = 198.18.4.1' "$tmpdir/etc/my.cnf.d/zz-office-system.cnf"
if id mysql >/dev/null 2>&1; then
  chmod 0755 "$tmpdir" "$tmpdir/etc" "$tmpdir/etc/my.cnf.d"
  runuser -u mysql -- test -r "$tmpdir/etc/my.cnf.d/zz-office-system.cnf"
fi
restart_rc=7
if mariadb_install; then echo 'Restart failure wrongly accepted' >&2; exit 1; fi
[[ "$successes" == 1 ]]
restart_rc=0
start_rc=8
if mariadb_install; then echo 'Start failure wrongly accepted' >&2; exit 1; fi
[[ "$successes" == 1 ]]
unit_mode=alias
start_rc=0
mariadb_install
[[ "$successes" == 2 && "$MARIADB_SERVICE" == mariadb.service ]]
unit_mode=missing
before_enables="$enables"
if mariadb_install; then echo 'Missing unit wrongly accepted' >&2; exit 1; fi
[[ "$enables" == "$before_enables" && "$successes" == 2 ]]
unit_mode=invalid
if mariadb_install; then echo 'Unexpected canonical unit wrongly accepted' >&2; exit 1; fi
[[ "$enables" == "$before_enables" && "$successes" == 2 ]]
echo 'PASS: private umask, readable config before start, private answers unchanged, start/restart fail closed'
