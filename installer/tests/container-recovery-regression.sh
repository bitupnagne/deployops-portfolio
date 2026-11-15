#!/usr/bin/env bash
# Stateful Docker fixture exercises replacement, failed startup, restore identity and project isolation.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
tmp="$(mktemp -d /tmp/deploy-container-recovery.XXXXXXXX)"
trap 'rm -rf -- "$tmp"' EXIT
source <(sed '/^main "\$@"$/d' "$repo/installer/deploy-agent/was/deploy-was-app.sh")
STATE_ROOT="$tmp/recovery"
export FIXTURE_ROOT="$tmp/docker"
mkdir -p "$FIXTURE_ROOT"
mkdir -p "$FIXTURE_ROOT/owners"
CONTAINER=project-a IMAGE=fixture:new HEALTH_MODE=none MEMORY_LIMIT=128m
MANAGED_PROJECT=1
DEPLOYMENT_ID=request-101
container_exists(){ [[ -f "$FIXTURE_ROOT/$1" ]]; }
verify_health(){
  printf '%s %s %s\n' "$HEALTH_MODE" "$HEALTH_PORT" "$HEALTH_PATH" >> "$FIXTURE_ROOT/health-checks"
  [[ ! -f "$FIXTURE_ROOT/health-failure" ]]
}
dump_failure_context(){ echo 'fixture diagnostics'; }
docker(){
  local action="$1"; shift
  case "$action" in
    image) return 0;;
    container)
      [[ "$1" == inspect ]]; shift
      local format="$2" name="$3"; [[ -f "$FIXTURE_ROOT/$name" ]] || return 1
      local id running generation; read -r id running generation < "$FIXTURE_ROOT/$name"
      case "$format" in
        '{{.Id}}') echo "$id";;
        '{{.State.Running}}') echo "$running";;
        '{{.State.Status}}') [[ "$running" == true ]] && echo running || echo exited;;
        '{{.State.Pid}}') [[ "$running" == true ]] && echo 1234 || echo 0;;
        '{{.HostConfig.NetworkMode}}') echo bridge;;
        *NetworkSettings.Ports*) printf '18080 8080/tcp\n';;
        '{{.State.ExitCode}} {{.State.OOMKilled}} {{.State.Running}}') echo "0 false $running";;
        *com.portfolio.deploy.generation*) echo "$generation";;
        *com.portfolio.deploy.project*) cat "$FIXTURE_ROOT/owners/$name" 2>/dev/null || true;;
        *com.portfolio.deploy.mount-spec*) echo '';;
        *com.portfolio.deploy.health-mode*) [[ "$id" == old-a ]] && echo http || echo none;;
        *com.portfolio.deploy.health-port*) [[ "$id" == old-a ]] && echo 8080 || echo 9090;;
        *com.portfolio.deploy.health-path*) [[ "$id" == old-a ]] && echo /old-health || echo /new-health;;
        *) echo "unexpected inspect $format" >&2; return 9;;
      esac;;
    stop|start)
      local name="${!#}" id running generation
      read -r id running generation < "$FIXTURE_ROOT/$name"
      if [[ "$action" == start && -f "$FIXTURE_ROOT/start-failure" ]]; then
        rm -f "$FIXTURE_ROOT/start-failure"; return 1
      fi
      [[ "$action" != stop ]] && running=true || running=false
      printf '%s %s %s\n' "$id" "$running" "$generation" > "$FIXTURE_ROOT/$name"
      # Restored old instances are healthy even when the new image fails.
      [[ "$action" != start || "$id" != old-a ]] || rm -f "$FIXTURE_ROOT/health-failure";;
    rename)
      mv "$FIXTURE_ROOT/$1" "$FIXTURE_ROOT/$2"
      if [[ -f "$FIXTURE_ROOT/owners/$1" ]]; then mv "$FIXTURE_ROOT/owners/$1" "$FIXTURE_ROOT/owners/$2"; fi;;
    rm) rm "$FIXTURE_ROOT/$1"; rm -f "$FIXTURE_ROOT/owners/$1";;
    run)
      [[ ! -f "$FIXTURE_ROOT/run-failure" ]] || return 1
      local name='' generation='' owner=''
      while (( $# )); do
        case "$1" in --name) name="$2"; shift 2;;
          --label)
            [[ "$2" != com.portfolio.deploy.generation=* ]] || generation="${2#*=}"
            [[ "$2" != com.portfolio.deploy.project=* ]] || owner="${2#*=}"
            shift 2;;
          *) shift;; esac
      done
      printf 'new-a true %s\n' "$generation" > "$FIXTURE_ROOT/$name"
      [[ -z "$owner" ]] || printf '%s\n' "$owner" > "$FIXTURE_ROOT/owners/$name"
      echo new-a;;
    ps)
      [[ ! -f "$FIXTURE_ROOT/daemon-failure" ]] || return 1
      if [[ " $* " == *" --format "* ]]; then
        local filter='' name=''
        while (( $# )); do
          case "$1" in --filter) filter="$2"; shift 2;; *) shift;; esac
        done
        name="${filter#name=^/}"; name="${name%\$}"
        if [[ -f "$FIXTURE_ROOT/$name" ]]; then cut -d' ' -f1 "$FIXTURE_ROOT/$name"; fi
      fi;;
    *) echo "unexpected docker action $action" >&2; return 9;;
  esac
}
seed(){
  rm -f "$FIXTURE_ROOT/project-a" "$FIXTURE_ROOT/project-a.deploy-previous" "$FIXTURE_ROOT/health-failure" "$FIXTURE_ROOT/run-failure" "$FIXTURE_ROOT/health-checks" "$FIXTURE_ROOT/start-failure"
  mkdir -p "$STATE_ROOT"; rm -f "$STATE_ROOT/project-a.state"
  rm -f "$FIXTURE_ROOT/owners/project-a" "$FIXTURE_ROOT/owners/project-a.deploy-previous"
  printf 'old-a true old-generation\n' > "$FIXTURE_ROOT/project-a"
  printf 'other-b true untouched\n' > "$FIXTURE_ROOT/project-b"
  printf '2\n' > "$FIXTURE_ROOT/owners/project-b"
}
seed
run_deploy > "$tmp/success.log"
[[ "$(cat "$FIXTURE_ROOT/owners/project-a")" == 1 ]]
[[ "$(cat "$FIXTURE_ROOT/project-a")" == 'new-a true request-101' ]]
[[ "$(cat "$FIXTURE_ROOT/project-a.deploy-previous")" == 'old-a false old-generation' ]]
restore_previous > "$tmp/restore.log"
[[ "$(cat "$FIXTURE_ROOT/project-a")" == 'old-a true old-generation' ]]
[[ "$(cat "$FIXTURE_ROOT/project-b")" == 'other-b true untouched' ]]
[[ "$(tail -1 "$FIXTURE_ROOT/health-checks")" == 'http 8080 /old-health' ]]

seed; touch "$FIXTURE_ROOT/health-failure"
if run_deploy > "$tmp/failed-health.log" 2>&1; then echo 'bad new health accepted'; exit 1; fi
[[ "$(cat "$FIXTURE_ROOT/project-a")" == 'old-a true old-generation' ]]

seed; touch "$FIXTURE_ROOT/run-failure"
if run_deploy > "$tmp/failed-run.log" 2>&1; then echo 'failed run accepted'; exit 1; fi
[[ "$(cat "$FIXTURE_ROOT/project-a")" == 'old-a true old-generation' ]]

seed; run_deploy > /dev/null
DEPLOYMENT_ID=another-generation
if restore_previous > /dev/null 2>&1; then echo 'foreign generation restored'; exit 1; fi
[[ "$(cat "$FIXTURE_ROOT/project-a")" == 'new-a true request-101' ]]
DEPLOYMENT_ID=request-101
printf 'foreign-id true request-101\n' > "$FIXTURE_ROOT/project-a"
if restore_previous > /dev/null 2>&1; then echo 'foreign instance restored'; exit 1; fi
[[ "$(cat "$FIXTURE_ROOT/project-a")" == 'foreign-id true request-101' ]]

seed; rm "$FIXTURE_ROOT/project-a"; run_deploy > /dev/null; restore_previous > /dev/null
[[ ! -f "$FIXTURE_ROOT/project-a" && "$(cat "$FIXTURE_ROOT/project-b")" == 'other-b true untouched' ]]

# A failed first state write never stops the live service. A failed CID write compensates
# using this invocation's authoritative docker-run ID and the already durable original state.
eval "$(declare -f write_recovery_state | sed '1s/write_recovery_state/real_write_recovery_state/')"
write_calls=0 fail_write_at=0
write_recovery_state(){
  write_calls=$((write_calls + 1))
  [[ "$write_calls" != "$fail_write_at" ]] || return 1
  real_write_recovery_state
}
seed; write_calls=0 fail_write_at=1
if run_deploy > "$tmp/failed-first-state.log" 2>&1; then echo 'state failure accepted'; exit 1; fi
[[ "$(cat "$FIXTURE_ROOT/project-a")" == 'old-a true old-generation' ]]
[[ ! -f "$FIXTURE_ROOT/project-a.deploy-previous" ]]

seed; write_calls=0 fail_write_at=2
if run_deploy > "$tmp/failed-new-state.log" 2>&1; then echo 'CID state failure accepted'; exit 1; fi
[[ "$(cat "$FIXTURE_ROOT/project-a")" == 'old-a true old-generation' ]]
[[ "$(tail -1 "$FIXTURE_ROOT/health-checks")" == 'http 8080 /old-health' ]]
fail_write_at=0

# Retry recovers an interruption after rename-back but before the original instance starts.
seed; run_deploy > /dev/null; touch "$FIXTURE_ROOT/start-failure"
if restore_previous > /dev/null 2>&1; then echo 'failed start accepted'; exit 1; fi
[[ "$(cat "$FIXTURE_ROOT/project-a")" == 'old-a false old-generation' ]]
restore_previous > /dev/null
[[ "$(cat "$FIXTURE_ROOT/project-a")" == 'old-a true old-generation' ]]

# An unrelated stopped container with a similar backup name is never removed.
seed; printf 'foreign-id false unrelated\n' > "$FIXTURE_ROOT/project-a.deploy-previous"
if run_deploy > /dev/null 2>&1; then echo 'foreign backup removed'; exit 1; fi
[[ "$(cat "$FIXTURE_ROOT/project-a.deploy-previous")" == 'foreign-id false unrelated' ]]
[[ "$(cat "$FIXTURE_ROOT/project-a")" == 'old-a true old-generation' ]]

# Interrupted replacement must not overwrite the only record for a preserved old instance.
seed; run_deploy > /dev/null; rm "$FIXTURE_ROOT/project-a"
state_before="$(cat "$STATE_ROOT/project-a.state")"
if run_deploy > /dev/null 2>&1; then echo 'orphaned recovery state overwritten'; exit 1; fi
[[ "$(cat "$STATE_ROOT/project-a.state")" == "$state_before" ]]
[[ "$(cat "$FIXTURE_ROOT/project-a.deploy-previous")" == 'old-a false old-generation' ]]

# Quiescence must inspect the container namespace/translated port; an empty host table
# alone would miss bridge-NAT connections.
ss(){ :; }
nsenter(){
  printf '%s\n' "$*" >> "$FIXTURE_ROOT/nsenter-calls"
  [[ "$*" == '--target 1234 --net -- ss -Htan state established ( sport = :8080 )' ]] || return 9
  [[ ! -f "$FIXTURE_ROOT/ss-failure" ]] || return 1
  [[ ! -f "$FIXTURE_ROOT/connections" ]] || echo '0 0 198.51.100.2:8080 198.51.100.1:43210'
}
sleep(){ :; }
seed; HEALTH_PORT=18080 DRAIN_TIMEOUT_SECONDS=1
run_quiesce > "$tmp/quiesce.log"
touch "$FIXTURE_ROOT/connections"
if run_quiesce > /dev/null 2>&1; then echo 'active connections drained'; exit 1; fi
[[ "$(cat "$FIXTURE_ROOT/project-a")" == 'old-a true old-generation' ]]
rm "$FIXTURE_ROOT/connections"; touch "$FIXTURE_ROOT/ss-failure"
if run_quiesce > /dev/null 2>&1; then echo 'ss failure treated as drained'; exit 1; fi
[[ "$(cat "$FIXTURE_ROOT/project-a")" == 'old-a true old-generation' ]]
rm "$FIXTURE_ROOT/ss-failure"
HEALTH_PORT=19090
if run_quiesce > /dev/null 2>&1; then echo 'unknown port accepted'; exit 1; fi

seed; printf 'old-a false old-generation\n' > "$FIXTURE_ROOT/project-a"
if run_health > /dev/null 2>&1; then echo 'stopped container probe accepted'; exit 1; fi

# A mistaken target name must not allow project 1 to replace or control project 2.
seed; CONTAINER=project-b
for command in run_deploy run_start run_stop run_restart run_health run_quiesce; do
  if "$command" > /dev/null 2>&1; then echo "foreign project accepted: $command"; exit 1; fi
  [[ "$(cat "$FIXTURE_ROOT/project-b")" == 'other-b true untouched' ]]
done

# Restore must check both the current and preserved ownership before stopping either.
CONTAINER=project-a; seed; run_deploy > /dev/null
printf '2\n' > "$FIXTURE_ROOT/owners/project-a.deploy-previous"
if restore_previous > /dev/null 2>&1; then echo 'foreign project backup restored'; exit 1; fi
[[ "$(cat "$FIXTURE_ROOT/project-a")" == 'new-a true request-101' ]]
[[ "$(cat "$FIXTURE_ROOT/project-a.deploy-previous")" == 'old-a false old-generation' ]]
printf '1\n' > "$FIXTURE_ROOT/owners/project-a.deploy-previous"
printf '2\n' > "$FIXTURE_ROOT/owners/project-a"
if restore_previous > /dev/null 2>&1; then echo 'foreign current project replaced on restore'; exit 1; fi
[[ "$(cat "$FIXTURE_ROOT/project-a")" == 'new-a true request-101' ]]

# An omitted scope cannot bypass labels; unlabeled canonical containers require explicit migration.
MANAGED_PROJECT=""
if run_stop > /dev/null 2>&1; then echo 'missing scope bypassed ownership'; exit 1; fi
MANAGED_PROJECT=1 CONTAINER=deploy-project-1
printf 'unlabeled true old-generation\n' > "$FIXTURE_ROOT/$CONTAINER"
if run_deploy > /dev/null 2>&1; then echo 'unlabeled canonical container taken over'; exit 1; fi
[[ "$(cat "$FIXTURE_ROOT/$CONTAINER")" == 'unlabeled true old-generation' ]]
rm "$FIXTURE_ROOT/$CONTAINER"
run_deploy > /dev/null
[[ "$(cat "$FIXTURE_ROOT/owners/$CONTAINER")" == 1 ]]
MANAGED_PROJECT=2
if run_stop > /dev/null 2>&1; then echo 'canonical name mismatch accepted'; exit 1; fi
echo 'PASS: immutable recovery, health, state failures, retry, project ownership, canonical adoption refusal, namespace drain and stopped probe'

# WEB wrapper uses these same stateful core functions; no real nginx or service is run.
seed; CONTAINER=project-a; MANAGED_PROJECT=1
rm "$FIXTURE_ROOT/project-a"
run_quiesce > "$tmp/quiesce-absent.log"
grep -q 'container absent' "$tmp/quiesce-absent.log"
touch "$FIXTURE_ROOT/daemon-failure"
if run_quiesce > /dev/null 2>&1; then echo 'daemon failure accepted as absent'; exit 1; fi
rm "$FIXTURE_ROOT/daemon-failure"
seed
printf 'old-a false old-generation\n' > "$FIXTURE_ROOT/project-a"
run_quiesce > "$tmp/quiesce-stopped.log"
grep -q 'container stopped' "$tmp/quiesce-stopped.log"
printf '2\n' > "$FIXTURE_ROOT/owners/project-a"
if run_quiesce > /dev/null 2>&1; then echo 'stopped foreign owner accepted'; exit 1; fi
echo 'PASS: first installation absence, stopped owned container and daemon/foreign-owner refusal'

source <(sed "/^web_main \"\\\$@\"$/d; s|/var/lock/deploy-ops-nginx.lock|$tmp/nginx.lock|g" "$repo/installer/deploy-agent/web/deploy-web-app.sh")
web_require_root(){ :; }
web_core(){
  printf '%s\n' "$1" >> "$tmp/web-core-calls"
  parse_args "$@"
  case "$ACTION" in
    capabilities) printf 'STOP_GRACE_VERSION=1\nROLLBACK_VERSION=1\nQUIESCE_VERSION=1\nPROJECT_OWNERSHIP_VERSION=1\n';;
    deploy) run_deploy;;
    restore) restore_previous;;
    probe) run_health;;
    *) echo "unexpected WEB fixture action $ACTION" >&2; return 1;;
  esac
}
web_nginx_ready(){ [[ ! -f "$tmp/nginx-preflight-failure" ]]; }
web_nginx_reload(){
  echo reload >> "$tmp/nginx-calls"
  if [[ -f "$tmp/nginx-reload-failure" ]]; then rm "$tmp/nginx-reload-failure"; return 1; fi
}
web_args=(--container project-a --image fixture:new --managed-project 1 --deployment-id request-101 --health-mode none)
MANAGED_PROJECT=1 CONTAINER=project-a DEPLOYMENT_ID=request-101
seed; : > "$tmp/web-core-calls"; : > "$tmp/nginx-calls"
web_main deploy "${web_args[@]}" --reload-nginx true > /dev/null
[[ "$(cat "$FIXTURE_ROOT/project-a")" == 'new-a true request-101' ]]
[[ "$(wc -l < "$tmp/nginx-calls")" == 1 ]]
web_main restore "${web_args[@]}" --reload-nginx false > /dev/null
[[ "$(cat "$FIXTURE_ROOT/project-a")" == 'old-a true old-generation' ]]
[[ "$(wc -l < "$tmp/nginx-calls")" == 1 ]]

seed; : > "$tmp/web-core-calls"; : > "$tmp/nginx-calls"; touch "$tmp/nginx-reload-failure"
if web_main deploy "${web_args[@]}" --reload-nginx true > "$tmp/web-reload-failure.log" 2>&1; then echo 'WEB reload failure reported success'; exit 1; fi
[[ "$(cat "$tmp/web-core-calls")" == $'deploy\nrestore' ]]
[[ "$(cat "$FIXTURE_ROOT/project-a")" == 'old-a true old-generation' ]]
[[ "$(cat "$FIXTURE_ROOT/project-b")" == 'other-b true untouched' ]]

seed; : > "$tmp/nginx-calls"; touch "$FIXTURE_ROOT/health-failure"
if web_main deploy "${web_args[@]}" --reload-nginx true > /dev/null 2>&1; then echo 'WEB bad health accepted'; exit 1; fi
[[ ! -s "$tmp/nginx-calls" && "$(cat "$FIXTURE_ROOT/project-a")" == 'old-a true old-generation' ]]

seed; : > "$tmp/web-core-calls"; touch "$tmp/nginx-preflight-failure"
if web_main deploy "${web_args[@]}" --reload-nginx true > /dev/null 2>&1; then echo 'WEB invalid nginx accepted'; exit 1; fi
[[ ! -s "$tmp/web-core-calls" && "$(cat "$FIXTURE_ROOT/project-a")" == 'old-a true old-generation' ]]
rm "$tmp/nginx-preflight-failure"
for bad in yes 1 ''; do
  if web_main deploy "${web_args[@]}" --reload-nginx "$bad" > /dev/null 2>&1; then echo 'invalid nginx switch accepted'; exit 1; fi
done
if web_main deploy "${web_args[@]}" --reload-nginx > /dev/null 2>&1; then echo 'missing nginx switch accepted'; exit 1; fi
if web_main deploy "${web_args[@]}" --reload-nginx false --reload-nginx true > /dev/null 2>&1; then echo 'duplicate nginx switch accepted'; exit 1; fi

seed; : > "$tmp/nginx-calls"
if web_main deploy --container project-b --image fixture:new --managed-project 1 --deployment-id request-101 --health-mode none --reload-nginx false > /dev/null 2>&1; then echo 'WEB foreign project accepted'; exit 1; fi
[[ "$(cat "$FIXTURE_ROOT/project-b")" == 'other-b true untouched' && ! -s "$tmp/nginx-calls" ]]
web_main capabilities > "$tmp/web-capabilities"
grep -qx 'PROJECT_OWNERSHIP_VERSION=1' "$tmp/web-capabilities"
echo 'PASS: WEB shared core, nginx preflight/reload compensation, failed health, scope and strict reload switch'
