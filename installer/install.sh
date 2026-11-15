#!/usr/bin/env bash
# install.sh - Office System 통합 오프라인 설치기 (Rocky/Ubuntu 공용)
#   - OS 자동감지(dnf/apt) · 컴포넌트 통합 메뉴 · 설치여부 표시 · 권장 순서 안내
#   - 바이너리는 bundles/<OS>/<component>/ (OS무관 항목은 bundles/common/)
#
# 무인(unattended) 모드:
#   sudo ./install.sh --unattended --answers answers.env --components all
#   sudo ./install.sh -u --answers answers.env --components docker,mariadb,deploy-system
#   - 응답 키 목록/예시: answers.example.env (FORCE_REINSTALL=y 면 설치된 것도 재설치)
set -uo pipefail

assert_trusted_staging_tree() {
  local tree="$1" label="$2" lexical="$1" cursor=/ part parent owner mode mode_bits entry
  [[ "$lexical" == /* ]] || { err "$label must be an absolute path for a real install"; return 1; }
  case "/$lexical/" in
    *"/../"*|*"/./"*) err "$label must not contain dot path segments"; return 1 ;;
  esac
  lexical="${lexical%/}"
  while [[ -n "$lexical" ]]; do
    part="${lexical%%/*}"
    if [[ -n "$part" ]]; then
      cursor="${cursor%/}/$part"
      [[ ! -L "$cursor" ]] || { err "$label path must not contain symlinks: $cursor"; return 1; }
    fi
    [[ "$lexical" == */* ]] || break
    lexical="${lexical#*/}"
  done
  [[ -d "$tree" ]] || { err "$label directory does not exist: $tree"; return 1; }
  cursor="$(readlink -f "$tree" 2>/dev/null || true)"
  [[ -n "$cursor" ]] || { err "$label cannot be resolved: $tree"; return 1; }

  parent="$cursor"
  while :; do
    owner="$(stat -c '%u' "$parent" 2>/dev/null || true)"
    mode="$(stat -c '%a' "$parent" 2>/dev/null || true)"
    [[ "$owner" == 0 && "$mode" =~ ^[0-7]{3,4}$ ]] \
      || { err "$label ancestors must be owned by root: $parent"; return 1; }
    mode_bits=$((8#$mode))
    if (( (mode_bits & 8#22) != 0 && (mode_bits & 8#1000) == 0 )); then
      err "$label ancestors must not be group/world writable (unless sticky): $parent"
      return 1
    fi
    [[ "$parent" != / ]] || break
    parent="$(dirname "$parent")"
  done

  # Keep the original scopes: links across mount boundaries, ownership/mode on
  # this filesystem. GNU find evaluates metadata without per-file stat forks.
  local unsafe
  if ! unsafe="$(find -P "$cursor" -type l -printf 'unsafe\n' -quit)"; then
    err "$label symbolic-link inspection failed"
    return 1
  fi
  if [[ -n "$unsafe" ]]; then
    err "$label must not contain symbolic links"
    return 1
  fi
  if ! unsafe="$(find -P "$cursor" -xdev \( -type f -o -type d \) \( ! -uid 0 -o -perm /022 \) -printf 'unsafe\n' -quit)"; then
    err "$label ownership/permission inspection failed"
    return 1
  fi
  if [[ -n "$unsafe" ]]; then
    err "$label contents must be root-owned and not group/world writable"
    return 1
  fi
}

# Root must not execute/source mutable installation media before checking it.
if (( EUID == 0 )); then
  err() { printf '[ERROR] %s\n' "$*" >&2; }
  installer_entry="${BASH_SOURCE[0]}"
  [[ "$installer_entry" == /* ]] || installer_entry="$PWD/$installer_entry"
  case "/$installer_entry/" in
    *"/../"*) err 'Installer entry must not contain parent path segments'; exit 1 ;;
  esac
  [[ ! -L "$installer_entry" ]] || { err 'Installer entry must not be a symlink'; exit 1; }
  installer_parent="$(realpath -ms -- "$(dirname "$installer_entry")")" || exit 1
  assert_trusted_staging_tree "$installer_parent" 'Installer source' || exit 1
  unset installer_entry installer_parent
fi

# Plain interactive entry; unattended and validation contracts are unchanged.
if [[ $# -eq 0 || ( $# -eq 1 && "$1" == --wizard ) ]]; then
  exec bash "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/interactive-install.sh"
fi
if [[ $# -eq 1 && "$1" == --legacy-menu ]]; then
  shift
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUNDLE_ROOT="${BUNDLE_ROOT:-$SCRIPT_DIR/bundles}"

# Refuse malformed source before loading any component or changing products.
# A sourced file can fail midway and still be followed by successful definitions
# when errexit is disabled, so syntax is checked independently first.
for source_file in "$SCRIPT_DIR"/lib/*.sh "$SCRIPT_DIR"/components/*.sh; do
  if ! bash -n "$source_file"; then
    printf '[ERROR] Installer source syntax invalid: %s\n' "$source_file" >&2
    exit 1
  fi
done
unset source_file

# shellcheck source=lib/release-metadata.sh
. "$SCRIPT_DIR/lib/release-metadata.sh" || exit 1
BOM_FILE="$BUNDLE_ROOT/RELEASE-BOM.env"
load_release_bom "$BOM_FILE" || exit 1
readonly SCRIPT_DIR BUNDLE_ROOT BOM_FILE
readonly BOM_SCHEMA_VERSION BOM_RELEASE BOM_GITLAB_IMAGE BOM_RUNNER_IMAGE
readonly BOM_RUNNER_HELPER_IMAGE BOM_RUNNER_BUILD_IMAGE BOM_NEXUS_IMAGE
readonly BOM_JAVA_VERSION BOM_JAVA_ARCHIVE BOM_JAVA_SHA256
readonly BOM_JQ_VERSION BOM_JQ_SHA256
readonly BOM_DEPLOYOPS_JAR BOM_DEPLOYOPS_VERSION BOM_DEPLOYOPS_REQUIRED_MIGRATIONS
readonly BOM_WORKSYSTEM_INCLUDED BOM_WORKSYSTEM_JAR BOM_WORKSYSTEM_VERSION

# shellcheck source=lib/ui.sh
. "$SCRIPT_DIR/lib/ui.sh" || exit 1
# shellcheck source=lib/os.sh
. "$SCRIPT_DIR/lib/os.sh" || exit 1
# shellcheck source=components/common.sh
. "$SCRIPT_DIR/components/common.sh" || exit 1
. "$SCRIPT_DIR/components/docker.sh" || exit 1
. "$SCRIPT_DIR/components/mariadb.sh" || exit 1
. "$SCRIPT_DIR/components/gitlab.sh" || exit 1
. "$SCRIPT_DIR/components/gitlab-runner.sh" || exit 1
. "$SCRIPT_DIR/components/nexus.sh" || exit 1
. "$SCRIPT_DIR/components/deploy-system.sh" || exit 1
. "$SCRIPT_DIR/components/worksystem-server.sh" || exit 1
. "$SCRIPT_DIR/components/nginx.sh" || exit 1
. "$SCRIPT_DIR/lib/target-bootstrap.sh" || exit 1
. "$SCRIPT_DIR/components/target-prerequisites.sh" || exit 1
. "$SCRIPT_DIR/components/deploy-agent.sh" || exit 1
readonly GITLAB_IMAGE RUNNER_IMAGE RUNNER_HELPER_IMAGE RUNNER_BUILD_IMAGE NEXUS_IMAGE

# 컴포넌트 레지스트리: key|표시명|status함수|install함수|권장단계|번들유형(pkg|image|jar)
COMPONENTS=(
  "nginx|Nginx DMZ 프록시 (DMZ 호스트용)|nginx_status|nginx_install|0|pkg"
  "docker|Docker 엔진|docker_status|docker_install|1|pkg"
  "mariadb|MariaDB (DB 기반)|mariadb_status|mariadb_install|2|pkg"
  "gitlab|GitLab (docker 이미지)|gitlab_status|gitlab_install|3|image"
  "gitlab-runner|GitLab Runner (docker 이미지)|gitlab_runner_status|gitlab_runner_install|3|image"
  "nexus|Nexus (docker 이미지)|nexus_status|nexus_install|4|image"
  "deploy-system|배포시스템 (deploy-ops)|deploy_system_status|deploy_system_install|5|jar"
  "worksystem-server|오피스 웹 (worksystem-server)|worksystem_server_status|worksystem_server_install|5|jar"
  "deploy-agent|배포 에이전트 (배포 대상 서버용)|deploy_agent_status|deploy_agent_install|6|none"
)

field() { printf '%s' "$1" | cut -d'|' -f"$2"; }

# Convert the command-line component list once so validation and installation
# always operate on the identical plan.
component_keys() {
  local -n result_ref="$1"
  local entry
  result_ref=()
  if [[ "$COMPONENT_LIST" == "all" ]]; then
    for entry in "${COMPONENTS[@]}"; do
      [[ "$(field "$entry" 1)" == worksystem-server && "$BOM_WORKSYSTEM_INCLUDED" == false ]] && continue
      result_ref+=("$(field "$entry" 1)")
    done
  else
    IFS=',' read -r -a result_ref <<<"$COMPONENT_LIST"
  fi
}

has_component() {
  local needle="$1" item
  shift
  for item in "$@"; do [[ "$item" == "$needle" ]] && return 0; done
  return 1
}

is_placeholder_value() {
  local value="${1,,}"
  [[ "$value" == *change-me* || "$value" == *replace-me* || "$value" == *example* || "$value" == *'<'* || "$value" == *'>'* ]]
}

is_port() { [[ "$1" =~ ^[0-9]{1,5}$ ]] && (( 10#$1 >= 1 && 10#$1 <= 65535 )); }
is_yes_no() { [[ "${1,,}" =~ ^(y|yes|true|1|n|no|false|0)$ ]]; }
is_true_value() { [[ "${1,,}" =~ ^(y|yes|true|1)$ ]]; }

validate_secret_value() {
  local name="$1" value="${!1:-}"
  [[ -n "$value" ]] || { err "Missing required value: $name"; return 1; }
  is_placeholder_value "$value" && { err "Placeholder value is not allowed: $name"; return 1; }
  [[ ${#value} -ge 12 ]] || { err "Value is too short (minimum 12 characters): $name"; return 1; }
}

validate_bundle_for_component() {
  local key="$1" entry bundle_type
  component_is_included "$key" || return 1
  entry="$(find_component "$key")" || { err "Unknown component: $key"; return 1; }
  bundle_type="$(field "$entry" 6)"
  case "$bundle_type" in
    pkg) bundle_has_packages "$key" || { err "Missing $BUNDLE_OS package bundle for $key"; return 1; } ;;
    image) bundle_has_image "$key" || { err "Missing image archive bundle for $key"; return 1; } ;;
    jar)
      if [[ "$key" == "deploy-system" ]]; then
        [[ -f "$BUNDLE_ROOT/$BOM_DEPLOYOPS_JAR" ]] \
          || { err "Missing application JAR bundle: $BOM_DEPLOYOPS_JAR"; return 1; }
      else
        [[ -f "$BUNDLE_ROOT/$BOM_WORKSYSTEM_JAR" ]] || { err "Missing application JAR bundle: $BOM_WORKSYSTEM_JAR"; return 1; }
      fi
      ;;
    none)
      [[ -f "$SCRIPT_DIR/deploy-agent/install-deploy-agent.sh" ]] || { err "Missing deploy-agent source"; return 1; }
      bundle_has_packages target-prerequisites || { err "Missing target-prerequisites RPM bundle"; return 1; }
      ;;
  esac
}

component_is_included() {
  if [[ "$1" == worksystem-server && "$BOM_WORKSYSTEM_INCLUDED" == false ]]; then
    err "이 Deploy 전용 패키지에는 Worksystem이 포함되지 않았습니다. 별도의 Worksystem 설치 패키지를 사용하세요."
    return 1
  fi
}

validate_install_plan() {
  local keys=() key errors=0
  [[ -n "${COMPONENT_LIST:-}" ]] || { err "--components is required for --validate"; return 1; }
  component_keys keys
  for key in "${keys[@]}"; do
    find_component "$key" >/dev/null || { err "Unknown component: $key"; errors=$((errors + 1)); continue; }
    validate_bundle_for_component "$key" || errors=$((errors + 1))
  done
  (( errors == 0 )) || return 1


  preflight_answers "${keys[@]}" || return 1
  for key in "${keys[@]}"; do
    case "$key" in
      nexus)
        nexus_validate_ports || return 1
        nexus_validate_maven_options || return 1
        nexus_secrets_validate_path || return 1
        ;;
      deploy-system)
        # DB 접속·포트·암호화키는 설정을 포함한 JAR에서 읽는다.
        deploy_system_validate_embedded_config || return 1
        ;;
      worksystem-server)
        validate_secret_value WORKSYSTEM_DB_PASSWORD || return 1
        validate_secret_value WORKSYSTEM_ADMIN_PASSWORD || return 1
        validate_secret_value SSO_SHARED_SECRET || return 1
        validate_secret_value ANONYMITY_SECRET || return 1
        is_port "${WORKSYSTEM_DB_PORT:-3306}" || { err "Invalid WORKSYSTEM_DB_PORT"; return 1; }
        is_port "${WORKSYSTEM_APP_PORT:-18080}" || { err "Invalid WORKSYSTEM_APP_PORT"; return 1; }
        ;;
      deploy-agent)
        case "${DEPLOY_AGENT_TYPE:-}" in was|web|deploy|nexus|gitlab|runner) ;; *) err "DEPLOY_AGENT_TYPE must be was, web, deploy, nexus, gitlab, or runner"; return 1 ;; esac
        case "${DEPLOY_AGENT_TRANSPORT:-agent}" in
          agent)
            [[ "${DEPLOY_AGENT_TYPE}" != both ]] || { err 'Pull agent requires one WAS or WEB role'; return 1; }
            [[ "${DEPLOY_AGENT_SERVER_ID:-}" =~ ^[1-9][0-9]*$ ]] || { err 'DEPLOY_AGENT_SERVER_ID must be a registered server ID'; return 1; }
            [[ "${DEPLOY_AGENT_ENROLLMENT_CODE_FILE:-}" == /* && -f "${DEPLOY_AGENT_ENROLLMENT_CODE_FILE}" && ! -L "${DEPLOY_AGENT_ENROLLMENT_CODE_FILE}"
              && "$(stat -c '%u:%a' "${DEPLOY_AGENT_ENROLLMENT_CODE_FILE}")" == '0:600' ]] \
              || { err 'DEPLOY_AGENT_ENROLLMENT_CODE_FILE must be a root-owned mode600 file'; return 1; }
            case "${DEPLOY_AGENT_ALLOW_HTTP:-n}" in y|n) ;; *) err 'DEPLOY_AGENT_ALLOW_HTTP must be y or n'; return 1 ;; esac
            [[ "${DEPLOY_AGENT_CONTROL_URL:-}" == https://* || ( "${DEPLOY_AGENT_ALLOW_HTTP:-n}" == y && "${DEPLOY_AGENT_CONTROL_URL:-}" == http://* ) ]] \
              || { err 'Agent URL requires HTTPS (explicit HTTP opt-in only for isolated QA)'; return 1; }
            ;;
          *) err 'DEPLOY_AGENT_TRANSPORT must be agent'; return 1 ;;
        esac
        is_port "${DEPLOY_AGENT_HEALTH_PORT:-}" || { err "Invalid DEPLOY_AGENT_HEALTH_PORT"; return 1; }
        ;;
      gitlab-runner)
        if is_true_value "${GITLAB_RUNNER_REGISTER:-n}"; then
          [[ -n "${GITLAB_RUNNER_URL:-}" ]] || { err "GITLAB_RUNNER_URL is required when runner registration is enabled"; return 1; }
          [[ "${GITLAB_RUNNER_DOCKER_IMAGE:-$RUNNER_BUILD_IMAGE}" == "$RUNNER_BUILD_IMAGE" ]] \
            || { err "GITLAB_RUNNER_DOCKER_IMAGE must match the verified offline bundle image: $RUNNER_BUILD_IMAGE"; return 1; }
          if [[ -n "${GITLAB_RUNNER_CLONE_URL:-}" ]]; then
            runner_clone_url_valid "$GITLAB_RUNNER_CLONE_URL" \
              || { err "GITLAB_RUNNER_CLONE_URL must be a credential-free http(s) URL without query or whitespace"; return 1; }
          fi
          if [[ -n "${GITLAB_RUNNER_TOKEN:-}" ]]; then
            [[ "${GITLAB_RUNNER_TOKEN}" == glrt-* ]] \
              || { err "GITLAB_RUNNER_TOKEN must be a glrt-* authentication token"; return 1; }
            [[ "${GITLAB_RUNNER_ID:-}" =~ ^[1-9][0-9]*$ ]] \
              || { err "GITLAB_RUNNER_ID must be a positive integer for an existing Runner"; return 1; }
            [[ -n "${GITLAB_RUNNER_VERIFY_API_TOKEN:-}" ]] \
              || { err "GITLAB_RUNNER_VERIFY_API_TOKEN is required to re-query an existing Runner policy"; return 1; }
            case "${GITLAB_RUNNER_TYPE:-project_type}" in
              project_type|group_type|instance_type) ;;
              *) err "Invalid GITLAB_RUNNER_TYPE"; return 1 ;;
            esac
          elif [[ -n "${GITLAB_RUNNER_API_TOKEN:-}" ]]; then
            case "${GITLAB_RUNNER_TYPE:-project_type}" in
              project_type) [[ "${GITLAB_RUNNER_PROJECT_ID:-}" =~ ^[1-9][0-9]*$ ]] || { err "GITLAB_RUNNER_PROJECT_ID must be a positive integer for project_type"; return 1; } ;;
              group_type) [[ "${GITLAB_RUNNER_GROUP_ID:-}" =~ ^[1-9][0-9]*$ ]] || { err "GITLAB_RUNNER_GROUP_ID must be a positive integer for group_type"; return 1; } ;;
              instance_type) ;;
              *) err "Invalid GITLAB_RUNNER_TYPE"; return 1 ;;
            esac
          else
            err "GITLAB_RUNNER_API_TOKEN or a verifiable existing GITLAB_RUNNER_TOKEN is required when registration is enabled"
            return 1
          fi
          is_yes_no "${GITLAB_RUNNER_RUN_UNTAGGED:-n}" || { err "Invalid GITLAB_RUNNER_RUN_UNTAGGED"; return 1; }
          is_yes_no "${GITLAB_RUNNER_PROTECTED:-y}" || { err "Invalid GITLAB_RUNNER_PROTECTED"; return 1; }
          is_yes_no "${GITLAB_RUNNER_LOCKED:-y}" || { err "Invalid GITLAB_RUNNER_LOCKED"; return 1; }
          if [[ -z "${GITLAB_RUNNER_TAG_LIST:-docker,offline}" ]] \
              && ! is_true_value "${GITLAB_RUNNER_RUN_UNTAGGED:-n}"; then
            err "GITLAB_RUNNER_TAG_LIST is required when untagged jobs are disabled"
            return 1
          fi
        fi
        ;;
      nginx)
        is_placeholder_value "${NGINX_UPSTREAM_HOST:-}" && { err "Placeholder value is not allowed: NGINX_UPSTREAM_HOST"; return 1; }
        is_port "${NGINX_STATIC_LISTEN:-80}" || { err "Invalid NGINX_STATIC_LISTEN"; return 1; }
        ;;
    esac
  done

  if has_component nginx "${keys[@]}" && has_component deploy-agent "${keys[@]}" && [[ "${DEPLOY_AGENT_TYPE:-}" == "was" ]]; then
    err "nginx with deploy-agent requires DEPLOY_AGENT_TYPE=web or both"
    return 1
  fi
  if has_component docker "${keys[@]}" && has_component deploy-agent "${keys[@]}" && [[ "${DEPLOY_AGENT_TYPE:-}" == "web" ]]; then
    err "docker with deploy-agent requires DEPLOY_AGENT_TYPE=was or both"
    return 1
  fi
  success "Install plan validation passed: ${keys[*]} (bundle: $BUNDLE_OS)"
}

find_component() { # key → entry (없으면 실패)
  local entry
  for entry in "${COMPONENTS[@]}"; do
    [[ "$(field "$entry" 1)" == "$1" ]] && { printf '%s' "$entry"; return 0; }
  done
  return 1
}

list_components() {
  section "컴포넌트 키"
  local entry
  for entry in "${COMPONENTS[@]}"; do
    printf '  %-18s %s\n' "$(field "$entry" 1)" "$(field "$entry" 2)"
  done
}

usage() {
  cat <<'EOF'
Additional validation mode:
  --validate                Validate --answers and --components without changing the host
  --require-java-ci         Require the signed Java CI asset payload (does not attest cold CI/import)
  --bundle-os rocky8|ubuntu Select a bundle only when used with --validate

사용법: sudo ./install.sh [옵션]
  (옵션 없음), --wizard       서버 정보 입력·저장·재개 대화형 설치기 (root)
      --legacy-menu          기존 구성요소 역할 메뉴 (고급)
  -u, --unattended            무인 모드 (프롬프트 없음, 응답파일/환경변수 사용)
      --answers FILE          응답파일(KEY=VALUE) 로드 — answers.example.env 참조
      --components KEYS       무인 모드 설치 대상: 콤마구분 키 또는 all
      --list                  컴포넌트 키 목록 출력
  -h, --help                  도움말
예:
  sudo ./install.sh -u --answers answers.env --components all
  sudo ./install.sh -u --answers answers.env --components docker,mariadb,deploy-system
EOF
}

# 무인 모드에서 컴포넌트별 필수 시크릿(빈 기본값이 허용되지 않는 값) 사전 점검
#   ★ SSO_SHARED_SECRET · DEPLOYOPS_CRYPTO_KEY 는 여기서 요구하지 않는다 —
#     형식이 정해진 임의값이라 비워 두면 설치기가 만든다(ask_secret_or_generate).
#     생성값은 config/설치결과.txt 에 남고 매일 백업에 포함된다.
#     오피스 웹을 같이 쓸 때만 SSO 키를 "양쪽 동일한 값"으로 직접 지정하면 된다.
required_vars_for() {
  case "$1" in
    deploy-system)      echo "" ;;
    worksystem-server)  echo "WORKSYSTEM_DB_PASSWORD SSO_SHARED_SECRET ANONYMITY_SECRET" ;;
    nginx)              echo "NGINX_UPSTREAM_HOST" ;;
    *)                  echo "" ;;
  esac
}

preflight_answers() {
  local key var missing=()
  for key in "$@"; do
    for var in $(required_vars_for "$key"); do
      [[ -n "${!var:-}" ]] || missing+=("$key → \$$var")
    done
  done
  if (( ${#missing[@]} > 0 )); then
    err "무인 모드: 다음 필수 값이 응답파일에 없습니다."
    local m; for m in "${missing[@]}"; do err "  - $m"; done
    err "answers.example.env 를 참고해 채워 주세요."
    return 1
  fi
  return 0
}

component_checkpoint() {
  [[ -n "${COMPONENT_STATE_DIR:-}" ]] || return 0
  local path="$COMPONENT_STATE_DIR/component-$1.status" tmp
  tmp="$(mktemp "$COMPONENT_STATE_DIR/.component-status.XXXXXX")" || return 1
  printf '%s\n' "$2" > "$tmp" && chmod 600 "$tmp" && mv -fT -- "$tmp" "$path" || {
    rm -f -- "$tmp"; return 1
  }
}

run_unattended() {
  if [[ -z "${COMPONENT_LIST:-}" ]]; then
    err "무인 모드: --components <키,키,... | all> 이 필요합니다."
    list_components
    return 1
  fi
  local keys=() entry key st component_rc
  component_keys keys
  for key in "${keys[@]}"; do
    find_component "$key" >/dev/null || { err "알 수 없는 컴포넌트: $key"; list_components; return 1; }
  done
  validate_install_plan || return 1

  section "무인 설치 시작: ${keys[*]}"
  for key in "${keys[@]}"; do
    entry="$(find_component "$key")"
    st="$("$(field "$entry" 3)" 2>/dev/null || echo missing)"
    if [[ "$st" == "installed" && "${FORCE_REINSTALL:-n}" != y* && "${FORCE_REINSTALL:-n}" != Y* ]]; then
      component_checkpoint "$key" VERIFIED || return 1
      info "[skip] $(field "$entry" 2) — 이미 설치됨 (FORCE_REINSTALL=y 로 재설치 가능)"
      continue
    fi
    section "설치: $(field "$entry" 2)"
    local force=""
    [[ "${FORCE_REINSTALL:-n}" == y* || "${FORCE_REINSTALL:-n}" == Y* ]] && force="force"
    component_checkpoint "$key" RUNNING || return 1
    "$(field "$entry" 4)" "$force" || {
      component_rc=$?
      if [[ "$key" == nexus && "$component_rc" == "$NEXUS_EULA_PENDING_RC" ]]; then
        component_checkpoint "$key" WAITING_NEXUS_WEB || return 1
        warn "Nexus 약관 처리 대기: Nexus UI에서 약관을 검토·처리한 뒤 같은 설치 명령을 다시 실행하세요."
        return "$component_rc"
      fi
      component_checkpoint "$key" FAILED || return 1
      err "무인 설치 실패: $key"; return 1
    }
    component_checkpoint "$key" INSTALLED || return 1
  done
  success "무인 설치 완료."
}

print_order() {
  section "권장 설치 순서"
  printf '  1. Docker 엔진        - GitLab/Nexus/Runner 이미지 구동 기반\n'
  printf '  2. MariaDB            - 모든 앱의 DB 기반 (OS 패키지)\n'
  printf '  3. GitLab / Runner    - docker 이미지\n'
  printf '  4. Nexus              - docker 이미지 (아티팩트/레지스트리)\n'
  printf '  5. deploy-system /    - 배포시스템(+오피스 웹) JAR. MariaDB 필요, GitLab/Nexus 연동.\n'
  printf '     worksystem-server\n'
  info "Docker→DB 먼저, 앱(배포시스템/오피스웹)을 마지막에 설치하면 연동 검증이 쉽습니다."
}

print_env_summary() {
  section "실행 환경"
  kv "OS" "$PRETTY_OS ($OS_ID ${OS_VER:-})"
  kv "패키지 관리자" "${PKG:-<미지원>}"
  kv "번들 OS 디렉터리" "bundles/$BUNDLE_OS"
  kv "번들 루트" "$BUNDLE_ROOT"
  if ! os_supported; then
    warn "지원 OS(rhel 계열/debian 계열)가 아닙니다. 패키지 설치가 동작하지 않을 수 있습니다."
  fi
}

# 서버 역할 레지스트리: 키|표시명|구역|설치할 컴포넌트(순서대로)|한 줄 설명
#   "이 서버에서 뭘 골라야 하나"를 설치기가 답해 준다. 이게 없으면 처음 설치하는 사람은
#   전체 설치(a)를 누르게 되는데, 그러면 DMZ 에 docker·GitLab·DB 까지 깔린다(설계 위반).
ROLES=(
  "deploy|배포시스템 서버 (deploy01)|배포망|docker,mariadb,nexus,deploy-system|배포시스템 + 배포이력 DB + 산출물 저장소"
  "gitlab|형상관리 서버 (gitlab01)|배포망|docker,gitlab|GitLab. Runner 는 GitLab 기동 후 2차 실행"
  "gitlab-runner|빌드 실행기 추가 (gitlab01 2차)|배포망|gitlab-runner|GitLab 토큰 발급 후 실행"
  "office|오피스 웹 서버 (office01)|업무망|worksystem-server|ERP/HR 웹과 배포 알림 수신 서버"
  "was|배포 대상 WAS (devwas1/prdwas1/prdwas2)|개발망·운영망|docker,deploy-agent|앱 컨테이너를 받는 서버"
  "web|배포 대상 WEB (devweb1/prdweb1/prdweb2)|개발망·운영 DMZ|nginx,deploy-agent|정적 산출물 + 리버스 프록시. docker 를 깔지 않는다"
)

print_role_menu() {
  section "이 서버의 역할을 고르세요"
  local idx=1 entry
  for entry in "${ROLES[@]}"; do
    printf '  %d) %-38s %-14s %s\n' "$idx" \
      "$(field "$entry" 2)" "[$(field "$entry" 3)]" "$(field "$entry" 5)"
    idx=$((idx+1))
  done
  printf '  m) 컴포넌트를 직접 고르기(고급)\n'
  printf '  q) 종료\n'
}

# 역할이 정한 컴포넌트만, 정해진 순서로 설치한다.
run_role() {
  local entry="$1" name keys key comp
  name="$(field "$entry" 2)"; keys="$(field "$entry" 4)"
  local -a selected_keys=()
  IFS=',' read -r -a selected_keys <<< "$keys"
  for key in "${selected_keys[@]}"; do component_is_included "$key" || return 1; done
  section "역할 설치: $name"
  info "설치할 컴포넌트: $keys"
  confirm "이대로 진행할까요?" || { info "취소했습니다."; return 0; }

  local IFS_OLD="$IFS"; IFS=','
  for key in $keys; do
    IFS="$IFS_OLD"
    comp="$(find_component "$key")" || { err "알 수 없는 컴포넌트: $key"; return 1; }
    if ! run_component "$comp"; then
      IFS="$IFS_OLD"
      err "중단: '$key' 설치에 실패했습니다."
      cat <<EOS

  이미 끝난 컴포넌트는 그대로 남아 있습니다. 원인을 고친 뒤 이 설치기를 다시 실행하고
  같은 역할을 고르면, 설치된 것은 건너뛰고 남은 것부터 이어서 진행합니다.

  자주 겪는 원인:
   · 필수 입력을 비웠다 — 빈 엔터로 넘길 수 없는 항목이 있습니다(IP·비밀번호 등)
   · 번들이 없다 — 위 [컴포넌트 상태] 에 [번들없음] 이 붙어 있는지 확인하세요
   · 디스크·권한 — 위에 찍힌 마지막 오류 줄을 그대로 보고해 주세요
EOS
      return 1
    fi
    IFS=','
  done
  IFS="$IFS_OLD"
  success "역할 설치 완료: $name"
  _role_next_steps "$(field "$entry" 1)"
}

# 설치기가 대신 해 줄 수 없는 다음 단계를 역할별로 알려 준다.
# (토큰 발급·키 배포·통합설정은 사람이 화면에서 해야 한다)
_role_next_steps() {
  section "다음에 할 일"
  case "$1" in
    deploy)
      cat <<'EOS'
  1) 브라우저에서 Deploy에 로그인하고 관리자 비밀번호를 변경합니다.
  2) GitLab·Nexus URL과 API 자격정보를 통합설정에 등록합니다.
  3) 서버 관리에서 대상 서버를 등록하고 일회성 에이전트 등록 코드를 발급합니다.
  4) 대상 서버 설치기에 Deploy URL·서버 ID·등록 코드를 입력합니다.
  5) Deploy에서 등록을 승인한 뒤 연결·실행 준비 상태를 검사합니다.
EOS
      ;;
    gitlab)
      cat <<'EOS'
  1) 브라우저로 GitLab 접속 → 초기 root 비밀번호 확인 (24시간 뒤 삭제됩니다):
       docker exec worksystem-gitlab cat /etc/gitlab/initial_root_password
  2) 로그인 후 root 비밀번호 변경
  3) 전용 서비스계정 PAT 발급(create_runner + manage_runner + read_api) + 대상 Project/Group 숫자 ID 확인
     Project/Group Runner는 대상 Owner, Instance Runner는 GitLab Admin 역할이 필요합니다.
  4) 이 서버에서 설치기를 다시 실행해 "빌드 실행기 추가" 역할을 선택
EOS
      ;;
    gitlab-runner)
      cat <<'EOS'
  1) 등록 확인:  docker exec worksystem-gitlab-runner gitlab-runner verify
  2) 배포시스템 통합설정에 GitLab 주소와 전용 서비스 계정 PAT를 등록
     Runner 상세 API를 위해 manage_runner scope와 대상 Maintainer 이상 역할이 필요합니다.
EOS
      ;;
    was|web)
      cat <<'EOS'
  1) Deploy 서버 관리에서 이 서버의 역할과 IP를 등록합니다.
  2) 발급한 일회성 등록 코드로 에이전트를 등록합니다.
  3) Deploy에서 등록 승인 후 연결·실행 준비 검사를 실행합니다.
EOS
      ;;
  esac
  echo
}

print_status_table() {
  section "컴포넌트 상태"
  local idx=1 entry name statusfn st bdir bnote
  for entry in "${COMPONENTS[@]}"; do
    name="$(field "$entry" 2)"
    statusfn="$(field "$entry" 3)"
    st="$("$statusfn" 2>/dev/null || echo missing)"
    # 번들 존재 여부 표기 (번들유형별 판정)
    local key btype; key="$(field "$entry" 1)"; btype="$(field "$entry" 6)"
    bnote=""
    case "$btype" in
      pkg)   bundle_has_packages "$key" || bnote=" ${C_YELLOW}[번들없음]${C_RESET}" ;;
      image) bundle_has_image "$key"    || bnote=" ${C_YELLOW}[번들없음]${C_RESET}" ;;
      jar)   bundle_file "$key" '*.jar' >/dev/null 2>&1 || bnote=" ${C_YELLOW}[번들없음]${C_RESET}" ;;
    esac
    printf '  %d) %-34s %s%b\n' "$idx" "$name" "$(colorize_state "$st")" "$bnote"
    idx=$((idx+1))
  done
}

protect_baseline() {
  local guard_python=/usr/libexec/platform-python
  [[ -x "$guard_python" ]] || guard_python=/usr/bin/python3
  "$guard_python" -I -B "$SCRIPT_DIR/interactive_install.py" --prepare-baseline-lock || return 1
  exec {baseline_lock_fd}<>/var/lib/deploy-installer/upgrades/maintenance.lock || return 1
  flock -sn "$baseline_lock_fd" || { err "고도화 작업 실행 중입니다. 10번에서 확인하세요."; return 1; }
  BASELINE_VERIFIED="$("$guard_python" -I -B "$SCRIPT_DIR/interactive_install.py" --baseline-check "$1")"
}

run_component() (
  protect_baseline "$(field "$1" 1)" || return 1
  if [[ "$BASELINE_VERIFIED" == "$(field "$1" 1)" ]]; then
    info "현재 고도화 버전을 확인했습니다. 초기 반입 버전을 설치하지 않습니다."
    return 0
  fi
  component_is_included "$(field "$1" 1)" || return 1
  local entry="$1" key name installfn st force=""
  key="$(field "$entry" 1)"; name="$(field "$entry" 2)"; installfn="$(field "$entry" 4)"
  st="$("$(field "$entry" 3)" 2>/dev/null || echo missing)"
  section "설치: $name"
  if [[ "$st" == "installed" ]]; then
    confirm "$name 은(는) 이미 설치되어 있습니다. 강제 재설치할까요?" && force="force" || { info "건너뜁니다."; return 0; }
  fi
  "$installfn" "$force"
)

# 고급 사용자용 — 컴포넌트를 직접 고른다.
component_menu_loop() {
  local choice entry
  while true; do
    print_status_table
    echo
    warn "직접 고르기 모드입니다. 이 서버 역할에 필요 없는 것을 깔면 구성이 어긋납니다."
    info "번호=해당 컴포넌트 설치 / r=상태 새로고침 / b=역할 선택으로 / q=종료"
    choice="$(prompt_input "선택: " "")"
    case "$choice" in
      q|Q) info "종료합니다."; return 1 ;;
      b|B) return 0 ;;
      r|R) continue ;;
      ''|*[!0-9]*) warn "유효한 번호를 입력하세요." ;;
      *)
        if (( choice >= 1 && choice <= ${#COMPONENTS[@]} )); then
          entry="${COMPONENTS[$((choice-1))]}"
          run_component "$entry" || err "설치 실패: $(field "$entry" 2)"
        else
          warn "범위를 벗어난 번호입니다."
        fi
        ;;
    esac
  done
}

menu_loop() {
  local choice entry
  while true; do
    print_status_table
    echo
    print_role_menu
    choice="$(prompt_input "선택: " "")"
    case "$choice" in
      q|Q) info "종료합니다."; return 0 ;;
      m|M) component_menu_loop || return 0 ;;
      ''|*[!0-9]*) warn "번호 또는 m/q 를 입력하세요." ;;
      *)
        if (( choice >= 1 && choice <= ${#ROLES[@]} )); then
          entry="${ROLES[$((choice-1))]}"
          run_role "$entry" || true
        else
          warn "범위를 벗어난 번호입니다."
        fi
        ;;
    esac
  done
}


main() {
  COMPONENT_STATE_DIR=""; UNATTENDED=0; COMPONENT_LIST=""; local answers_file="" list_only=0 runtime_status=0 validate_only=0 bundle_os_override="" require_java_ci=0
  while [[ $# -gt 0 ]]; do
    case "$1" in
      -u|--unattended) UNATTENDED=1 ;;
      --component-state) COMPONENT_STATE_DIR="${2:-}"; shift ;;
      --answers)       answers_file="${2:-}"; shift ;;
      --components)    COMPONENT_LIST="${2:-}"; shift ;;
      --list)          list_only=1 ;;
      --validate)      validate_only=1 ;;
      --runtime-status) runtime_status=1 ;;
      --require-java-ci) require_java_ci=1 ;;
      --bundle-os)     bundle_os_override="${2:-}"; shift ;;
      -h|--help)       usage; exit 0 ;;
      *)               printf '알 수 없는 옵션: %s\n' "$1" >&2; usage; exit 1 ;;
    esac
    shift
  done
  export UNATTENDED
  if [[ -n "$COMPONENT_STATE_DIR" ]]; then
    [[ "$COMPONENT_STATE_DIR" =~ ^/root/deploy-installer/(deploy|gitlab|web|was)$ &&
       "$(readlink -e -- "$COMPONENT_STATE_DIR")" == "$COMPONENT_STATE_DIR" &&
       "$(stat -c '%u:%a' -- "$COMPONENT_STATE_DIR")" == 0:700 ]] ||
      { err "설치 상태 경로는 root 전용 역할 폴더여야 합니다."; exit 1; }
  fi

  init_ui
  if [[ -n "$COMPONENT_LIST" && "$COMPONENT_LIST" != all ]]; then
    local -a selected_keys=()
    local selected_key
    IFS=',' read -r -a selected_keys <<< "$COMPONENT_LIST"
    for selected_key in "${selected_keys[@]}"; do component_is_included "$selected_key" || exit 1; done
  fi
  banner
  if [[ "$list_only" == 1 ]]; then list_components; exit 0; fi
  detect_os
  if [[ -n "$bundle_os_override" ]]; then
    [[ "$validate_only" == 1 && ( "$bundle_os_override" == "rocky8" || "$bundle_os_override" == "ubuntu" ) ]] \
      || { err "--bundle-os is supported only with --validate: rocky8 or ubuntu"; exit 1; }
    BUNDLE_OS="$bundle_os_override"
  fi
  [[ -n "$answers_file" ]] && { load_answers "$answers_file" || exit 1; }
  if [[ "$runtime_status" == 1 ]]; then
    [[ -n "$COMPONENT_LIST" ]] || return 1
    load_release_lock "$BUNDLE_ROOT/RELEASE-LOCK.env" || return 1
    local status_keys=() status_entry status_key status_value
    component_keys status_keys
    for status_key in "${status_keys[@]}"; do
      status_entry="$(find_component "$status_key")" || return 1
      status_value="$("$(field "$status_entry" 3)" 2>/dev/null)" || status_value=unknown
      printf 'COMPONENT_STATUS %s=%s\n' "$status_key" "$status_value"
    done
    return 0
  fi
  print_env_summary
  if [[ "$validate_only" == 1 ]]; then
    if [[ "$require_java_ci" == 1 && ! -f "$BUNDLE_ROOT/common/java-ci/CI-PAYLOAD.json" ]]; then
      err 'Required Java CI payload is missing'; exit 1
    fi
    validate_install_plan
    exit $?
  fi
  os_supported || { err "현재 OS($OS_ID $OS_VER)는 이 설치 패키지의 지원 대상이 아닙니다. RPM 설치는 Rocky 8.10에서 실행하세요. OS 고도화에는 대상 OS 전용 패키지가 필요합니다."; exit 1; }
  require_root
  if [[ "$UNATTENDED" == 1 ]]; then
    protect_baseline "$COMPONENT_LIST" || exit 1
    if [[ -n "$BASELINE_VERIFIED" ]]; then
      local baseline_keys=() remaining=() key
      component_keys baseline_keys
      for key in "${baseline_keys[@]}"; do
        if grep -Fxq -- "$key" <<< "$BASELINE_VERIFIED"; then
          info "$key: 고도화된 서비스의 현재 버전과 상태를 확인했습니다."
          component_checkpoint "$key" VERIFIED || exit 1
        else
          remaining+=("$key")
        fi
      done
      (( ${#remaining[@]} )) || return 0
      COMPONENT_LIST="$(IFS=,; echo "${remaining[*]}")"
    fi
  fi
  assert_trusted_staging_tree "$SCRIPT_DIR" "installer tree" \
    && assert_trusted_staging_tree "$BUNDLE_ROOT" "release bundle tree" \
    || { err "호스트 변경 전 모든 installer/bundle 파일을 root-owned, non-writable staging 경로에 둬야 합니다."; exit 1; }
  info "설치에 필요한 파일·버전·아키텍처를 확인합니다."
  local verify_args=()
  [[ "$require_java_ci" == 0 ]] || verify_args+=(--require-java-ci)
  # 서명/공개키/해시 목록 인증은 설치 전제조건이 아니다.
  export OFFLINE_VERIFY_AUTHENTICITY=0
  env BUNDLE_ROOT="$BUNDLE_ROOT" BUNDLE_OS="$BUNDLE_OS" bash "$SCRIPT_DIR/verify-offline-bundle.sh" "${verify_args[@]}" \
    || { err "오프라인 번들 검증 실패 — 호스트 변경 전에 설치를 중단합니다."; exit 1; }
  load_release_lock "$BUNDLE_ROOT/RELEASE-LOCK.env" \
    || { err "검증된 release lock을 설치 프로세스에서 읽지 못했습니다."; exit 1; }
  readonly LOCK_SCHEMA_VERSION LOCK_BOM_RELEASE
  readonly LOCK_GITLAB_IMAGE LOCK_GITLAB_DIGEST LOCK_GITLAB_PINNED_REF LOCK_GITLAB_PLATFORM_DIGEST LOCK_GITLAB_CONFIG_DIGEST LOCK_GITLAB_ARCHIVE_ID
  readonly LOCK_RUNNER_IMAGE LOCK_RUNNER_DIGEST LOCK_RUNNER_PINNED_REF LOCK_RUNNER_PLATFORM_DIGEST LOCK_RUNNER_CONFIG_DIGEST LOCK_RUNNER_ARCHIVE_ID
  readonly LOCK_RUNNER_HELPER_IMAGE LOCK_RUNNER_HELPER_DIGEST LOCK_RUNNER_HELPER_PINNED_REF LOCK_RUNNER_HELPER_PLATFORM_DIGEST LOCK_RUNNER_HELPER_CONFIG_DIGEST LOCK_RUNNER_HELPER_ARCHIVE_ID
  readonly LOCK_RUNNER_BUILD_IMAGE LOCK_RUNNER_BUILD_DIGEST LOCK_RUNNER_BUILD_PINNED_REF LOCK_RUNNER_BUILD_PLATFORM_DIGEST LOCK_RUNNER_BUILD_CONFIG_DIGEST LOCK_RUNNER_BUILD_ARCHIVE_ID
  readonly LOCK_NEXUS_IMAGE LOCK_NEXUS_DIGEST LOCK_NEXUS_PINNED_REF LOCK_NEXUS_PLATFORM_DIGEST LOCK_NEXUS_CONFIG_DIGEST LOCK_NEXUS_ARCHIVE_ID
  readonly LOCK_DEPLOYOPS_JAR LOCK_DEPLOYOPS_VERSION LOCK_DEPLOYOPS_REQUIRED_MIGRATIONS LOCK_DEPLOYOPS_SHA256
  readonly LOCK_WORKSYSTEM_INCLUDED LOCK_WORKSYSTEM_JAR LOCK_WORKSYSTEM_VERSION LOCK_WORKSYSTEM_SHA256
  if [[ "$UNATTENDED" == "1" ]]; then
    run_unattended
    exit $?
  fi
  print_order
  menu_loop
}

main "$@"
