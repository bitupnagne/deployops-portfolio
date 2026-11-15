#!/usr/bin/env bash
# Real agent parsing/run argument construction with mocked Docker/health commands only.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
tmp="$(mktemp -d -t deploy-container-ports.XXXXXX)"
tmp="$(cd "$tmp" && pwd)"
[[ "$tmp" == */deploy-container-ports.* && "$tmp" != / ]] || exit 1
trap 'rm -rf -- "$tmp"' EXIT
export PORT_TEST_DIR="$tmp"
cat > "$tmp/health" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$@" > "$PORT_TEST_DIR/health-args"
EOF
chmod +x "$tmp/health"

run_agent(){
  local agent="$1"
  shift
  (
    source <(sed 's/\r$//; /^main "\$@"$/d' "$repo/installer/deploy-agent/was/deploy-was-app.sh")
    require_root(){ :; }
    docker_ready(){ :; }
    reload_nginx(){ :; }
    CONFIG_FILE="$PORT_TEST_DIR/missing-config"
    STATE_ROOT="$(mktemp -d "$PORT_TEST_DIR/state.XXXXXX")"
    HEALTHCHECK_SCRIPT="$PORT_TEST_DIR/health"
    docker(){
      printf '%s\n' "$*" >> "$PORT_TEST_DIR/docker-calls"
      case "$1 $2" in
        'container inspect') return 1 ;;
        'image inspect'|'image pull') return 0 ;;
      esac
      if [[ "$1" == run ]]; then
        printf '%s\n' "$@" > "$PORT_TEST_DIR/run-args"
        printf 'fixture-container-id\n'
      fi
    }
    if [[ "$agent" == */web/* ]]; then
      source <(sed 's/\r$//; /^web_main "\$@"$/d' "$agent")
      web_require_root(){ :; }
      web_core(){ main "$@"; }
      web_main deploy --reload-nginx false --image registry.test/app:immutable --container demo-app --health-port 9090 --health-path /health "$@"
    else
      main deploy --image registry.test/app:immutable --container demo-app --health-port 9090 --health-path /health "$@"
    fi
  )
}

published(){
  local -a arguments=() values=()
  local i
  mapfile -t arguments < "$tmp/run-args"
  for (( i=0; i<${#arguments[@]}; i++ )); do
    if [[ "${arguments[$i]}" == -p ]]; then values+=("${arguments[$((i+1))]}"); fi
  done
  printf '%s\n' "${values[*]}"
}

for agent in "$repo/installer/deploy-agent/was/deploy-was-app.sh" "$repo/installer/deploy-agent/web/deploy-web-app.sh"; do
  run_agent "$agent" --publish 9090:9090 --publish 9080:9080 > "$tmp/output"
  [[ "$(published)" == '9090:9090/tcp 9080:9080/tcp' ]]
  [[ "$(cat "$tmp/health-args")" == $'--port\n9090\n--path\n/health' ]]
  run_agent "$agent" --host-port 9090 --container-port 9090 > "$tmp/output"
  [[ "$(published)" == '9090:9090/tcp' ]]
  run_agent "$agent" --publish 1:65535 --publish 65535:1 > "$tmp/output"
  [[ "$(published)" == '1:65535/tcp 65535:1/tcp' ]]

  for invalid in '0:80' '65536:80' '80:0' '80:65536' '127.0.0.1:80:80' '80:80/udp' '80-81:80' '--privileged' '80:80 --network host'; do
    rm -f -- "$tmp/docker-calls"
    if run_agent "$agent" --publish "$invalid" > "$tmp/output" 2>&1; then echo "Accepted invalid binding: $invalid"; exit 1; fi
    # Ownership probes are read-only; invalid bindings must never mutate Docker.
    [[ ! -e "$tmp/docker-calls" ]] || ! grep -Ev '^container inspect ' "$tmp/docker-calls"
  done
  for kind in duplicate mixed missing legacy-range; do
    rm -f -- "$tmp/docker-calls"
    case "$kind" in
      duplicate) args=(--publish 9090:80 --publish 09090:81) ;;
      mixed) args=(--publish 9090:80 --host-port 9080 --container-port 80) ;;
      missing) args=(--publish) ;;
      legacy-range) args=(--host-port 70000 --container-port 80) ;;
    esac
    if run_agent "$agent" "${args[@]}" > "$tmp/output" 2>&1; then echo "Accepted $kind ports"; exit 1; fi
    [[ ! -e "$tmp/docker-calls" ]] || ! grep -Ev '^container inspect ' "$tmp/docker-calls"
  done
done
printf 'PASS: WAS/WEB multi-port TCP, unchanged health, legacy mapping, bounds, injection/duplicate rejection before Docker mutation\n'
