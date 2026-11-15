#!/usr/bin/env bash
# Exercises the actual WAS container-health function with Docker inspect responses mocked.
set -euo pipefail

repo="$(cd "$(dirname "$0")/../.." && pwd)"
tmp="$(mktemp -d /tmp/codex-was-e2e-20260924-health.XXXXXX)"
tmp="$(cd "$tmp" && pwd)"
[[ "$tmp" =~ ^/tmp/codex-was-e2e-20260924-health\.[A-Za-z0-9]+$ ]] || exit 1
trap 'rm -rf -- "$tmp"' EXIT
export INSPECT_RESULTS_FILE="$tmp/results"
export INSPECT_INDEX_FILE="$tmp/index"
export ACTION_DOCKER_LOG_FILE="$tmp/action-docker.log"
export ACTION_STATE_FILE="$tmp/action-state"
source <(sed '/^main "\$@"$/d' "$repo/installer/deploy-agent/was/deploy-was-app.sh")
require_root(){ :; }
docker_ready(){ :; }
container_exists(){ return 0; }
sleep(){ :; }

CONTAINER=fixture
HEALTH_MODE=container
docker(){
  [[ -n "${ACTION_DOCKER_LOG_FILE:-}" ]] && printf '%s\n' "$*" >> "$ACTION_DOCKER_LOG_FILE"
  if [[ "$1 $2" != 'container inspect' ]]; then
    case "$1" in
      start|restart) printf 'restarting\n' > "$ACTION_STATE_FILE" ;;
      stop) printf 'stopped\n' > "$ACTION_STATE_FILE" ;;
      logs|ps) ;;
      *) echo "unexpected docker call: $*" >&2; return 99 ;;
    esac
    return 0
  fi
  local format
  if [[ "$3" == --format ]]; then
    format="$4"
    [[ "$5" == "$CONTAINER" ]] || return 97
  elif [[ "$3" == "$CONTAINER" && "$4" == --format ]]; then
    format="$5"
  else
    return 98
  fi
  if [[ "$format" == '{{.State.Running}}' ]]; then
    # Docker may report Running=true while State.Status is Restarting.
    printf 'true\n'
    return 0
  fi
  [[ "$format" == '{{.State.Status}} {{.RestartCount}}' ]] || {
    printf 'status=restarting\nexitCode=42\nrestarts=4\n'
    return 0
  }
  local index line
  index="$(cat "$INSPECT_INDEX_FILE")"
  printf '%s\n' "$((index + 1))" > "$INSPECT_INDEX_FILE"
  line="$(sed -n "$((index + 1))p" "$INSPECT_RESULTS_FILE")"
  [[ -n "$line" ]] || line="$(tail -n 1 "$INSPECT_RESULTS_FILE")"
  printf '%s\n' "$line"
}

free(){ printf '              total used free\nMem: 1 1 0\n'; }
df(){ printf 'Filesystem Size Used Avail Use%% Mounted on\n/dev/mock 1G 1G 0G 100%% /\n'; }

set_results(){
  printf '%s\n' "$@" > "$INSPECT_RESULTS_FILE"
  printf '0\n' > "$INSPECT_INDEX_FILE"
}

# Regression for Docker's Restarting state: the old Running-only check returns success here.
set_results 'restarting 4'
if verify_health >/dev/null 2>&1; then
  echo 'Restarting container incorrectly passed container health' >&2
  exit 1
fi
[[ "$(cat "$INSPECT_INDEX_FILE")" -eq 10 ]]

# A stable running state with an unchanged restart count passes after three samples.
set_results 'running 2' 'running 2' 'running 2'
verify_health >/dev/null
[[ "$(cat "$INSPECT_INDEX_FILE")" -eq 3 ]]

# A restart during sampling resets stability; three later unchanged samples are required.
set_results 'running 2' 'running 3' 'running 3' 'running 3' 'running 3'
verify_health >/dev/null
[[ "$(cat "$INSPECT_INDEX_FILE")" -eq 4 ]]

# Repeated restarts must time out even when every sample says Running=true.
set_results 'running 1' 'running 2' 'running 3' 'running 4' 'running 5' \
  'running 6' 'running 7' 'running 8' 'running 9' 'running 10'
if verify_health >/dev/null 2>&1; then
  echo 'Container with changing restart count incorrectly passed health' >&2
  exit 1
fi
[[ "$(cat "$INSPECT_INDEX_FILE")" -eq 10 ]]

# Failed manual actions preserve diagnostics, then stop the restart-policy container.
for action in run_start run_restart; do
  set_results 'restarting 4'
  : > "$ACTION_DOCKER_LOG_FILE"
  printf 'created\n' > "$ACTION_STATE_FILE"
  if ( "$action" ) > "$tmp/$action.out" 2>&1; then
    echo "$action unexpectedly succeeded with a restarting container" >&2
    exit 1
  fi
  [[ "$(cat "$ACTION_STATE_FILE")" == stopped ]]
  action_output="$(cat "$tmp/$action.out")"
  [[ "$action_output" == *'status=restarting'* && "$action_output" == *'exitCode=42'* ]]
  stop_line="$(grep -nFx 'stop fixture' "$ACTION_DOCKER_LOG_FILE" | cut -d: -f1)"
  logs_line="$(grep -n '^logs ' "$ACTION_DOCKER_LOG_FILE" | head -n 1 | cut -d: -f1)"
  [[ -n "$stop_line" && -n "$logs_line" && "$logs_line" -lt "$stop_line" ]]
done

printf 'PASS: stable health, restart rejection, and failed manual start/restart stop after diagnostics\n'
