#!/usr/bin/env bash
set -euo pipefail

action="${1:-}"; unit="${2:-}"
[[ "$action" =~ ^(start|stop|restart|status)$ ]] || { echo "usage: deploy-service-control.sh start|stop|restart|status UNIT.service" >&2; exit 2; }
[[ "$unit" =~ ^[A-Za-z0-9][A-Za-z0-9_.@:-]{0,127}\.service$ ]] || { echo "invalid service unit" >&2; exit 2; }
# Only units explicitly installed under the deploy agent namespace can be controlled.
case "$unit" in deploy-*.service|worksystem-*.service|office-*.service) ;; *) echo "service unit is not allowed" >&2; exit 3 ;; esac
exec /usr/bin/systemctl "$action" "$unit"
