#!/usr/bin/env bash
# Inject one required helper-copy failure; never copy files or enable a real unit.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/lib/nexus-backup.sh"
fixture="$(mktemp -d /tmp/nexus-backup-install-unit.XXXXXXXX)"
trap 'rm -f -- "$fixture/failed-helper" "$fixture/systemctl-called"; rmdir -- "$fixture"' EXIT
SCRIPT_DIR="$ROOT"
DEPLOY_APP_USER=deployops
err() { printf '%s\n' "$*" >&2; }
install() {
  case "$*" in
    *nexus-promotion-worker.py*) : > "$fixture/failed-helper"; return 44 ;;
    *) return 0 ;;
  esac
}
systemctl() { : > "$fixture/systemctl-called"; return 0; }
if deploy_system_install_nexus_backup_broker; then
  printf '%s\n' 'FAIL: missing required promotion helper was accepted' >&2
  exit 1
fi
[[ -f "$fixture/failed-helper" && ! -e "$fixture/systemctl-called" ]]
printf '%s\n' 'PASS: required helper installation failure propagates before timer activation'
