#!/usr/bin/env bash
# ui.sh - 출력/프롬프트 유틸 (install.sh 에서 source 하여 사용)
# 단독 실행용이 아니라 함수 정의 모음이다.

init_ui() {
  if [[ -t 1 ]]; then
    C_RESET=$'\033[0m'; C_BOLD=$'\033[1m'
    C_BLUE=$'\033[38;5;33m'; C_CYAN=$'\033[38;5;44m'
    C_GREEN=$'\033[38;5;40m'; C_YELLOW=$'\033[38;5;220m'
    C_RED=$'\033[38;5;196m'; C_GRAY=$'\033[38;5;245m'
  else
    C_RESET=''; C_BOLD=''; C_BLUE=''; C_CYAN=''
    C_GREEN=''; C_YELLOW=''; C_RED=''; C_GRAY=''
  fi
}

line()    { printf '%b\n' "${C_GRAY}================================================================${C_RESET}"; }
section() { echo; printf '%b\n' "${C_BOLD}${C_CYAN}[$1]${C_RESET}"; }
kv()      { printf ' %-26s %s\n' "$1" "$2"; }
info()    { printf '%b%s%b\n' "$C_GRAY" "$1" "$C_RESET"; }
success() { printf '%b%s%b\n' "$C_GREEN" "$1" "$C_RESET"; }
warn()    { printf '%b%s%b\n' "$C_YELLOW" "$1" "$C_RESET"; }
err()     { printf '%b%s%b\n' "$C_RED" "$1" "$C_RESET" >&2; }

# 상태 라벨(색상 포함 문자열만 반환)
label_installed() { printf '%b%s%b' "$C_GREEN" "installed" "$C_RESET"; }
label_partial()   { printf '%b%s%b' "$C_YELLOW" "partial"   "$C_RESET"; }
label_missing()   { printf '%b%s%b' "$C_GRAY"   "missing"   "$C_RESET"; }

# 상태 문자열(installed/partial/missing 등)을 색칠
colorize_state() {
  case "$1" in
    installed) label_installed ;;
    unregistered) printf '%b%s%b' "$C_YELLOW" 'unregistered (Runner 등록 대기)' "$C_RESET" ;;
    partial)   label_partial ;;
    missing)   label_missing ;;
    *)         printf '%s' "$1" ;;
  esac
}

banner() {
  line
  printf '%b\n' "${C_BOLD}${C_BLUE}Office System Offline Installer${C_RESET}"
  printf '%b\n' "${C_GRAY}OS 자동감지 · 통합 메뉴 · 설치여부 확인 · 순서 안내${C_RESET}"
  line
}

prompt_input() {
  local message="$1" default_value="${2:-}" value
  read -r -p "$message" value
  [[ -z "$value" ]] && value="$default_value"
  value="${value//$'\r'/}"; value="${value//$'\n'/}"
  printf '%s' "$value"
}

prompt_secret() {
  local message="$1" value
  # 입력 후 줄바꿈은 stderr 로 — stdout 으로 보내면 $(...) 캡처 시 값 앞에 개행이 섞인다.
  read -r -s -p "$message" value; echo >&2
  value="${value//$'\r'/}"; value="${value//$'\n'/}"
  printf '%s' "$value"
}

confirm() {
  local message="$1" answer
  printf '%b? %s%b [y/N]: ' "$C_BOLD" "$message" "$C_RESET"
  read -r answer
  answer="${answer//$'\r'/}"; answer="${answer//$'\n'/}"
  answer="$(printf '%s' "$answer" | tr '[:upper:]' '[:lower:]')"
  [[ "$answer" == "y" || "$answer" == "yes" ]]
}

# ────────────────────────────────────────────────────────────────
# 무인(unattended) 모드 지원
#   - 응답파일(KEY=VALUE, shell 문법)을 환경으로 로드 → ask* 가 동명 변수를 최우선 사용
#   - UNATTENDED=1 이면 프롬프트 없이 (환경값 > 기본값) 순으로 결정
#   - 응답 키 목록은 installer/answers.example.env 참조
#   - 응답파일은 셸로 실행하지 않는다. 허용된 KEY=VALUE 데이터만 파싱한다.
# ────────────────────────────────────────────────────────────────
answer_key_allowed() {
  case "$1" in DEPLOY_TLS_KEYSTORE_FILE|DEPLOY_TLS_CA_FILE|DEPLOY_HEALTHCHECK_URL) return 0 ;; esac
  case "$1" in DEPLOY_INFRA_RESTORE_HTTP_PORT|DEPLOY_AGENT_TRANSPORT|DEPLOY_AGENT_CONTROL_URL|DEPLOY_AGENT_SERVER_ID|DEPLOY_AGENT_ENROLLMENT_CODE_FILE|DEPLOY_AGENT_CA_FILE|DEPLOY_AGENT_ALLOW_HTTP) return 0 ;; esac
  case "$1" in NEXUS_ISOLATED_RESTORE_ENABLED|NEXUS_ADDITIONAL_REGISTRY_PORT|NEXUS_MAVEN_SETUP|NEXUS_MAVEN_REPAIR_DRIFT|NEXUS_SECRETS_KEY_FILE|TARGET_SSH_PORT|TARGET_SSH_MODE) return 0 ;; esac
  local key="$1"
  local allowed='|FORCE_REINSTALL|OFFLINE_REQUIRE_SIGNATURE|OFFLINE_MANIFEST_PUBLIC_KEY|DOCKER_SET_INSECURE_REGISTRY|DOCKER_INSECURE_REGISTRY|DOCKER_GROUP_USER|MARIADB_BIND_ADDRESS|MARIADB_ROOT_PASSWORD|GITLAB_HTTP_PORT|GITLAB_SSH_PORT|GITLAB_EXTERNAL_URL|GITLAB_PUMA_WORKERS|GITLAB_SIDEKIQ_CONCURRENCY|GITLAB_PROMETHEUS|GITLAB_RUNNER_REGISTER|GITLAB_RUNNER_URL|GITLAB_RUNNER_CLONE_URL|GITLAB_RUNNER_API_TOKEN|GITLAB_RUNNER_TYPE|GITLAB_RUNNER_PROJECT_ID|GITLAB_RUNNER_GROUP_ID|GITLAB_RUNNER_TOKEN|GITLAB_RUNNER_ID|GITLAB_RUNNER_VERIFY_API_TOKEN|GITLAB_RUNNER_NAME|GITLAB_RUNNER_DOCKER_IMAGE|GITLAB_RUNNER_DOCKER_NETWORK|GITLAB_RUNNER_TAG_LIST|GITLAB_RUNNER_RUN_UNTAGGED|GITLAB_RUNNER_PROTECTED|GITLAB_RUNNER_LOCKED|NEXUS_WEB_PORT|NEXUS_REGISTRY_PORT|NEXUS_HEAP|NEXUS_AUTO_SETUP|NEXUS_ADMIN_PASSWORD|NEXUS_DOCKER_REPO_NAME|NEXUS_REPAIR_DRIFT|DEPLOYOPS_DB_HOST|DEPLOYOPS_DB_PORT|DEPLOYOPS_DB_NAME|DEPLOYOPS_DB_USER|DEPLOYOPS_DB_PASSWORD|DEPLOYOPS_APP_PORT|DEPLOYOPS_HEAP|DEPLOYOPS_IMAGE_TRANSFER_MODE|SSO_SHARED_SECRET|DEPLOYOPS_CRYPTO_KEY|DEPLOYOPS_ADMIN_PASSWORD|DEPLOYOPS_CREATE_DB|DEPLOYOPS_BACKUP_DIR|DEPLOYOPS_KEEP_DAILY|DEPLOYOPS_MAILBOX_DIR|WORKSYSTEM_DB_HOST|WORKSYSTEM_DB_PORT|WORKSYSTEM_DB_NAME|WORKSYSTEM_DB_USER|WORKSYSTEM_DB_PASSWORD|WORKSYSTEM_APP_PORT|WORKSYSTEM_HEAP|DEPLOY_SYSTEM_URL|ANONYMITY_SECRET|WORKSYSTEM_CREATE_DB|WORKSYSTEM_ADMIN_USER|WORKSYSTEM_ADMIN_NAME|WORKSYSTEM_ADMIN_PASSWORD|DEPLOY_AGENT_TYPE|DEPLOY_AGENT_USER|DEPLOY_AGENT_AUTHORIZED_KEY_FILE|DEPLOY_AGENT_HEALTH_PORT|DEPLOY_AGENT_HEALTH_PATH|DEPLOY_AGENT_REGISTRY_URL|DEPLOY_AGENT_REGISTRY_USERNAME|DEPLOY_AGENT_REGISTRY_PASSWORD|NGINX_UPSTREAM_HOST|NGINX_MAX_BODY|NGINX_PROXY_OFFICE|NGINX_PROXY_DEPLOY|NGINX_PROXY_GITLAB|NGINX_PROXY_NEXUS_WEB|NGINX_PROXY_NEXUS_REGISTRY|NGINX_OFFICE_LISTEN|NGINX_OFFICE_UPSTREAM|NGINX_DEPLOY_LISTEN|NGINX_DEPLOY_UPSTREAM|NGINX_GITLAB_LISTEN|NGINX_GITLAB_UPSTREAM|NGINX_NEXUS_WEB_LISTEN|NGINX_NEXUS_WEB_UPSTREAM|NGINX_NEXUS_REGISTRY_LISTEN|NGINX_NEXUS_REGISTRY_UPSTREAM|NGINX_CUSTOM_SERVICES|NGINX_STATIC_ROOT|NGINX_STATIC_LISTEN|'
  [[ "$allowed" == *"|$key|"* ]]
}

parse_answer_value() {
  local raw="$1" value
  raw="${raw%$'\r'}"
  raw="$(printf '%s' "$raw" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
  case "$raw" in
    \"*)
      if [[ "$raw" =~ ^\"([^\"]*)\"([[:space:]]*#.*)?$ ]]; then
        value="${BASH_REMATCH[1]}"
      else
        err "응답파일의 큰따옴표 값이 닫히지 않았거나 뒤에 잘못된 문자가 있습니다."
        return 1
      fi
      ;;
    \'*)
      if [[ "$raw" =~ ^\'([^\']*)\'([[:space:]]*#.*)?$ ]]; then
        value="${BASH_REMATCH[1]}"
      else
        err "응답파일의 작은따옴표 값이 닫히지 않았거나 뒤에 잘못된 문자가 있습니다."
        return 1
      fi
      ;;
    *)
      if [[ "$raw" == \#* ]]; then
        value=""
      else
        value="$(printf '%s' "$raw" | sed -e 's/[[:space:]]#.*$//' -e 's/[[:space:]]*$//')"
      fi
      ;;
  esac
  printf '%s' "$value"
}

validate_image_transfer_mode() {
  case "$1" in
    ""|push|registry) return 0 ;;
    *) err "초기 이미지 전달 방식은 push 또는 registry여야 합니다."; return 1 ;;
  esac
}

load_answers() {
  local f="$1" line key raw value line_no=0 seen='|'
  [[ -f "$f" ]] || { err "응답파일이 없습니다: $f"; return 1; }
  while IFS= read -r line || [[ -n "$line" ]]; do
    line_no=$((line_no + 1))
    line="${line%$'\r'}"
    [[ "$line" =~ ^[[:space:]]*$ || "$line" =~ ^[[:space:]]*# ]] && continue
    if [[ ! "$line" =~ ^[[:space:]]*([A-Z][A-Z0-9_]*)[[:space:]]*=(.*)$ ]]; then
      err "$f:$line_no: KEY=VALUE 형식이 아닙니다."
      return 1
    fi
    key="${BASH_REMATCH[1]}"; raw="${BASH_REMATCH[2]}"
    answer_key_allowed "$key" || { err "$f:$line_no: 허용되지 않은 응답 키입니다: $key"; return 1; }
    [[ "$seen" != *"|$key|"* ]] || { err "$f:$line_no: 중복 응답 키입니다: $key"; return 1; }
    value="$(parse_answer_value "$raw")" || { err "$f:$line_no: 값 해석 실패: $key"; return 1; }
    if [[ "$key" == DEPLOYOPS_IMAGE_TRANSFER_MODE ]]; then
      validate_image_transfer_mode "$value" || return 1
    fi
    if [[ "$key" == NEXUS_ISOLATED_RESTORE_ENABLED && "$value" != true && "$value" != false ]]; then
      err "Nexus 격리복원 설정은 true 또는 false여야 합니다."; return 1
    fi
    printf -v "$key" '%s' "$value"
    export "$key"
    seen+="$key|"
  done < "$f"
  info "응답파일 로드: $f"
}

# ask VAR "프롬프트" "기본값" — 환경값 > (무인=기본값) > 대화형 입력
ask() {
  local var="$1" msg="$2" def="${3:-}"
  if [[ -n "${!var:-}" ]]; then printf '%s' "${!var}"; return 0; fi
  if [[ "${UNATTENDED:-0}" == "1" ]]; then printf '%s' "$def"; return 0; fi
  prompt_input "$msg" "$def"
}

# ask_secret VAR "프롬프트" [allow_empty] — 무인 모드에선 환경값 필수(allow_empty 제외)
ask_secret() {
  local var="$1" msg="$2" allow="${3:-}"
  if [[ -n "${!var:-}" ]]; then printf '%s' "${!var}"; return 0; fi
  if [[ "${UNATTENDED:-0}" == "1" ]]; then
    [[ "$allow" == "allow_empty" ]] && return 0
    err "무인 모드: 필수 값 \$$var 가 없습니다 (응답파일/환경변수로 지정하세요)."
    return 1
  fi
  prompt_secret "$msg"
}

# ask_secret_or_generate VAR "설명" — 비워 두면 안전한 임의값을 만들어 준다.
#   시크릿은 사람이 openssl 을 쳐서 만들 이유가 없다. 직접 만든 값을 쓰고 싶으면 입력하면 되고,
#   그냥 엔터를 치면 여기서 만든다. 만든 값은 호출 측이 요약 파일에 남긴다.
ask_secret_or_generate() {
  local var="$1" msg="$2" v
  v="${!var:-}"
  if [[ -z "$v" && "${UNATTENDED:-0}" != "1" ]]; then
    v="$(prompt_secret "$msg [엔터 = 자동 생성]: ")"
  fi
  if [[ -z "$v" ]]; then
    v="$(generate_secret)" || return 1
  fi
  printf '%s' "$v"
}

# 32바이트 랜덤을 hex 로. openssl 이 없으면 커널 엔트로피로 대체한다.
generate_secret() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 32
  elif [[ -r /dev/urandom ]]; then
    head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n'
  else
    err "임의값을 만들 수 없습니다(openssl·/dev/urandom 둘 다 없음)."
    return 1
  fi
}

# ask_confirm VAR "질문" [무인기본 y|n] — 환경값(y/yes/true/1=참) > (무인=기본) > 대화형
ask_confirm() {
  local var="$1" msg="$2" def="${3:-n}"
  local v="${!var:-}"
  if [[ -n "$v" ]]; then
    v="$(printf '%s' "$v" | tr '[:upper:]' '[:lower:]')"
    [[ "$v" == "y" || "$v" == "yes" || "$v" == "true" || "$v" == "1" ]]
    return $?
  fi
  if [[ "${UNATTENDED:-0}" == "1" ]]; then [[ "$def" == y* ]]; return $?; fi
  confirm "$msg"
}
