#!/bin/bash
# install-deploy-agent.sh — 배포 "대상" 서버에 배포 에이전트를 설치한다 (was/web 통합).
#   agent 전송은 서버의 outbound worker가 승인된 작업을 받아 실행한다.
#   서버 관리 작업은 outbound 에이전트 전송만 사용한다.
#   (구 offline-installer/final-deliverables/{was,web}-pack 의 install-deploy-agent.sh 를 통합한 정본)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$SCRIPT_DIR/sudoers-render.sh"
AGENT_TYPE=""                 # was | web | both
AGENT_TRANSPORT="${DEPLOY_AGENT_TRANSPORT:-agent}"
CONTROL_URL="${DEPLOY_AGENT_CONTROL_URL:-}"
SERVER_ID="${DEPLOY_AGENT_SERVER_ID:-}"
ENROLLMENT_CODE_FILE="${DEPLOY_AGENT_ENROLLMENT_CODE_FILE:-}"
CONTROL_CA_FILE="${DEPLOY_AGENT_CA_FILE:-}"
ALLOW_HTTP="${DEPLOY_AGENT_ALLOW_HTTP:-n}"
DEPLOY_USER="deploy"
DEPLOY_GROUP="deploy"
READ_USER="${READ_USER:-deployai}"
READ_ENABLED=1
DEFAULT_HEALTH_PORT="8080"
DEFAULT_HEALTH_PATH="/actuator/health"
REGISTRY_URL=""
REGISTRY_USERNAME=""
REGISTRY_PASSWORD=""
FORCE_REINSTALL=0
INSTALL_BASE="/usr/local/deploy"
SUDOERS_PATH="/etc/sudoers.d/deploy-remote"
SUDOERS_CANDIDATE=""

usage(){
  cat <<'EOF'
Usage: install-deploy-agent.sh --type was|web|deploy|nexus|gitlab|runner
         [--deploy-user USER]
         [--health-port PORT] [--health-path PATH]
         [--transport agent] [--control-url https://deploy.example:9090]
         [--server-id ID] [--enrollment-code-file ROOT_ONLY_FILE] [--ca-file FILE]
         [--allow-http] # isolated local QA only; HTTPS is the normal contract
         [--registry-url URL] [--registry-username USER] [--registry-password PASSWORD]
         [--force-reinstall]
EOF
}

parse_args(){
  while [[ "$#" -gt 0 ]]; do
    case "$1" in
      --type) AGENT_TYPE="$2"; shift 2 ;;
      --transport) AGENT_TRANSPORT="$2"; shift 2 ;;
      --control-url) CONTROL_URL="$2"; shift 2 ;;
      --server-id) SERVER_ID="$2"; shift 2 ;;
      --enrollment-code-file) ENROLLMENT_CODE_FILE="$2"; shift 2 ;;
      --ca-file) CONTROL_CA_FILE="$2"; shift 2 ;;
      --allow-http) ALLOW_HTTP=y; shift ;;
      --deploy-user) DEPLOY_USER="$2"; DEPLOY_GROUP="$2"; shift 2 ;;
      --authorized-key-file|--ssh-port|--ssh-mode) echo "SSH 관리 옵션은 지원하지 않습니다. 에이전트를 등록하세요." >&2; return 1 ;;
      --read-user) READ_USER="$2"; shift 2 ;;
      --no-read-agent) READ_ENABLED=0; shift ;;
      --health-port) DEFAULT_HEALTH_PORT="$2"; shift 2 ;;
      --health-path) DEFAULT_HEALTH_PATH="$2"; shift 2 ;;
      --registry-url) REGISTRY_URL="$2"; shift 2 ;;
      --registry-username) REGISTRY_USERNAME="$2"; shift 2 ;;
      --registry-password) REGISTRY_PASSWORD="$2"; shift 2 ;;
      --force-reinstall) FORCE_REINSTALL=1; shift ;;
      -h|--help) usage; exit 0 ;;
      *) echo "Unknown argument: $1"; usage; exit 1 ;;
    esac
  done
  case "$AGENT_TYPE" in
    was|web|deploy|nexus|gitlab|runner) ;;
    *) echo "--type 은 was|web|deploy|nexus|gitlab|runner 중 하나여야 합니다."; usage; exit 1 ;;
  esac
  [[ "$DEPLOY_USER" =~ ^[a-z_][a-z0-9_-]*$ && "$READ_USER" =~ ^[a-z_][a-z0-9_-]*$ ]] \
    || { echo 'Invalid deploy/read account name'; return 1; }
  case "$AGENT_TRANSPORT" in
    agent)
      [[ "$AGENT_TYPE" != both ]] || { echo 'Pull worker requires one WAS or WEB role per server'; return 1; }
      [[ -z "$SERVER_ID" || "$SERVER_ID" =~ ^[1-9][0-9]*$ ]] || { echo 'Invalid agent server ID'; return 1; }
      case "$ALLOW_HTTP" in y|n) ;; *) echo 'DEPLOY_AGENT_ALLOW_HTTP must be y or n'; return 1 ;; esac
      ;;
    *) echo '--transport must be agent'; return 1 ;;
  esac
}

install_pull_worker(){
  local worker="$SCRIPT_DIR/pull/deploy-pull-agent.py" service="$SCRIPT_DIR/pull/deploy-pull-agent.service"
  ensure_file "$worker"; ensure_file "$service"; ensure_file "$SCRIPT_DIR/pull/deploy-agent-probe"
  install -d -m 700 -o root -g root /etc/deploy-system /var/lib/deploy-system/agent
  install -d -m 700 -o root -g root /var/tmp/deploy-ops
  install -m 750 -o root -g root "$worker" "$INSTALL_BASE/bin/deploy-pull-agent.py"
  install -m 750 -o root -g root "$SCRIPT_DIR/pull/deploy-agent-probe" "$INSTALL_BASE/bin/deploy-agent-probe"
  install -m 644 -o root -g root "$service" /etc/systemd/system/deploy-pull-agent.service
  systemctl daemon-reload
  systemctl enable deploy-pull-agent.service
  if [[ ! -e /etc/deploy-system/agent.json && -n "$CONTROL_URL" && -n "$SERVER_ID" && -n "$ENROLLMENT_CODE_FILE" ]]; then
    local enroll=(--config /etc/deploy-system/agent.json enroll --url "$CONTROL_URL" --server-id "$SERVER_ID" --role "${AGENT_TYPE^^}" --enrollment-code-file "$ENROLLMENT_CODE_FILE")
    if [[ -n "$CONTROL_CA_FILE" ]]; then
      [[ -f "$CONTROL_CA_FILE" && ! -L "$CONTROL_CA_FILE" ]] || { echo 'Invalid agent CA file'; return 1; }
      install -m 600 -o root -g root "$CONTROL_CA_FILE" /etc/deploy-system/agent-ca.pem
      enroll+=(--ca-file /etc/deploy-system/agent-ca.pem)
    fi
    [[ "$ALLOW_HTTP" != y ]] || enroll+=(--allow-http)
    /usr/libexec/platform-python "$INSTALL_BASE/bin/deploy-pull-agent.py" "${enroll[@]}" || return 1
  fi
  if [[ -f /etc/deploy-system/agent.json && ! -L /etc/deploy-system/agent.json ]]; then
    # Reinstall preserves the host identity/credential and the durable task journal.
    systemctl restart deploy-pull-agent.service
    echo '[agent] outbound worker started; confirm registration in Deploy UI'
  else
    echo '[agent] worker installed, registration pending. Issue an enrollment code in Deploy UI and run the enroll command.'
  fi
}

require_root(){
  [[ "${EUID}" -eq 0 ]] || { echo "Run as root."; exit 1; }
}

ensure_file(){
  local target="$1"
  [[ -e "$target" ]] || { echo "Required file not found: $target"; exit 1; }
}

ensure_deploy_user(){
  if ! getent group "$DEPLOY_GROUP" >/dev/null 2>&1; then
    groupadd --system "$DEPLOY_GROUP"
  fi
  if ! id -u "$DEPLOY_USER" >/dev/null 2>&1; then
    useradd --create-home --gid "$DEPLOY_GROUP" --shell /bin/bash "$DEPLOY_USER"
  fi
  # ★ docker 그룹에 넣지 않는다.
  #   docker 소켓은 root 권한과 동등하다 — 그룹에 들어가면 sudoers 화이트리스트
  #   ("에이전트 스크립트만 실행 가능")를 통째로 우회할 수 있다. 배포 계정은 배포망에서
  #   로컬 실행 계정에도 Docker 소켓 권한을 부여하지 않는다.
  #   에이전트 스크립트는 sudoers NOPASSWD 로 root 로 실행되니 docker 는 그 안에서 동작한다.
  #   (과거 설치본에서 잘못 추가됐다면 여기서 되돌린다)
  if getent group docker >/dev/null 2>&1 && id -nG "$DEPLOY_USER" 2>/dev/null | grep -qw docker; then
    gpasswd -d "$DEPLOY_USER" docker >/dev/null 2>&1 || true
    echo "[보안] $DEPLOY_USER 를 docker 그룹에서 제거했습니다(소켓 = root 권한)."
  fi
}

write_agent_env(){
  local env_file="$1"
  {
    printf 'DEFAULT_HEALTH_PORT=%q\n' "$DEFAULT_HEALTH_PORT"
    printf 'DEFAULT_HEALTH_PATH=%q\n' "$DEFAULT_HEALTH_PATH"
    printf 'REGISTRY_URL=%q\n' "$REGISTRY_URL"
    printf 'REGISTRY_USERNAME=%q\n' "$REGISTRY_USERNAME"
    printf 'REGISTRY_PASSWORD=%q\n' "$REGISTRY_PASSWORD"
  } > "$env_file"
  chmod 640 "$env_file"
  chown root:root "$env_file"
}

install_one(){
  local type="$1"
  local deploy_script="$SCRIPT_DIR/$type/deploy-$type-app.sh"
  local bin_dir="$INSTALL_BASE/bin"
  local config_dir="$INSTALL_BASE/conf"

  ensure_file "$deploy_script"
  # 설치기는 서명된 현재 release의 파일을 정본으로 삼는다. 재실행할 때도 항상
  # 같은 bytes로 수렴시켜 구버전 agent와 공통 helper의 부분 혼재를 남기지 않는다.
  install -m 750 -o root -g root "$deploy_script" "$bin_dir/deploy-$type-app.sh"
  write_agent_env "$config_dir/$type-agent.env"
  echo "[$type] 에이전트 설치: $bin_dir/deploy-$type-app.sh / $config_dir/$type-agent.env"
}

# 읽기 전용 진단 에이전트 + 전용 계정.
#   AI 와 진단 화면이 서버 상태를 볼 때 쓰는 유일한 경로다. 배포 계정과 분리해서,
#   AI 경로가 침해돼도 배포 스크립트에는 손이 닿지 않게 한다.
install_read_agent(){
  local src="$SCRIPT_DIR/read/deploy-read.sh"
  local bin_dir="$INSTALL_BASE/bin"
  [[ -f "$src" ]] || { echo "읽기 에이전트 소스가 없습니다: $src"; return 1; }

  if ! getent group "$READ_USER" >/dev/null 2>&1; then
    groupadd --system "$READ_USER"
  fi
  if ! id -u "$READ_USER" >/dev/null 2>&1; then
    # 로컬 helper의 전용 서비스 계정이다.
    useradd --system --create-home --gid "$READ_USER" --shell /bin/bash "$READ_USER"
  fi
  # Keep the dedicated helper account password locked.
  usermod --shell /bin/bash "$READ_USER"
  lock_account_password "$READ_USER"
  # 이 계정도 docker 그룹에 넣지 않는다(소켓 = root 권한).
  if getent group docker >/dev/null 2>&1 && id -nG "$READ_USER" 2>/dev/null | grep -qw docker; then
    gpasswd -d "$READ_USER" docker >/dev/null 2>&1 || true
  fi

  install -d -m 755 "$bin_dir"
  install -m 750 -o root -g root "$src" "$bin_dir/deploy-read.sh"

  echo "[read] 읽기 전용 에이전트 설치: $bin_dir/deploy-read.sh (계정: $READ_USER)"
}

lock_account_password(){
  # usermod ships with the shadow-utils package already required by this installer.
  # Locking is idempotent, works for new `!!` accounts, and is never reversed on re-enable.
  usermod --lock "$1"
}

disable_read_agent(){
  local nologin home_dir candidate
  id -u "$READ_USER" >/dev/null 2>&1 || return 0
  nologin="$(command -v nologin || true)"
  if [[ -z "$nologin" || ! -x "$nologin" ]]; then
    nologin=""
    for candidate in /sbin/nologin /usr/sbin/nologin; do
      if [[ -x "$candidate" ]]; then nologin="$candidate"; break; fi
    done
  fi
  [[ -n "$nologin" && -x "$nologin" ]] || { echo 'nologin shell not found; cannot disable read-agent account' >&2; return 1; }
  # Lock the command shell first; key cleanup follows. Never unlock the password on re-enable.
  usermod --shell "$nologin" "$READ_USER"
  lock_account_password "$READ_USER"
  home_dir="$(getent passwd "$READ_USER" | cut -d: -f6)"
  rm -f -- "$INSTALL_BASE/bin/deploy-read.sh"
  echo "[read] 읽기 계정 비활성화: $READ_USER (shell=$nologin)"
}

remove_opposite_role_scripts(){
  case "$AGENT_TYPE" in
    was)
      rm -f -- "$INSTALL_BASE/bin/deploy-web-app.sh" \
        "$INSTALL_BASE/bin/deploy-web-static.sh" \
        "$INSTALL_BASE/bin/deploy-web-proxy.sh" \
          "$INSTALL_BASE/bin/deploy-nginx-policy.sh" \
          "$INSTALL_BASE/bin/nginx-config-validator.py" \
          "$INSTALL_BASE/bin/nginx-rollout.py" \
          "$INSTALL_BASE/bin/deploy-container-core.sh"
      ;;
    web) rm -f -- "$INSTALL_BASE/bin/deploy-was-app.sh" ;;
  esac
}

# WEB 추가 에이전트: 정적(STATIC_BUNDLE)·프록시(PROXY_RELOAD) 산출물 배포 스크립트.
# 존재할 때만 설치하므로 구 트리에서도 안전하다.
install_web_extras(){
  local bin_dir="$INSTALL_BASE/bin"
  local extra
  # Fixed root-only core, called only by the WEB wrapper; no direct sudo rule is added.
  install -m 750 -o root -g root "$SCRIPT_DIR/was/deploy-was-app.sh" "$bin_dir/deploy-container-core.sh"
  for extra in deploy-web-static.sh deploy-web-proxy.sh deploy-nginx-policy.sh; do
    local src="$SCRIPT_DIR/web/$extra"
    [[ -f "$src" ]] || continue
    install -m 750 -o root -g root "$src" "$bin_dir/$extra"
    echo "[web] 추가 에이전트 설치: $bin_dir/$extra"
  done
  install -m 640 -o root -g root "$SCRIPT_DIR/web/nginx-config-validator.py" "$bin_dir/nginx-config-validator.py"
  install -m 640 -o root -g root "$SCRIPT_DIR/web/nginx-rollout.py" "$bin_dir/nginx-rollout.py"
}

main(){
  require_root
  parse_args "$@"
    local tool
    for tool in sudo visudo systemctl getent useradd groupadd gpasswd tar gzip curl flock findmnt getenforce; do
      command -v "$tool" >/dev/null || { echo "Missing agent prerequisite: $tool"; return 1; }
    done
    [[ -x /usr/libexec/platform-python ]] || { echo 'Rocky platform-python is required for the pull worker'; return 1; }
    if systemctl is-active deploy-pull-agent.service >/dev/null 2>&1; then
      echo 'Agent is active. Confirm no running jobs in Deploy UI, stop deploy-pull-agent.service, then reinstall. In-flight helpers are never overwritten.' >&2
      return 1
    fi
    /usr/libexec/platform-python "$SCRIPT_DIR/pull/deploy-pull-agent.py" --state-root /var/lib/deploy-system/agent install-check || return 1
    if [[ -e /etc/deploy-system/agent.json || -L /etc/deploy-system/agent.json ]]; then
      local verify=(--config /etc/deploy-system/agent.json verify --role "${AGENT_TYPE^^}")
      [[ -z "$SERVER_ID" ]] || verify+=(--server-id "$SERVER_ID")
      [[ -z "$CONTROL_URL" ]] || verify+=(--url "$CONTROL_URL")
      /usr/libexec/platform-python "$SCRIPT_DIR/pull/deploy-pull-agent.py" "${verify[@]}" || return 1
    fi
  if [[ "$AGENT_TYPE" == deploy || "$AGENT_TYPE" == nexus || "$AGENT_TYPE" == gitlab || "$AGENT_TYPE" == runner ]]; then
    local infra="$SCRIPT_DIR/../infrastructure"
    ensure_file "$infra/deploy-infrastructure.sh"
    ensure_file "$infra/deploy-infrastructure.py"
    install -d -m 755 -o root -g root "$INSTALL_BASE/bin" "$INSTALL_BASE/conf"
    install -m 750 -o root -g root "$infra/deploy-infrastructure.sh" "$INSTALL_BASE/bin/deploy-infrastructure.sh"
    install -m 640 -o root -g root "$infra/deploy-infrastructure.py" "$INSTALL_BASE/bin/deploy-infrastructure.py"
    printf '%s\n' "$AGENT_TYPE" > "$INSTALL_BASE/conf/agent-type"
    printf 'agent\n' > "$INSTALL_BASE/conf/agent-transport"
    chmod 640 "$INSTALL_BASE/conf/agent-type" "$INSTALL_BASE/conf/agent-transport"
    install -d -m 700 -o root -g root /etc/deploy-system
    if [[ ! -e /etc/deploy-system/infrastructure.json ]]; then
      /usr/libexec/platform-python - "$AGENT_TYPE" "${DEPLOY_INFRA_RESTORE_HTTP_PORT:-18929}" <<'PYCONFIG'
import json, os, sys
role = sys.argv[1].upper()
nexus = os.environ.get('NEXUS_CONTAINER', 'worksystem-nexus')
gitlab = os.environ.get('GITLAB_CONTAINER', 'worksystem-gitlab')
runner = os.environ.get('RUNNER_CONTAINER', 'worksystem-gitlab-runner')
containers = {'DEPLOY': {'nexus': nexus}, 'NEXUS': {'nexus': nexus}, 'GITLAB': {'gitlab': gitlab, 'runner': runner}, 'RUNNER': {'runner': runner}}
fd = os.open('/etc/deploy-system/infrastructure.json', os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(fd, 'w') as output:
    config = {'role': role, 'containers': containers[role]}
    if role == 'GITLAB':
        port = int(sys.argv[2])
        if not 1024 <= port <= 65535:
            raise ValueError('Restore HTTP port must be 1024..65535')
        config['restoreHttpPort'] = port
    json.dump(config, output)
PYCONFIG
    fi
    if [[ "$AGENT_TYPE" == gitlab ]]; then
      install -d -m 750 -o root -g root "$INSTALL_BASE/lib/gitlab"
      if ! command -v jq >/dev/null 2>&1; then
        ensure_file "$SCRIPT_DIR/../bundles/common/jq/jq-linux-amd64"
        install -d -m 755 -o root -g root "$INSTALL_BASE/lib/bundles/common/jq"
        install -m 755 -o root -g root "$SCRIPT_DIR/../bundles/common/jq/jq-linux-amd64" "$INSTALL_BASE/lib/bundles/common/jq/jq-linux-amd64"
        install -m 644 -o root -g root "$SCRIPT_DIR/../bundles/RELEASE-BOM.env" "$INSTALL_BASE/lib/bundles/RELEASE-BOM.env"
      fi
      local helper
      for helper in backup-gitlab.sh restore-gitlab.sh recovery-tools.sh; do
        install -m 750 -o root -g root "$SCRIPT_DIR/../gitlab/$helper" "$INSTALL_BASE/lib/gitlab/$helper"
      done
    fi
    install_pull_worker
    return
  fi
  visudo -c

  local health_script="$SCRIPT_DIR/local-healthcheck.sh"
  local sudoers_template="$SCRIPT_DIR/deploy-remote.sudoers"
  ensure_file "$health_script"
  ensure_file "$sudoers_template"

  # Validate the complete intended rule set before changing any installed marker or agent file.
  case "$AGENT_TYPE" in
    was) ensure_file "$SCRIPT_DIR/was/deploy-was-app.sh" ;;
    web)
      ensure_file "$SCRIPT_DIR/was/deploy-was-app.sh"
      ensure_file "$SCRIPT_DIR/web/deploy-web-app.sh"
      ensure_file "$SCRIPT_DIR/web/deploy-web-static.sh"
      ensure_file "$SCRIPT_DIR/web/deploy-web-proxy.sh"
      ensure_file "$SCRIPT_DIR/web/deploy-nginx-policy.sh"
      ensure_file "$SCRIPT_DIR/web/nginx-config-validator.py"
      ensure_file "$SCRIPT_DIR/web/nginx-rollout.py"
      ;;
    both)
      ensure_file "$SCRIPT_DIR/was/deploy-was-app.sh"
      ensure_file "$SCRIPT_DIR/web/deploy-web-app.sh"
      ensure_file "$SCRIPT_DIR/web/deploy-web-static.sh"
      ensure_file "$SCRIPT_DIR/web/deploy-web-proxy.sh"
      ensure_file "$SCRIPT_DIR/web/deploy-nginx-policy.sh"
      ensure_file "$SCRIPT_DIR/web/nginx-config-validator.py"
      ensure_file "$SCRIPT_DIR/web/nginx-rollout.py"
      ;;
  esac
  ensure_file "$SCRIPT_DIR/deploy-service-control.sh"
  ensure_file "$SCRIPT_DIR/was/deploy-runtime.sh"
  ensure_file "$SCRIPT_DIR/was/deploy-runtime.py"
  ensure_file "$SCRIPT_DIR/was/deploy-storage.sh"
  ensure_file "$SCRIPT_DIR/was/deploy-storage.py"
  ensure_file "$SCRIPT_DIR/was/deploy-storage-guard.service"
  ensure_file "$SCRIPT_DIR/was/deploy-storage-guard.timer"
  command -v findmnt >/dev/null || { echo "Storage validation requires util-linux(findmnt)"; exit 1; }
  [[ -x /usr/libexec/platform-python || -x /usr/bin/python3 ]] || { echo "Managed runtime agent requires Python 3"; exit 1; }
  (( READ_ENABLED )) && ensure_file "$SCRIPT_DIR/read/deploy-read.sh"

  SUDOERS_CANDIDATE="$(mktemp /etc/sudoers.d/.deploy-remote.XXXXXX)"
  trap '[[ -z "$SUDOERS_CANDIDATE" || ! -f "$SUDOERS_CANDIDATE" ]] || rm -f -- "$SUDOERS_CANDIDATE"' EXIT
  render_deploy_agent_sudoers "$sudoers_template" "$AGENT_TYPE" "$READ_ENABLED" "$DEPLOY_USER" "$READ_USER" > "$SUDOERS_CANDIDATE"
  chmod 440 "$SUDOERS_CANDIDATE"
  chown root:root "$SUDOERS_CANDIDATE"
  visudo -cf "$SUDOERS_CANDIDATE"

  # Apply the validated least-privilege rules before installing/removing agent files.
  # A later failure therefore cannot leave an older, broader sudoers policy active.
  mv "$SUDOERS_CANDIDATE" "$SUDOERS_PATH"
  SUDOERS_CANDIDATE=""
  visudo -cf "$SUDOERS_PATH"
  visudo -c
  ensure_deploy_user
  install -d -m 755 "$INSTALL_BASE" "$INSTALL_BASE/conf" "$INSTALL_BASE/bin"
  install -m 750 -o root -g root "$health_script" "$INSTALL_BASE/bin/local-healthcheck.sh"
  [[ -x /usr/libexec/platform-python || -x /usr/bin/python3 ]] || { echo "Managed runtime agent requires Python 3"; exit 1; }
  install -m 750 -o root -g root "$SCRIPT_DIR/was/deploy-runtime.sh" "$INSTALL_BASE/bin/deploy-runtime.sh"
  install -m 640 -o root -g root "$SCRIPT_DIR/was/deploy-runtime.py" "$INSTALL_BASE/bin/deploy-runtime.py"
  install -m 750 -o root -g root "$SCRIPT_DIR/was/deploy-storage.sh" "$INSTALL_BASE/bin/deploy-storage.sh"
  install -m 640 -o root -g root "$SCRIPT_DIR/was/deploy-storage.py" "$INSTALL_BASE/bin/deploy-storage.py"
  install -m 644 -o root -g root "$SCRIPT_DIR/was/deploy-storage-guard.service" /etc/systemd/system/deploy-storage-guard.service
  install -m 644 -o root -g root "$SCRIPT_DIR/was/deploy-storage-guard.timer" /etc/systemd/system/deploy-storage-guard.timer
  systemctl daemon-reload
  # Do not start application containers during agent installation. The timer checks
  # only previously recorded desired identities after installation has completed.
  if [[ "$AGENT_TYPE" == was || "$AGENT_TYPE" == both ]]; then
    systemctl enable deploy-storage-guard.service deploy-storage-guard.timer
    systemctl start deploy-storage-guard.timer
  else
    systemctl disable --now deploy-storage-guard.timer
    systemctl disable deploy-storage-guard.service
  fi
  if [[ -f "$SCRIPT_DIR/deploy-service-control.sh" ]]; then
    install -m 750 -o root -g root "$SCRIPT_DIR/deploy-service-control.sh" "$INSTALL_BASE/bin/deploy-service-control.sh"
  fi

  case "$AGENT_TYPE" in
    was)  install_one was ;;
    web)  install_one web; install_web_extras ;;
    both) install_one was; install_one web; install_web_extras ;;
  esac

  # 읽기 전용 진단 경로 — 배포 계정과 분리된 계정으로 설치한다.
  if (( READ_ENABLED )); then
    install_read_agent
  else
    disable_read_agent
  fi

  # Remove scripts from the previous role only after the narrower sudoers file is active.
  remove_opposite_role_scripts

  printf '%s\n' "$AGENT_TYPE" > "$INSTALL_BASE/conf/agent-type"
  chmod 640 "$INSTALL_BASE/conf/agent-type"
  chown root:root "$INSTALL_BASE/conf/agent-type"
  printf '%s\n' "$READ_ENABLED" > "$INSTALL_BASE/conf/agent-read-enabled"
  chmod 640 "$INSTALL_BASE/conf/agent-read-enabled"
  chown root:root "$INSTALL_BASE/conf/agent-read-enabled"
  printf '%s|%s\n' "$DEPLOY_USER" "$READ_USER" > "$INSTALL_BASE/conf/agent-users"
  chmod 640 "$INSTALL_BASE/conf/agent-users"
  chown root:root "$INSTALL_BASE/conf/agent-users"

  [[ "$(getenforce)" == Disabled ]] || restorecon -RF "$INSTALL_BASE" "$SUDOERS_PATH"
  printf '%s\n' "$AGENT_TRANSPORT" > "$INSTALL_BASE/conf/agent-transport"
  chmod 640 "$INSTALL_BASE/conf/agent-transport"
  chown root:root "$INSTALL_BASE/conf/agent-transport"
  install_pull_worker

  echo "배포 에이전트 설치 완료."
  echo "  계정      : $DEPLOY_USER (sudoers: $SUDOERS_PATH)"
  echo "  설치 경로 : $INSTALL_BASE/bin"
  echo "다음 단계: Deploy 서버 관리에서 에이전트 등록 승인과 연결 검사를 실행하세요."
}

main "$@"
