#!/bin/bash
set -euo pipefail

CONFIG_FILE="/usr/local/deploy/conf/was-agent.env"
case "${DEPLOY_CONTAINER_ROLE:-was}" in
  was) ;;
  web) CONFIG_FILE="/usr/local/deploy/conf/web-agent.env" ;;
  *) echo "Invalid container agent role." >&2; exit 1 ;;
esac
HEALTHCHECK_SCRIPT="/usr/local/deploy/bin/local-healthcheck.sh"
ACTION=""
IMAGE=""
CONTAINER=""
LINES="200"
NETWORK=""
HOST_PORT=""
CONTAINER_PORT=""
PUBLISH_PORTS=()
ENV_FILE=""
MOUNT_SPEC=""
MOUNT_SPEC_SHA256=""
STORAGE_HELPER="/usr/local/deploy/bin/deploy-storage.sh"
MANAGED_PROJECT=""
MANAGED_COMPONENT="0"
MANAGED_ENVIRONMENT=""
PERSISTENT_LOGS=false
TMP_MIB=0
STOP_GRACE_SECONDS=30
DEPLOYMENT_ID=""
DRAIN_TIMEOUT_SECONDS=30
STATE_ROOT="/var/lib/deploy-system/container-recovery"
HEALTH_PORT=""
HEALTH_PATH=""
HEALTH_MODE="http"
REGISTRY_URL=""
REGISTRY_USERNAME=""
REGISTRY_PASSWORD=""
IMAGE_TAR=""
IMAGE_ID=""
IMAGE_SHA256=""
MEMORY_LIMIT=""
CONTAINER_USER=""
PRIVILEGED="false"
CAP_ADD=""
READ_ONLY_ROOTFS="false"
FAIL_LOG_LINES="${FAIL_LOG_LINES:-200}"

usage(){
  cat <<'EOF'
Usage:
  관리 프로젝트 명령: --managed-project PROJECT_ID (소유 프로젝트 label 확인)
  deploy/start/stop/restart: [--stop-grace-seconds SEC] (default 30; integer 1..300)
  capabilities: STOP_GRACE_VERSION=1, ROLLBACK_VERSION=1, QUIESCE_VERSION=1, PROJECT_OWNERSHIP_VERSION=1
  deploy-was-app.sh deploy --image IMAGE --container NAME [--image-tar PATH --image-id sha256:CONFIG --image-sha256 SHA256] [--network NAME]
                           [--host-port PORT --container-port PORT] [--env-file PATH]
                           [--publish HOST:CONTAINER ...] (TCP, legacy port flags와 함께 사용 불가)
                           [--health-mode http|container|none] [--health-port PORT] [--health-path PATH] [--memory SIZE] [--user UID:GID]
                           [--cap-add CAP[,CAP...]] [--privileged true|false] [--read-only true|false]
  deploy-was-app.sh status  --container NAME
  deploy-was-app.sh logs    --container NAME [--lines N]
  deploy-was-app.sh start   --container NAME [--health-mode http|container|none] [--health-port PORT] [--health-path PATH]
  deploy-was-app.sh stop    --container NAME
  deploy-was-app.sh restart --container NAME [--health-mode http|container|none] [--health-port PORT] [--health-path PATH]
  deploy-was-app.sh health  --container NAME [--health-mode http|container|none] [--health-port PORT] [--health-path PATH]
  deploy-was-app.sh probe   --container NAME [--health-mode http|container|none] [--health-port PORT] [--health-path PATH]
  deploy-was-app.sh quiesce --container NAME --health-port HOST_SERVICE_PORT [--drain-timeout-seconds SEC]
  deploy-was-app.sh restore --container NAME --deployment-id ORIGINAL_DEPLOYMENT_ID

start/stop 은 재배포가 아니다. 이미 있는 컨테이너를 그대로 껐다 켜므로 버전이 바뀌지 않는다.

이미지 전달 방식 두 가지:
  registry  --image 만 주면 레지스트리에서 pull 한다(대상 서버 → 레지스트리 연결 필요).
  push      --image-tar, --image-id, --image-sha256을 함께 주면 tar 검증/load 후 config ID로 실행한다.
            --image는 승인된 registry 참조이며 config ID와 다르다. 검증 성공 후 tar를 지운다.
EOF
}

safe_image(){
  [[ "$1" =~ ^[A-Za-z0-9._/@:-]+$ ]]
}

safe_name(){
  [[ "$1" =~ ^[A-Za-z0-9._-]+$ ]]
}

safe_path(){
  [[ "$1" =~ ^/[A-Za-z0-9._/+:-]+$ ]]
}

require_root(){
  [[ "${EUID}" -eq 0 ]] || { echo "Run as root."; exit 1; }
}

load_defaults(){
  if [[ -f "$CONFIG_FILE" ]]; then
    # shellcheck disable=SC1090
    source "$CONFIG_FILE"
  fi
  # Host configuration may supply registry credentials, never a remote engine.
  unset DOCKER_CONTEXT DOCKER_TLS_VERIFY DOCKER_TLS DOCKER_CERT_PATH
  export DOCKER_HOST=unix:///var/run/docker.sock
  HEALTH_PORT="${HEALTH_PORT:-${DEFAULT_HEALTH_PORT:-8080}}"
  HEALTH_PATH="${HEALTH_PATH:-${DEFAULT_HEALTH_PATH:-/actuator/health}}"
  HEALTH_MODE="${HEALTH_MODE:-${DEFAULT_HEALTH_MODE:-http}}"
  MEMORY_LIMIT="${MEMORY_LIMIT:-${DEFAULT_MEMORY_LIMIT:-}}"
}

parse_args(){
  [[ "$#" -gt 0 ]] || { usage; exit 1; }
  ACTION="$1"
  shift
  while [[ "$#" -gt 0 ]]; do
    case "$1" in
      --image) IMAGE="$2"; shift 2 ;;
      --image-tar) IMAGE_TAR="$2"; shift 2 ;;
      --image-id) IMAGE_ID="$2"; shift 2 ;;
      --image-sha256) IMAGE_SHA256="$2"; shift 2 ;;
      --container) CONTAINER="$2"; shift 2 ;;
      --lines) LINES="$2"; shift 2 ;;
      --network) NETWORK="$2"; shift 2 ;;
      --host-port) HOST_PORT="$2"; shift 2 ;;
      --container-port) CONTAINER_PORT="$2"; shift 2 ;;
      --publish) [[ $# -ge 2 ]] || { echo "--publish requires HOST:CONTAINER"; exit 1; }; PUBLISH_PORTS+=("$2"); shift 2 ;;
      --env-file) ENV_FILE="$2"; shift 2 ;;
      --mount-spec) MOUNT_SPEC="$2"; shift 2 ;;
      --mount-spec-sha256) MOUNT_SPEC_SHA256="$2"; shift 2 ;;
      --managed-project) MANAGED_PROJECT="$2"; shift 2 ;;
      --managed-component) MANAGED_COMPONENT="$2"; shift 2 ;;
      --managed-environment) MANAGED_ENVIRONMENT="$2"; shift 2 ;;
      --persistent-logs) PERSISTENT_LOGS=true; shift ;;
      --stop-grace-seconds) [[ $# -ge 2 ]] || { echo "종료 유예 시간 값이 필요합니다."; exit 1; }; STOP_GRACE_SECONDS="$2"; shift 2 ;;
      --deployment-id) [[ $# -ge 2 ]] || { echo "배포 식별자가 필요합니다."; exit 1; }; DEPLOYMENT_ID="$2"; shift 2 ;;
      --drain-timeout-seconds) [[ $# -ge 2 ]] || { echo "연결 배출 대기 시간이 필요합니다."; exit 1; }; DRAIN_TIMEOUT_SECONDS="$2"; shift 2 ;;
      --tmp-mib) TMP_MIB="$2"; shift 2 ;;
      --health-port) HEALTH_PORT="$2"; shift 2 ;;
      --health-path) HEALTH_PATH="$2"; shift 2 ;;
      --health-mode) HEALTH_MODE="$2"; shift 2 ;;
      --memory) MEMORY_LIMIT="$2"; shift 2 ;;
      --user) CONTAINER_USER="$2"; shift 2 ;;
      --cap-add) CAP_ADD="$2"; shift 2 ;;
      --privileged) PRIVILEGED="$2"; shift 2 ;;
      --read-only) READ_ONLY_ROOTFS="$2"; shift 2 ;;
      --fail-log-lines) FAIL_LOG_LINES="$2"; shift 2 ;;
      -h|--help) usage; exit 0 ;;
      *) echo "Unknown argument: $1"; usage; exit 1 ;;
    esac
  done
}

docker_ready(){
  command -v docker >/dev/null 2>&1 || { echo "docker is required."; exit 1; }
  systemctl is-active --quiet docker || { echo "docker service is not active."; exit 1; }
}

registry_login(){
  if [[ -z "$REGISTRY_URL" && -z "$REGISTRY_USERNAME" && -z "$REGISTRY_PASSWORD" ]]; then
    return 0
  fi
  [[ -n "$REGISTRY_URL" && -n "$REGISTRY_USERNAME" && -n "$REGISTRY_PASSWORD" ]] || {
    echo "Registry login requires REGISTRY_URL, REGISTRY_USERNAME, and REGISTRY_PASSWORD."
    exit 1
  }
  printf '%s' "$REGISTRY_PASSWORD" | docker login "$REGISTRY_URL" --username "$REGISTRY_USERNAME" --password-stdin >/dev/null
  echo "Registry login completed: $REGISTRY_URL"
}

container_exists(){
  docker container inspect "$1" >/dev/null 2>&1
}

validate_project_identity(){
  if [[ -n "$MANAGED_PROJECT" && ! "$MANAGED_PROJECT" =~ ^[1-9][0-9]{0,18}$ ]]; then
    echo "관리 프로젝트 ID가 올바르지 않습니다."; return 1
  fi
  if [[ "$CONTAINER" =~ ^deploy-project-([1-9][0-9]*)$ ]]; then
    [[ "$MANAGED_PROJECT" == "${BASH_REMATCH[1]}" ]] \
      || { echo "고유 컨테이너 이름과 관리 프로젝트 ID가 다릅니다."; return 1; }
  fi
}

assert_container_owner(){
  local name="$1" owner
  validate_project_identity || return 1
  owner="$(docker container inspect --format '{{index .Config.Labels "com.portfolio.deploy.project"}}' "$name")" \
    || { echo "컨테이너 소유 프로젝트를 확인하지 못했습니다."; return 1; }
  if [[ -n "$owner" && "$owner" != '<no value>' ]]; then
    [[ -n "$MANAGED_PROJECT" && "$owner" == "$MANAGED_PROJECT" ]] \
      || { echo "다른 프로젝트 소유의 컨테이너입니다. 변경하지 않았습니다."; return 1; }
  elif [[ "$CONTAINER" =~ ^deploy-project-[1-9][0-9]*$ ]]; then
    echo "고유 컨테이너의 소유 label이 없습니다. 자동 인수하지 않습니다. 기존 컨테이너를 확인하여 명시적으로 이관하세요."
    return 1
  fi
  # Legacy names without ownership labels remain usable after the Java mapping collision check.
}

verify_health(){
  case "$HEALTH_MODE" in
    http)
      "$HEALTHCHECK_SCRIPT" --port "$HEALTH_PORT" --path "$HEALTH_PATH"
      ;;
    container)
      [[ -n "$CONTAINER" ]] || { echo "--container is required for container health mode."; return 1; }
      local stable=0 state restart_count previous_restart_count="" attempt
      for attempt in {1..10}; do
        # Running can remain true while Docker is repeatedly restarting a failed process.
        # Require a genuinely running state and an unchanged restart count for three samples.
        read -r state restart_count < <(
          docker container inspect --format '{{.State.Status}} {{.RestartCount}}' "$CONTAINER" 2>/dev/null || true
        )
        if [[ "$state" == "running" && "$restart_count" =~ ^[0-9]+$ ]]; then
          if [[ "$restart_count" == "$previous_restart_count" ]]; then
            stable=$((stable + 1))
          else
            stable=1
            previous_restart_count="$restart_count"
          fi
          if (( stable >= 3 )); then
            echo "Container running check passed: $CONTAINER"
            return 0
          fi
        else
          stable=0
          previous_restart_count=""
        fi
        if (( attempt < 10 )); then sleep 1; fi
      done
      echo "Container running check failed: $CONTAINER"
      return 1
      ;;
    none)
      echo "Health check skipped by explicit mode: none"
      ;;
    *)
      echo "Invalid --health-mode: $HEALTH_MODE (expected http, container, or none)"
      return 1
      ;;
  esac
}

append_publish_args(){
  local -a bindings=("${PUBLISH_PORTS[@]}")
  local binding host container
  local -A seen=()
  if [[ -n "$HOST_PORT" || -n "$CONTAINER_PORT" ]]; then
    [[ ${#bindings[@]} -eq 0 ]] || { echo "Do not combine --publish with legacy port flags."; exit 1; }
    [[ -n "$HOST_PORT" && -n "$CONTAINER_PORT" ]] || { echo "Both --host-port and --container-port are required together."; exit 1; }
    bindings+=("${HOST_PORT}:${CONTAINER_PORT}")
  fi
  for binding in "${bindings[@]}"; do
    [[ "$binding" =~ ^([0-9]{1,5}):([0-9]{1,5})$ ]] || { echo "Invalid TCP publish binding."; exit 1; }
    host=$((10#${BASH_REMATCH[1]}))
    container=$((10#${BASH_REMATCH[2]}))
    (( host >= 1 && host <= 65535 && container >= 1 && container <= 65535 )) || { echo "Publish ports must be 1..65535."; exit 1; }
    [[ -z "${seen[$host]:-}" ]] || { echo "Duplicate host publish port: $host"; exit 1; }
    seen[$host]=1
    run_args+=(-p "${host}:${container}/tcp")
  done
}

# --image는 승인 registry 참조, IMAGE_ID는 그 참조를 해석한 실행 config ID이다.
# 사용자 소유 전송 파일의 교체 경쟁을 막으려고 root 전용 복사본을 검증하고 load한다.
load_image_archive(){
  (
    [[ "$IMAGE_ID" =~ ^sha256:[a-f0-9]{64}$ ]] || { echo "A valid --image-id is required with --image-tar."; exit 1; }
    [[ "$IMAGE_SHA256" =~ ^[a-f0-9]{64}$ ]] || { echo "A valid --image-sha256 is required with --image-tar."; exit 1; }
    safe_path "$IMAGE_TAR" || { echo "Invalid image tar path: $IMAGE_TAR"; exit 1; }
    [[ -f "$IMAGE_TAR" ]] || { echo "Image tar not found: $IMAGE_TAR"; exit 1; }
    umask 077
    local archive_dir archive_sha loaded_id
    archive_dir="$(mktemp -d /var/tmp/deploy-image-verify.XXXXXX)" || exit 1
    trap 'rm -f -- "$archive_dir/image.tar"; rmdir -- "$archive_dir"' EXIT
    cp -- "$IMAGE_TAR" "$archive_dir/image.tar" || { echo "Image archive copy failed."; exit 1; }
    chmod 600 "$archive_dir/image.tar" || exit 1
    archive_sha="$(sha256sum -- "$archive_dir/image.tar")" || { echo "Image archive checksum read failed."; exit 1; }
    archive_sha="${archive_sha%% *}"
    [[ "$archive_sha" == "$IMAGE_SHA256" ]] || { echo "Image archive checksum mismatch."; exit 1; }
    echo "Verified archive sha256=$IMAGE_SHA256; approved reference=$IMAGE; expected config ID=$IMAGE_ID"
    docker load -i "$archive_dir/image.tar" || { echo "docker load failed."; exit 1; }
    loaded_id="$(docker image inspect --format '{{.Id}}' "$IMAGE_ID")" || { echo "Loaded config ID is missing: $IMAGE_ID"; exit 1; }
    [[ "$loaded_id" == "$IMAGE_ID" ]] || { echo "Loaded image config ID mismatch."; exit 1; }
    echo "Verified loaded config ID=$loaded_id"
  )
}

recovery_paths(){
  [[ -n "$CONTAINER" && ${#CONTAINER} -le 100 ]] && safe_name "$CONTAINER" || { echo "복구 대상 컨테이너 이름이 올바르지 않습니다."; return 1; }
  [[ ! -L "$STATE_ROOT" ]] || { echo "복구 상태 경로가 심볼릭 링크입니다."; return 1; }
  install -d -m 700 -o root -g root "$STATE_ROOT" || return 1
  RECOVERY_FILE="$STATE_ROOT/$CONTAINER.state"
  PREVIOUS_CONTAINER="${CONTAINER}.deploy-previous"
  [[ ! -L "$RECOVERY_FILE" ]] || { echo "복구 상태 파일이 심볼릭 링크입니다."; return 1; }
}

write_recovery_state(){
  local candidate
  candidate="$(mktemp "$STATE_ROOT/.state.XXXXXXXX")" || return 1
  if ! printf '%s\n%s\n%s\n%s\n%s\n%s\n%s\n' "$DEPLOYMENT_ID" "$PREVIOUS_ID" "$PREVIOUS_RUNNING" "${CURRENT_ID:-}" \
      "${PREVIOUS_HEALTH_MODE:-container}" "${PREVIOUS_HEALTH_PORT:-}" "${PREVIOUS_HEALTH_PATH:-}" > "$candidate" \
      || ! chmod 600 "$candidate" || ! sync -f "$candidate" || ! mv -f -- "$candidate" "$RECOVERY_FILE"; then
    rm -f -- "$candidate"
    return 1
  fi
  sync -f "$STATE_ROOT"
}

valid_health_contract(){
  [[ "$1" == http || "$1" == container || "$1" == none ]] || return 1
  [[ "$2" != *$'\n'* && "$2" != *$'\r'* && "$3" != *$'\n'* && "$3" != *$'\r'* ]] || return 1
  if [[ "$1" == http ]]; then
    [[ "$2" =~ ^[0-9]{1,5}$ ]] && (( 10#$2 >= 1 && 10#$2 <= 65535 )) && [[ "$3" == /* ]] || return 1
  fi
}

read_previous_health(){
  PREVIOUS_HEALTH_MODE="$(docker container inspect --format '{{index .Config.Labels "com.portfolio.deploy.health-mode"}}' "$CONTAINER")" || return 1
  PREVIOUS_HEALTH_PORT="$(docker container inspect --format '{{index .Config.Labels "com.portfolio.deploy.health-port"}}' "$CONTAINER")" || return 1
  PREVIOUS_HEALTH_PATH="$(docker container inspect --format '{{index .Config.Labels "com.portfolio.deploy.health-path"}}' "$CONTAINER")" || return 1
  if [[ -z "$PREVIOUS_HEALTH_MODE" || "$PREVIOUS_HEALTH_MODE" == '<no value>' ]]; then
    # Legacy containers did not retain their HTTP contract. Do not check them against a new port.
    PREVIOUS_HEALTH_MODE=container PREVIOUS_HEALTH_PORT="" PREVIOUS_HEALTH_PATH=""
  fi
  valid_health_contract "$PREVIOUS_HEALTH_MODE" "$PREVIOUS_HEALTH_PORT" "$PREVIOUS_HEALTH_PATH" \
    || { echo "직전 컨테이너의 상태 검사 설정이 올바르지 않습니다."; return 1; }
}

verify_restored_health(){
  local HEALTH_MODE="$1" HEALTH_PORT="$2" HEALTH_PATH="$3"
  echo "RESTORE_HEALTH_MODE=$HEALTH_MODE"
  verify_health
}

# No helper call is needed for legacy/unmounted containers. Mounted instances must
# always revalidate their original immutable specification before starting.
storage_label(){
  docker container inspect --format '{{index .Config.Labels "com.portfolio.deploy.mount-spec"}}' "$1"
}

check_container_storage(){
  local spec
  spec="$(storage_label "$1")" || return 1
  [[ -z "$spec" || "$spec" == '<no value>' ]] && return 0
  "$STORAGE_HELPER" check-container --container "$1" >/dev/null
}

storage_desired(){
  local spec
  spec="$(storage_label "$1")" || return 1
  [[ -z "$spec" || "$spec" == '<no value>' ]] && return 0
  "$STORAGE_HELPER" desired --container "$1" --enabled "$2" >/dev/null
}

restore_previous(){
  recovery_paths || return 1
  validate_project_identity || return 1
  if container_exists "$CONTAINER"; then assert_container_owner "$CONTAINER" || return 1; fi
  if container_exists "$PREVIOUS_CONTAINER"; then assert_container_owner "$PREVIOUS_CONTAINER" || return 1; fi
  [[ -n "$DEPLOYMENT_ID" ]] && safe_name "$DEPLOYMENT_ID" || { echo "복원에는 배포 식별자가 필요합니다."; return 1; }
  [[ -f "$RECOVERY_FILE" ]] || { echo "직전 컨테이너 복구 정보가 없습니다."; return 1; }
  local token previous_id previous_running current_id actual_id actual_token previous_actual
  local previous_health_mode="" previous_health_port="" previous_health_path=""
  { IFS= read -r token; IFS= read -r previous_id; IFS= read -r previous_running; IFS= read -r current_id
    IFS= read -r previous_health_mode || true; IFS= read -r previous_health_port || true; IFS= read -r previous_health_path || true; } < "$RECOVERY_FILE"
  [[ "$token" == "$DEPLOYMENT_ID" ]] || { echo "다른 배포 세대의 복구 요청입니다."; return 1; }
  [[ "$previous_running" == true || "$previous_running" == false ]] || { echo "복구 상태가 손상되었습니다."; return 1; }
  previous_health_mode="${previous_health_mode:-container}"
  valid_health_contract "$previous_health_mode" "$previous_health_port" "$previous_health_path" || return 1
  # Only the same invocation may supply the authoritative CID from a successful docker run
  # when persisting that CID failed. An external restore cannot guess it from a label.
  if [[ -z "$current_id" && -n "${1:-}" ]]; then current_id="$1"; fi
  if container_exists "$CONTAINER"; then
    actual_id="$(docker container inspect --format '{{.Id}}' "$CONTAINER")" || return 1
    if [[ "$actual_id" == "$previous_id" ]]; then
      # Handles interruption before rename or after rename-back/start failed.
      if [[ "$previous_running" == true ]]; then
        check_container_storage "$CONTAINER" || return 1
        docker start "$CONTAINER" >/dev/null || return 1
        verify_restored_health "$previous_health_mode" "$previous_health_port" "$previous_health_path" || return 1
      fi
      storage_desired "$CONTAINER" "$previous_running" || return 1
      rm -f -- "$RECOVERY_FILE"
      echo "RESTORED_CONTAINER_ID=$previous_id"
      echo "RESTORE_GENERATION=$token"
      return 0
    fi
    actual_token="$(docker container inspect --format '{{index .Config.Labels "com.portfolio.deploy.generation"}}' "$CONTAINER")" || return 1
    [[ -n "$current_id" && "$actual_id" == "$current_id" && "$actual_token" == "$token" ]] || { echo "현재 컨테이너가 복구 대상 배포와 다릅니다."; return 1; }
  fi
  # Validate the preserved instance before touching the currently running one.
  if [[ "$previous_id" != ABSENT ]]; then
    previous_actual="$(docker container inspect --format '{{.Id}}' "$PREVIOUS_CONTAINER")" || { echo "보존한 컨테이너가 없습니다."; return 1; }
    [[ "$previous_actual" == "$previous_id" ]] || { echo "보존한 컨테이너의 신원이 다릅니다."; return 1; }
    check_container_storage "$PREVIOUS_CONTAINER" || return 1
  fi
  if container_exists "$CONTAINER"; then
    stop_container || return $?
    docker rm "$CONTAINER" >/dev/null || return 1
  fi
  if [[ "$previous_id" == ABSENT ]]; then
    echo "RESTORED_ABSENT=true"
  else
    docker rename "$PREVIOUS_CONTAINER" "$CONTAINER" || return 1
    if [[ "$previous_running" == true ]]; then
      docker start "$CONTAINER" >/dev/null || return 1
      verify_restored_health "$previous_health_mode" "$previous_health_port" "$previous_health_path" \
        || { echo "직전 컨테이너 복원 후 상태 확인에 실패했습니다."; return 1; }
    fi
    storage_desired "$CONTAINER" "$previous_running" || return 1
    echo "RESTORED_CONTAINER_ID=$previous_id"
  fi
  rm -f -- "$RECOVERY_FILE"
  echo "RESTORE_GENERATION=$token"
}

run_deploy(){
  local restart_policy=unless-stopped
  [[ -z "$MOUNT_SPEC" ]] || restart_policy=no
  local run_args=(docker run -d --name "$CONTAINER" --restart "$restart_policy" --stop-timeout "$STOP_GRACE_SECONDS")
  local run_image="$IMAGE"

  [[ -n "$IMAGE" ]] || { echo "--image is required."; exit 1; }
  [[ -n "$CONTAINER" ]] || { echo "--container is required."; exit 1; }
  safe_image "$IMAGE" || { echo "Invalid image: $IMAGE"; exit 1; }
  safe_name "$CONTAINER" || { echo "Invalid container name: $CONTAINER"; exit 1; }
  recovery_paths || return 1
  validate_project_identity || return 1
  if container_exists "$CONTAINER"; then assert_container_owner "$CONTAINER" || return 1; fi
  if container_exists "$PREVIOUS_CONTAINER"; then assert_container_owner "$PREVIOUS_CONTAINER" || return 1; fi
  DEPLOYMENT_ID="${DEPLOYMENT_ID:-manual-$(date +%s)-$$}"
  [[ ${#DEPLOYMENT_ID} -le 100 ]] && safe_name "$DEPLOYMENT_ID" || { echo "배포 식별자가 올바르지 않습니다."; return 1; }
  valid_health_contract "$HEALTH_MODE" "$HEALTH_PORT" "$HEALTH_PATH" || { echo "상태 검사 설정이 올바르지 않습니다."; return 1; }
  run_args+=(--label "com.portfolio.deploy.generation=$DEPLOYMENT_ID"
    --label "com.portfolio.deploy.health-mode=$HEALTH_MODE"
    --label "com.portfolio.deploy.health-port=$HEALTH_PORT"
    --label "com.portfolio.deploy.health-path=$HEALTH_PATH")
  [[ -z "$MANAGED_PROJECT" ]] || run_args+=(--label "com.portfolio.deploy.project=$MANAGED_PROJECT")
  [[ "$PRIVILEGED" == true || "$PRIVILEGED" == false ]] || { echo "Invalid --privileged"; exit 1; }
  [[ "$READ_ONLY_ROOTFS" == true || "$READ_ONLY_ROOTFS" == false ]] || { echo "Invalid --read-only"; exit 1; }
  if [[ -n "$CONTAINER_USER" ]]; then
    [[ "$CONTAINER_USER" =~ ^[0-9]+(:[0-9]+)?$ ]] || { echo "--user must be numeric UID[:GID]."; exit 1; }
    run_args+=(--user "$CONTAINER_USER")
  fi
  [[ "$PRIVILEGED" == true ]] && run_args+=(--privileged)
  if [[ -n "$MOUNT_SPEC" ]]; then
    [[ "$PRIVILEGED" == false && -z "$CAP_ADD" ]] || { echo "Bind mounts prohibit privileged/cap-add"; return 1; }
    local mount_args mount_arg
    mount_args="$("$STORAGE_HELPER" arguments --spec "$MOUNT_SPEC" --sha256 "$MOUNT_SPEC_SHA256" --project "$MANAGED_PROJECT" \
      --component "$MANAGED_COMPONENT" --environment "$MANAGED_ENVIRONMENT" --container "$CONTAINER" --user "$CONTAINER_USER")" || return 1
    [[ -n "$mount_args" ]] || { echo "Empty storage specification"; return 1; }
    while IFS= read -r mount_arg; do run_args+=(--mount "$mount_arg"); done <<< "$mount_args"
    run_args+=(--label "com.portfolio.deploy.mount-spec=$MOUNT_SPEC")
  fi
  if [[ "$PERSISTENT_LOGS" == true || "$TMP_MIB" != 0 ]]; then
    [[ "$MANAGED_PROJECT" =~ ^[1-9][0-9]{0,17}$ && "$MANAGED_COMPONENT" =~ ^(0|[1-9][0-9]{0,17})$ && "$MANAGED_ENVIRONMENT" =~ ^(TEST|PROD)$ ]] || { echo "Invalid managed runtime scope"; exit 1; }
    [[ "$CONTAINER_USER" =~ ^[1-9][0-9]{0,9}:[1-9][0-9]{0,9}$ && "$PRIVILEGED" == false && -z "$CAP_ADD" ]] || { echo "Managed storage requires numeric nonroot UID:GID without privileged/cap-add"; exit 1; }
    local runtime_uid="${CONTAINER_USER%%:*}" runtime_gid="${CONTAINER_USER##*:}"
    [[ "$TMP_MIB" =~ ^[0-9]{1,3}$ ]] && (( 10#$TMP_MIB == 0 || (10#$TMP_MIB >= 16 && 10#$TMP_MIB <= 512) )) || { echo "Invalid tmpfs size"; exit 1; }
    if [[ "$PERSISTENT_LOGS" == true ]]; then
      /usr/local/deploy/bin/deploy-runtime.sh volume --project "$MANAGED_PROJECT" --environment "$MANAGED_ENVIRONMENT" --component "$MANAGED_COMPONENT" --uid "$runtime_uid" --gid "$runtime_gid" || { echo "Managed log volume refused"; exit 1; }
      local log_volume="deploy-p${MANAGED_PROJECT}-c${MANAGED_COMPONENT}-${MANAGED_ENVIRONMENT,,}-logs"
      run_args+=(--mount "type=volume,source=$log_volume,target=/app/logs,volume-nocopy")
    fi
    if [[ "$TMP_MIB" != 0 ]]; then
      [[ "$TMP_MIB" =~ ^[0-9]{1,3}$ ]] && (( 10#$TMP_MIB >= 16 && 10#$TMP_MIB <= 512 )) || { echo "Invalid tmpfs size"; exit 1; }
      run_args+=(--tmpfs "/tmp:rw,nosuid,nodev,noexec,size=${TMP_MIB}m,mode=0700,uid=${runtime_uid},gid=${runtime_gid}")
    fi
  fi
  [[ "$READ_ONLY_ROOTFS" == true ]] && run_args+=(--read-only)
  if [[ -n "$CAP_ADD" ]]; then
    IFS=',' read -ra caps <<< "$CAP_ADD"
    for cap in "${caps[@]}"; do [[ "$cap" =~ ^[A-Za-z0-9_+-]+$ ]] || { echo "Invalid capability: $cap"; exit 1; }; run_args+=(--cap-add "$cap"); done
  fi

  # 메모리 상한이 없으면 컨테이너 안 JVM 이 cgroup 이 아니라 호스트 전체 메모리를 보고
  # MaxRAMPercentage(기본 25%)로 힙을 잡는다. 16GB 노드면 컨테이너 하나가 4GB 를 잡으려 들고,
  # 서비스를 여러 개 올리면 합계가 물리 메모리를 넘겨 커널 OOM killer 가 "다른" 컨테이너를 죽인다.
  # 상한을 걸면 JVM 이 그 값을 보고 힙을 정하고, 넘치는 컨테이너만 스스로 죽는다.
  if [[ -n "$MEMORY_LIMIT" ]]; then
    [[ "$MEMORY_LIMIT" =~ ^[0-9]+[bkmgBKMG]?$ ]] || { echo "Invalid --memory: $MEMORY_LIMIT"; exit 1; }
    # swap 을 같은 값으로 묶지 않으면 상한을 넘긴 만큼 스왑으로 새어 나가 제한이 무의미해진다.
    run_args+=(--memory "$MEMORY_LIMIT" --memory-swap "$MEMORY_LIMIT")
  else
    echo "WARNING: --memory 가 지정되지 않았습니다. 한 서비스의 메모리 누수가 노드 전체를 정지시킬 수 있습니다."
  fi

  if [[ -n "$NETWORK" ]]; then
    safe_name "$NETWORK" || { echo "Invalid network name: $NETWORK"; exit 1; }
    run_args+=(--network "$NETWORK")
  fi

  append_publish_args

  if [[ -n "$ENV_FILE" ]]; then
    safe_path "$ENV_FILE" || { echo "Invalid env file path: $ENV_FILE"; exit 1; }
    [[ -f "$ENV_FILE" ]] || { echo "Env file not found: $ENV_FILE"; exit 1; }
    run_args+=(--env-file "$ENV_FILE")
  fi

  # 이미지 확보 — tar 를 받았으면 load, 아니면 종전대로 레지스트리에서 pull.
  # 망분리 구성에서는 대상 서버가 레지스트리에 붙을 수 없으므로 배포망이 tar 를 밀어 넣는다.
  if [[ -n "$IMAGE_TAR" ]]; then
    load_image_archive
    run_image="$IMAGE_ID"
    rm -f "$IMAGE_TAR"
  elif [[ -n "$IMAGE_ID" || -n "$IMAGE_SHA256" ]]; then
    echo "--image-id and --image-sha256 require --image-tar."; exit 1
  elif docker image inspect "$IMAGE" >/dev/null 2>&1; then
    # 이미 이 노드에 있는 이미지다(직전 배포분으로 되돌리는 경우가 대표적).
    # push 방식에서는 레지스트리로 나갈 수 없으므로, 있는 것을 그대로 쓴다.
    echo "Using image already present on this host: $IMAGE"
  elif [[ -n "$REGISTRY_URL" ]]; then
    registry_login
    docker image pull "$IMAGE"
  else
    # push 방식인데 tar 도 없고 로컬에도 없다 — 레지스트리를 찔러 봐야
    # "http: server gave HTTP response to HTTPS client" 같은 엉뚱한 오류만 나온다.
    echo "Image not found locally and no registry configured: $IMAGE"
    echo "  push 방식에서는 배포망이 --image-tar 로 이미지를 함께 보내야 합니다."
    exit 1
  fi

  PREVIOUS_ID=ABSENT PREVIOUS_RUNNING=false CURRENT_ID=""
  PREVIOUS_HEALTH_MODE=container PREVIOUS_HEALTH_PORT="" PREVIOUS_HEALTH_PATH=""
  if ! container_exists "$CONTAINER" && container_exists "$PREVIOUS_CONTAINER"; then
    echo "현재 컨테이너 없이 보존된 복구 인스턴스가 있습니다. 직전 배포를 복구한 뒤 다시 배포해 주세요."
    return 1
  fi
  if container_exists "$CONTAINER"; then
    PREVIOUS_ID="$(docker container inspect --format '{{.Id}}' "$CONTAINER")" || return 1
    PREVIOUS_RUNNING="$(docker container inspect --format '{{.State.Running}}' "$CONTAINER")" || return 1
    read_previous_health || return 1
    # A previous generation is retained only until the next deployment. Never remove volumes.
    if container_exists "$PREVIOUS_CONTAINER"; then
      [[ "$(docker container inspect --format '{{.State.Running}}' "$PREVIOUS_CONTAINER")" == false ]] || { echo "복구용 컨테이너가 실행 중입니다. 확인 후 다시 시도하세요."; return 1; }
      local owned_previous=""
      [[ -f "$RECOVERY_FILE" ]] && owned_previous="$(sed -n '2p' "$RECOVERY_FILE")"
      [[ -n "$owned_previous" && "$(docker container inspect --format '{{.Id}}' "$PREVIOUS_CONTAINER")" == "$owned_previous" ]] \
        || { echo "이 배포시스템이 보존한 컨테이너인지 확인할 수 없습니다."; return 1; }
      docker rm "$PREVIOUS_CONTAINER" >/dev/null || return 1
    fi
  fi
  # Persist the original identity and health contract before stopping anything.
  # Recheck after image transfer, before disturbing the running service.
  if [[ -n "$MOUNT_SPEC" ]]; then
    "$STORAGE_HELPER" arguments --spec "$MOUNT_SPEC" --sha256 "$MOUNT_SPEC_SHA256" --project "$MANAGED_PROJECT" \
      --component "$MANAGED_COMPONENT" --environment "$MANAGED_ENVIRONMENT" --container "$CONTAINER" --user "$CONTAINER_USER" >/dev/null || return 1
  fi
  write_recovery_state || { echo "직전 컨테이너 복구 정보 기록 실패. 기존 서비스는 유지합니다."; return 1; }
  if [[ "$PREVIOUS_ID" != ABSENT ]]; then
    stop_container || { local rc=$?; [[ "$PREVIOUS_RUNNING" != true ]] || { check_container_storage "$CONTAINER" && docker start "$CONTAINER" >/dev/null; }; return "$rc"; }
    docker rename "$CONTAINER" "$PREVIOUS_CONTAINER" || { [[ "$PREVIOUS_RUNNING" != true ]] || { check_container_storage "$CONTAINER" && docker start "$CONTAINER" >/dev/null; }; return 1; }
  fi

  run_args+=("$run_image")
  # 컨테이너 ID 를 버리지 않는다 — 실패 조사 때 필요하다.
  local cid
  if ! cid="$("${run_args[@]}")"; then
    echo "docker run 실패. 직전 상태 복원을 시도합니다."
    if container_exists "$CONTAINER"; then CURRENT_ID="$(docker container inspect --format '{{.Id}}' "$CONTAINER")" || CURRENT_ID=""; fi
    restore_previous "$CURRENT_ID" || echo "AUTOMATIC_RESTORE_FAILED=true"
    return 1
  fi
  CURRENT_ID="$cid"
  if ! write_recovery_state; then
    echo "신규 컨테이너 복구 상태 기록 실패. 직전 상태 복원을 시도합니다."
    restore_previous "$CURRENT_ID" || echo "AUTOMATIC_RESTORE_FAILED=true"
    return 1
  fi
  echo "Container started: ${cid:0:12}"

  # 헬스체크가 실패하면 "왜 실패했는지"를 여기서 뽑아 둔다.
  #   docker run -d 는 앱 stdout 을 컨테이너 안에 가둬 둔다. 이걸 꺼내지 않으면
  #   배포 이력에는 "Health check failed" 한 줄만 남고, 정작 원인(기동 실패 스택트레이스·
  #   DB 접속 거부·포트 충돌·환경변수 누락)은 아무 데도 남지 않는다.
  #   컨테이너가 이미 죽었을 수도 있으므로 상태부터 찍는다.
  if ! verify_health; then
    dump_failure_context
    echo "신규 컨테이너 상태 확인 실패. 직전 상태 복원을 시도합니다."
    restore_previous || echo "AUTOMATIC_RESTORE_FAILED=true"
    return 1
  fi
  if ! storage_desired "$CONTAINER" true; then
    echo "Storage desired-state persistence failed; restoring the previous deployment."
    restore_previous || echo "AUTOMATIC_RESTORE_FAILED=true"
    return 1
  fi
  docker ps --filter "name=^/${CONTAINER}$"
}

# 배포 실패 시 조사에 필요한 것만 모아 찍는다.
#   이 출력은 배포시스템의 remote_command_log 에 그대로 저장되고(시크릿은 저장 시 마스킹),
#   화면·AI 진단이 읽는 것도 이 기록이다.
dump_failure_context() {
  echo
  echo "===== 배포 실패 진단 ====="

  echo "--- 컨테이너 상태 ---"
  docker container inspect "$CONTAINER" --format \
'status={{.State.Status}}
exitCode={{.State.ExitCode}}
oomKilled={{.State.OOMKilled}}
restarts={{.RestartCount}}
startedAt={{.State.StartedAt}}
finishedAt={{.State.FinishedAt}}
error={{.State.Error}}
image={{.Config.Image}}
memLimit={{.HostConfig.Memory}}' 2>&1 || echo "(컨테이너 정보를 읽을 수 없습니다 — 생성 자체가 실패했을 수 있습니다)"

  echo
  echo "--- 애플리케이션 로그 (마지막 ${FAIL_LOG_LINES}줄) ---"
  # 죽은 컨테이너도 로그는 남아 있다. 여기에 진짜 원인이 있다.
  docker logs --tail "$FAIL_LOG_LINES" "$CONTAINER" 2>&1 \
    || echo "(로그를 읽을 수 없습니다)"

  echo
  echo "--- 포트 점유 ---"
  if [[ -n "$HOST_PORT" ]]; then
    (ss -lntp 2>/dev/null || netstat -lntp 2>/dev/null) | grep -E "[:.]${HOST_PORT}\\b" \
      || echo "(${HOST_PORT} 포트를 잡고 있는 프로세스 없음)"
  fi

  echo
  echo "--- 노드 자원 ---"
  free -m 2>/dev/null | head -2
  df -hP / 2>/dev/null | tail -1
  echo "=========================="
}

# 컨테이너 표준출력 로그. 읽기만 한다 — 상태를 바꾸지 않는다.
#   화면의 [실시간 로그] 가 주기적으로 이걸 불러 새 줄만 이어 붙인다.
#   --follow 를 쓰지 않는 이유: SSH 세션을 계속 물고 있으면 브라우저 탭 수만큼 원격 프로세스가
#   남고, 클라이언트가 조용히 사라지면 회수되지 않는다. 매번 끝나는 명령이 안전하다.
run_logs(){
  [[ -n "$CONTAINER" ]] || { echo "--container is required."; exit 1; }
  safe_name "$CONTAINER" || { echo "Invalid container name: $CONTAINER"; exit 1; }
  local n="$LINES"
  [[ "$n" =~ ^[0-9]{1,4}$ ]] || n=200
  (( n < 1 )) && n=1
  (( n > 1000 )) && n=1000
  container_exists "$CONTAINER" || { echo "컨테이너가 없습니다: $CONTAINER"; exit 1; }
  assert_container_owner "$CONTAINER" || return 1
  docker logs --tail "$n" --timestamps "$CONTAINER" 2>&1
}

run_status(){
  [[ -n "$CONTAINER" ]] || { echo "--container is required."; exit 1; }
  safe_name "$CONTAINER" || { echo "Invalid container name: $CONTAINER"; exit 1; }
  validate_project_identity || return 1
  if container_exists "$CONTAINER"; then assert_container_owner "$CONTAINER" || return 1; fi
  docker ps -a --filter "name=^/${CONTAINER}$"
}

# 운영자가 화면에서 누르는 정지/기동.
#   재배포가 아니다 — 이미 만들어져 있는 컨테이너를 그대로 껐다 켠다. 이미지·설정은 건드리지 않으므로
#   버전이 바뀌지 않고, 다시 start 하면 직전에 배포된 그 버전이 그대로 올라온다.
require_container(){
  [[ -n "$CONTAINER" ]] || { echo "--container is required."; exit 1; }
  safe_name "$CONTAINER" || { echo "Invalid container name: $CONTAINER"; exit 1; }
  container_exists "$CONTAINER" || {
    echo "컨테이너가 없습니다: $CONTAINER (아직 한 번도 배포되지 않았습니다)"
    exit 1
  }
  assert_container_owner "$CONTAINER" || return 1
}

stop_container(){
  assert_container_owner "$CONTAINER" || return 1
  docker stop --time "$STOP_GRACE_SECONDS" "$CONTAINER" >/dev/null || { echo "컨테이너 정지 명령에 실패했습니다."; return 1; }
  local stopped
  stopped="$(docker container inspect --format '{{.State.ExitCode}} {{.State.OOMKilled}} {{.State.Running}}' "$CONTAINER")" || return 1
  if [[ "$stopped" == "137 false false" ]]; then
    echo "STOP_GRACE_EXCEEDED: 종료 유예 ${STOP_GRACE_SECONDS}초 안에 종료되지 않아 강제 종료되었습니다. 작업 상태를 확인해 주세요."
    return 42
  fi
  [[ "$stopped" != *" true" ]] || { echo "컨테이너가 아직 실행 중입니다."; return 1; }
}

run_stop(){
  require_container || return $?
  storage_desired "$CONTAINER" false || return 1
  # docker stop 은 "사람이 명시적으로 내린 것"으로 기록된다. --restart unless-stopped 는
  # 이 상태를 존중해 도커 재시작·서버 재부팅 후에도 이 컨테이너만 올리지 않는다 —
  # 운영자가 내려둔 서비스가 재부팅으로 되살아나는 사고를 막는다.
  stop_container || return $?
  echo "Container stopped: $CONTAINER"
  docker ps -a --filter "name=^/${CONTAINER}$"
}

run_start(){
  require_container || return $?
  check_container_storage "$CONTAINER" || return 1
  storage_desired "$CONTAINER" false || return 1
  docker start "$CONTAINER" >/dev/null || { echo "docker start 실패."; exit 1; }
  echo "Container started: $CONTAINER"
  if ! verify_health; then
    dump_failure_context
    stop_container >/dev/null 2>&1       && echo "재시작 루프를 막기 위해 컨테이너를 정지했습니다: $CONTAINER"
    echo "기동은 했지만 헬스체크가 통과하지 못했습니다."
    exit 1
  fi
  storage_desired "$CONTAINER" true || { stop_container >/dev/null 2>&1; return 1; }
  docker ps --filter "name=^/${CONTAINER}$"
}

run_restart(){
  require_container || return $?
  check_container_storage "$CONTAINER" || return 1
  storage_desired "$CONTAINER" false || return 1
  stop_container || return $?
  docker start "$CONTAINER" >/dev/null || { echo "docker start 실패."; exit 1; }
  echo "Container restarted: $CONTAINER"
  if ! verify_health; then
    dump_failure_context
    stop_container >/dev/null 2>&1       && echo "재시작 루프를 막기 위해 컨테이너를 정지했습니다: $CONTAINER"
    echo "재기동은 했지만 헬스체크가 통과하지 못했습니다."
    exit 1
  fi
  storage_desired "$CONTAINER" true || { stop_container >/dev/null 2>&1; return 1; }
  docker ps --filter "name=^/${CONTAINER}$"
}

run_health(){
  require_container || return $?
  local identity
  identity="$(docker container inspect --format '{{.Id}}' "$CONTAINER")" || return 1
  [[ "$(docker container inspect --format '{{.State.Running}}' "$CONTAINER")" == true ]] \
    || { echo "컨테이너가 실행 중이 아닙니다."; return 1; }
  verify_health || return 1
  [[ "$(docker container inspect --format '{{.Id}}' "$CONTAINER")" == "$identity" ]] \
    && [[ "$(docker container inspect --format '{{.State.Running}}' "$CONTAINER")" == true ]] \
    || { echo "상태 검사 중 컨테이너가 변경되거나 중지되었습니다."; return 1; }
}

run_quiesce(){
  [[ -n "$CONTAINER" ]] && safe_name "$CONTAINER" || { echo "Invalid container name."; return 1; }
  validate_project_identity || return 1
  # A successful daemon listing proves absence; inspect failure alone does not.
  local listed running state
  listed="$(docker ps -a --filter "name=^/${CONTAINER}$" --format '{{.ID}}')" \
    || { echo "컨테이너 존재 여부를 조회하지 못했습니다."; return 1; }
  if [[ -z "$listed" ]]; then echo "QUIESCED=true (container absent)"; return 0; fi
  assert_container_owner "$CONTAINER" || return 1
  running="$(docker container inspect --format '{{.State.Running}}' "$CONTAINER")" \
    || { echo "컨테이너 실행 상태를 조회하지 못했습니다."; return 1; }
  if [[ "$running" == false ]]; then
    state="$(docker container inspect --format '{{.State.Status}}' "$CONTAINER")" || return 1
    [[ "$state" == exited || "$state" == created ]] \
      || { echo "컨테이너가 안정된 정지 상태가 아닙니다: $state"; return 1; }
    echo "QUIESCED=true (container stopped)"; return 0
  fi
  [[ "$running" == true ]] || { echo "컨테이너 실행 상태가 올바르지 않습니다."; return 1; }
  [[ "$HEALTH_PORT" =~ ^[0-9]{1,5}$ ]] && (( 10#$HEALTH_PORT >= 1 && 10#$HEALTH_PORT <= 65535 )) \
    || { echo "연결 배출에는 공개 서비스 포트가 필요합니다."; return 1; }
  [[ "$DRAIN_TIMEOUT_SECONDS" =~ ^[1-9][0-9]{0,2}$ ]] && (( DRAIN_TIMEOUT_SECONDS <= 120 )) \
    || { echo "연결 배출 제한 시간은 1~120초입니다."; return 1; }
  command -v nsenter >/dev/null && command -v ss >/dev/null \
    || { echo "연결 배출 검사에는 util-linux(nsenter)와 iproute(ss)가 필요합니다."; return 1; }
  local identity pid network container_port bindings line host port connections attempt quiet=0
  identity="$(docker container inspect --format '{{.Id}}' "$CONTAINER")" || return 1
  pid="$(docker container inspect --format '{{.State.Pid}}' "$CONTAINER")" || return 1
  [[ "$pid" =~ ^[1-9][0-9]*$ ]] || { echo "실행 중인 컨테이너 PID를 확인할 수 없습니다."; return 1; }
  network="$(docker container inspect --format '{{.HostConfig.NetworkMode}}' "$CONTAINER")" || return 1
  if [[ "$network" == host ]]; then
    container_port="$HEALTH_PORT"
  else
    bindings="$(docker container inspect --format '{{range $port, $bindings := .NetworkSettings.Ports}}{{range $bindings}}{{println .HostPort $port}}{{end}}{{end}}' "$CONTAINER")" || return 1
    container_port=""
    while read -r host port; do
      if [[ "$host" == "$HEALTH_PORT" && "$port" =~ ^([0-9]{1,5})/tcp$ ]]; then
        [[ -z "$container_port" || "$container_port" == "${BASH_REMATCH[1]}" ]] \
          || { echo "서비스 포트 매핑이 중복되어 연결 배출을 확인할 수 없습니다."; return 1; }
        container_port="${BASH_REMATCH[1]}"
      fi
    done <<< "$bindings"
    [[ -n "$container_port" ]] || { echo "공개 서비스 포트의 컨테이너 포트 매핑을 찾을 수 없습니다."; return 1; }
  fi
  for (( attempt=0; attempt<=DRAIN_TIMEOUT_SECONDS; attempt++ )); do
    [[ "$(docker container inspect --format '{{.Id}}' "$CONTAINER")" == "$identity" ]] \
      && [[ "$(docker container inspect --format '{{.State.Pid}}' "$CONTAINER")" == "$pid" ]] \
      || { echo "연결 배출 검사 중 컨테이너가 변경되었습니다."; return 1; }
    connections="$(nsenter --target "$pid" --net -- ss -Htan state established "( sport = :$container_port )")" \
      || { echo "컨테이너 연결 배출 상태를 조회하지 못했습니다."; return 1; }
    if [[ -z "$connections" ]]; then
      quiet=$((quiet + 1))
      if (( quiet >= 2 )); then echo "DRAINED_CONTAINER_ID=$identity"; return 0; fi
    else
      quiet=0
    fi
    (( attempt >= DRAIN_TIMEOUT_SECONDS )) || sleep 1
  done
  echo "기존 연결이 남아 배포를 중단합니다. 컨테이너는 유지합니다."
  return 1
}

main(){
  require_root
  parse_args "$@"
  load_defaults
  [[ "$STOP_GRACE_SECONDS" =~ ^[1-9][0-9]{0,2}$ ]] && (( STOP_GRACE_SECONDS <= 300 )) || { echo "종료 유예 시간은 1~300초 정수로 입력해 주세요."; exit 1; }
  if [[ "$ACTION" == "capabilities" ]]; then printf 'STOP_GRACE_VERSION=1\nROLLBACK_VERSION=1\nQUIESCE_VERSION=1\nPROJECT_OWNERSHIP_VERSION=1\nSTORAGE_MOUNT_VERSION=1\n'; return; fi
  docker_ready

  if [[ "$ACTION" == deploy || "$ACTION" == restore || "$ACTION" == quiesce \
        || "$ACTION" == start || "$ACTION" == stop || "$ACTION" == restart ]]; then
    recovery_paths || return 1
    exec 9>"$STATE_ROOT/$CONTAINER.lock"
    flock -n 9 || { echo "이 프로젝트 컨테이너의 배포·복구가 이미 실행 중입니다."; return 1; }
  fi

  case "$ACTION" in
    deploy) run_deploy ;;
    restore) restore_previous ;;
    quiesce) run_quiesce ;;
    probe) run_health ;;
    status) run_status ;;
    logs) run_logs ;;
    start) run_start ;;
    stop) run_stop ;;
    restart) run_restart ;;
    health) run_health ;;
    *) echo "Unsupported action: $ACTION"; usage; exit 1 ;;
  esac
}

main "$@"
