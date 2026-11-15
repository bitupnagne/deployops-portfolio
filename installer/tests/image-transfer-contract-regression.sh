#!/usr/bin/env bash
# Real WAS/WEB agent functions; Docker, health, systemctl and nginx are never invoked.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
tmp="$(mktemp -d -t deploy-image-contract.XXXXXX)"
tmp="$(cd "$tmp" && pwd)"
[[ "$tmp" == */deploy-image-contract.* && "$tmp" != / ]] || exit 1
trap 'rm -rf -- "$tmp"' EXIT
export IMAGE_TEST_DIR="$tmp"
config_id="sha256:$(printf 'b%.0s' {1..64})"
reference="registry.test/app@sha256:$(printf 'a%.0s' {1..64})"
cat > "$tmp/health" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
chmod +x "$tmp/health"

reset_fixture(){
  rm -f -- "$tmp/docker-calls" "$tmp/run-image" "$tmp/private-copy"
  printf 'verified archive bytes\n' > "$tmp/input.tar"
  checksum="$(sha256sum "$tmp/input.tar")"
  checksum="${checksum%% *}"
}

run_agent(){
  local agent="$1" scenario="$2"
  shift 2
  (
    source <(sed 's/\r$//; /^main "\$@"$/d' "$repo/installer/deploy-agent/was/deploy-was-app.sh")
    require_root(){ :; }
    docker_ready(){ :; }
    reload_nginx(){ :; }
    CONFIG_FILE="$IMAGE_TEST_DIR/missing-config"
    STATE_ROOT="$(mktemp -d "$IMAGE_TEST_DIR/state.XXXXXX")"
    HEALTHCHECK_SCRIPT="$IMAGE_TEST_DIR/health"
    cp(){
      command cp "$@"
      if [[ "$scenario" == source-race ]]; then printf 'replaced source\n' > "$IMAGE_TEST_DIR/input.tar"; fi
    }
    sha256sum(){
      if [[ "$scenario" == checksum-read-failure ]]; then echo 'mock read error' >&2; return 1; fi
      command sha256sum "$@"
    }
    docker(){
      printf '%s\n' "$*" >> "$IMAGE_TEST_DIR/docker-calls"
      if [[ "$1 $2" == 'container inspect' ]]; then return 1; fi
      if [[ "$1" == load ]]; then
        printf '%s\n' "$3" > "$IMAGE_TEST_DIR/private-copy"
        [[ "$3" != "$IMAGE_TEST_DIR/input.tar" && "$(stat -c %a "$(dirname "$3")")" == 700 ]]
        [[ "$(stat -c %a "$3")" == 600 ]]
        [[ "$(cat "$3")" == 'verified archive bytes' ]]
        [[ "$scenario" != load-failure ]] || return 1
        echo "Loaded image ID: $config_id" # No RepoTags or RepoDigests restored.
      elif [[ "$1 $2" == 'image inspect' && "${3:-}" == --format ]]; then
        [[ "${5:-}" == "$config_id" ]] || return 1
        [[ "$scenario" != missing-id ]] || return 1
        if [[ "$scenario" == wrong-id ]]; then printf 'sha256:%064d\n' 0; else printf '%s\n' "$config_id"; fi
      elif [[ "$1 $2" == 'image inspect' ]]; then
        [[ "$scenario" == direct-tag ]] || return 1
      elif [[ "$1" == run ]]; then
        printf '%s\n' "${@: -1}" > "$IMAGE_TEST_DIR/run-image"
        echo 'fixture-container-id'
      fi
      return 0
    }
    if [[ "$agent" == */web/* ]]; then
      source <(sed 's/\r$//; /^web_main "\$@"$/d' "$agent")
      web_require_root(){ :; }
      web_core(){
        # Production executes a separate core process. Preserve its errexit semantics:
        # an in-process function inside the wrapper's `if` would disable errexit.
        bash <(
          sed 's/\r$//; /^main "\$@"$/d' "$repo/installer/deploy-agent/was/deploy-was-app.sh"
          declare -f require_root docker_ready cp sha256sum docker
          declare -p CONFIG_FILE STATE_ROOT HEALTHCHECK_SCRIPT IMAGE_TEST_DIR scenario config_id
          printf 'main "$@"\n'
        ) "$@"
      }
      web_main deploy --reload-nginx false --image "$reference" --container existing-app "$@"
    else
      main deploy --image "$reference" --container existing-app "$@"
    fi
  )
}

assert_no_container_change(){
  [[ ! -f "$tmp/run-image" ]]
  if [[ -f "$tmp/docker-calls" ]]; then
    ! grep -Eq '^(run |stop |rm |image pull |login )' "$tmp/docker-calls"
  fi
}

for agent in "$repo/installer/deploy-agent/was/deploy-was-app.sh" "$repo/installer/deploy-agent/web/deploy-web-app.sh"; do
  for scenario in success source-race; do
    reset_fixture
    run_agent "$agent" "$scenario" --image-tar "$tmp/input.tar" --image-id "$config_id" --image-sha256 "$checksum" --memory 512m > "$tmp/output"
    [[ "$(cat "$tmp/run-image")" == "$config_id" ]]
    [[ ! -e "$tmp/input.tar" && ! -e "$(cat "$tmp/private-copy")" ]]
    ! grep -Eq '^(image pull |login )' "$tmp/docker-calls"
    grep -F -- '--memory 512m --memory-swap 512m' "$tmp/docker-calls" >/dev/null
  done

  for scenario in checksum-mismatch checksum-read-failure missing-id wrong-id load-failure missing-metadata malformed-id orphan-metadata; do
    reset_fixture
    args=(--image-tar "$tmp/input.tar" --image-id "$config_id" --image-sha256 "$checksum")
    case "$scenario" in
      checksum-mismatch) args[5]="$(printf '0%.0s' {1..64})" ;;
      missing-metadata) args=(--image-tar "$tmp/input.tar") ;;
      malformed-id) args[3]='sha256:bad --privileged' ;;
      orphan-metadata) args=(--image-id "$config_id" --image-sha256 "$checksum") ;;
    esac
    # Do not place run_agent in an if/! list: Bash would disable errexit inside real agent functions.
    set +e
    run_agent "$agent" "$scenario" "${args[@]}" > "$tmp/output" 2>&1
    status=$?
    set -e
    [[ "$status" -ne 0 ]] || { echo "Accepted invalid transfer: $agent $scenario"; exit 1; }
    assert_no_container_change
    [[ -e "$tmp/input.tar" ]]
    if [[ -f "$tmp/private-copy" ]]; then [[ ! -e "$(cat "$tmp/private-copy")" ]]; fi
  done

  reset_fixture
  run_agent "$agent" direct-tag --image registry.test/app:release > "$tmp/output"
  [[ "$(cat "$tmp/run-image")" == registry.test/app:release ]]
  reset_fixture
  run_agent "$agent" success --image registry.test/app:release --image-tar "$tmp/input.tar" --image-id "$config_id" --image-sha256 "$checksum" > "$tmp/output"
  [[ "$(cat "$tmp/run-image")" == "$config_id" ]]
done
printf 'PASS: WAS/WEB digest and tag archives run by verified config ID; SHA/read/load/identity/metadata failures preserve existing container; private copy prevents source replacement; direct tags preserved\n'
