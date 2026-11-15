#!/usr/bin/env bash
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
tmp="$(mktemp -d /tmp/deploy-storage-control.XXXXXXXX)"
trap 'rm -rf -- "$tmp"' EXIT
source <(sed '/^main "\$@"$/d' "$repo/installer/deploy-agent/was/deploy-was-app.sh")
export STORAGE_FIXTURE="$tmp"
cat > "$tmp/helper" <<'HELPER'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$STORAGE_FIXTURE/events"
if [[ "$1" == check-container && -f "$STORAGE_FIXTURE/nas-missing" ]]; then exit 4; fi
HELPER
chmod +x "$tmp/helper"
STORAGE_HELPER="$tmp/helper"
CONTAINER=deploy-project-2 MANAGED_PROJECT=2 HEALTH_MODE=container
container_exists(){ return 0; }
assert_container_owner(){ return 0; }
verify_health(){ return 0; }
docker(){
  case "$1" in
    container)
      case "$4" in
        *mount-spec*) echo '/var/lib/deploy-system/storage/specs/2-TEST-3-4-deploy-project-2.json';;
        *ExitCode*) echo '0 false false';;
        *) return 1;;
      esac;;
    start|stop) echo "docker-$1" >> "$tmp/events";;
    ps) return 0;;
    *) return 1;;
  esac
}
touch "$tmp/nas-missing"
if run_start; then echo 'FAIL: missing NAS start accepted'; exit 1; fi
if run_restart; then echo 'FAIL: missing NAS restart accepted'; exit 1; fi
if grep -q 'docker-' "$tmp/events"; then echo 'FAIL: missing NAS touched container'; exit 1; fi
rm "$tmp/nas-missing"
: > "$tmp/events"
run_start >/dev/null
[[ "$(sed -n '1p' "$tmp/events")" == 'check-container --container deploy-project-2' ]]
[[ "$(sed -n '2p' "$tmp/events")" == 'desired --container deploy-project-2 --enabled false' ]]
[[ "$(sed -n '3p' "$tmp/events")" == docker-start ]]
[[ "$(sed -n '4p' "$tmp/events")" == 'desired --container deploy-project-2 --enabled true' ]]
: > "$tmp/events"
run_stop >/dev/null
[[ "$(sed -n '1p' "$tmp/events")" == 'desired --container deploy-project-2 --enabled false' ]]
[[ "$(sed -n '2p' "$tmp/events")" == docker-stop ]]
echo 'PASS: missing NAS blocks start/restart before mutation; manual stop disables boot intent; successful start enables intent after health'
