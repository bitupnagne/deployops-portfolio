#!/usr/bin/env bash
# deploy-agent.sh - 배포 "대상" 서버용 에이전트 설치 컴포넌트
#   배포시스템(deploy-ops)이 에이전트 작업으로 호출하는 deploy-{was,web}-app.sh + 헬스체크 + sudoers + 배포계정.
#   정본 소스/직접 설치법: installer/deploy-agent/README.md

# WAS 용과 WEB 용은 서로 다른 스크립트다. 둘 중 하나만 있어도 installed 로 보고하면,
# WEB 서버에 WAS 에이전트만 깔린 상태를 "설치됨"으로 오인하게 된다.
deploy_agent_status() {
  local installed_transport
  installed_transport="$(cat /usr/local/deploy/conf/agent-transport 2>/dev/null || printf unknown)"
  if [[ "$installed_transport" == agent ]]; then
    local pull_file
    for pull_file in deploy-pull-agent.py deploy-agent-probe; do
      [[ -f "/usr/local/deploy/bin/$pull_file" && ! -L "/usr/local/deploy/bin/$pull_file"
        && "$(stat -c '%u:%g:%a' "/usr/local/deploy/bin/$pull_file")" == '0:0:750' ]] \
        && cmp -s "$SCRIPT_DIR/deploy-agent/pull/$pull_file" "/usr/local/deploy/bin/$pull_file" \
        || { echo 'drift(pull-agent-files)'; return 0; }
    done
    cmp -s "$SCRIPT_DIR/deploy-agent/pull/deploy-pull-agent.service" /etc/systemd/system/deploy-pull-agent.service \
      && systemctl is-enabled deploy-pull-agent.service >/dev/null 2>&1 \
      || { echo 'drift(pull-agent-service)'; return 0; }
    [[ -f /etc/deploy-system/agent.json ]] || { echo 'waiting(agent-registration)'; return 0; }
    systemctl is-active deploy-pull-agent.service >/dev/null 2>&1 || { echo 'drift(pull-agent-stopped)'; return 0; }
  else
    echo 'waiting(agent-migration)'; return 0
  fi
  if [[ "$(cat /usr/local/deploy/conf/agent-type 2>/dev/null)" =~ ^(deploy|nexus|gitlab|runner)$ ]]; then
    cmp -s "$SCRIPT_DIR/infrastructure/deploy-infrastructure.sh" /usr/local/deploy/bin/deploy-infrastructure.sh \
      && cmp -s "$SCRIPT_DIR/infrastructure/deploy-infrastructure.py" /usr/local/deploy/bin/deploy-infrastructure.py \
      && echo 'installed(infrastructure)' || echo 'drift(infrastructure-helper)'
    return 0
  fi
  command -v visudo >/dev/null 2>&1 && visudo -cf /etc/sudoers.d/deploy-remote >/dev/null 2>&1 \
    || { echo 'drift(agent-sudoers)'; return 0; }
  local agent_dir="$SCRIPT_DIR/deploy-agent"
  [[ -r "$agent_dir/sudoers-render.sh" ]] || { echo 'drift(agent-sudoers-renderer)'; return 0; }
  # shellcheck source=../deploy-agent/sudoers-render.sh
  . "$agent_dir/sudoers-render.sh" || { echo 'drift(agent-sudoers-renderer)'; return 0; }
  local users deploy_user read_user
  users="$(cat /usr/local/deploy/conf/agent-users 2>/dev/null)" \
    || { echo 'drift(agent-users)'; return 0; }
  [[ "$users" =~ ^([a-z_][a-z0-9_-]*)\|([a-z_][a-z0-9_-]*)$ ]] \
    || { echo 'drift(agent-users)'; return 0; }
  deploy_user="${BASH_REMATCH[1]}"; read_user="${BASH_REMATCH[2]}"
  local marker=/usr/local/deploy/conf/agent-type read_marker=/usr/local/deploy/conf/agent-read-enabled type read_enabled source target
  [[ -r "$marker" ]] || {
    [[ -e /usr/local/deploy/bin/deploy-was-app.sh || -e /usr/local/deploy/bin/deploy-web-static.sh ]] \
      && echo 'drift(agent-release)' || echo missing
    return 0
  }
  type="$(cat "$marker" 2>/dev/null || true)"
  case "$type" in was|web|both) ;; *) echo 'drift(agent-type)'; return 0 ;; esac
  [[ -r "$read_marker" ]] || { echo 'drift(agent-read-enabled)'; return 0; }
  read_enabled="$(cat "$read_marker" 2>/dev/null || true)"
  case "$read_enabled" in 0|1) ;; *) echo 'drift(agent-read-enabled)'; return 0 ;; esac
  cmp -s /etc/sudoers.d/deploy-remote <(render_deploy_agent_sudoers "$agent_dir/deploy-remote.sudoers" "$type" "$read_enabled" "$deploy_user" "$read_user") \
    || { echo 'drift(agent-sudoers-version)'; return 0; }
  local pairs=(
    "$agent_dir/was/deploy-runtime.sh|/usr/local/deploy/bin/deploy-runtime.sh"
    "$agent_dir/was/deploy-storage.sh|/usr/local/deploy/bin/deploy-storage.sh"
    "$agent_dir/local-healthcheck.sh|/usr/local/deploy/bin/local-healthcheck.sh"
    "$agent_dir/deploy-service-control.sh|/usr/local/deploy/bin/deploy-service-control.sh"
  )
  if [[ "$read_enabled" == 1 ]]; then
    pairs+=("$agent_dir/read/deploy-read.sh|/usr/local/deploy/bin/deploy-read.sh")
  fi
  if [[ "$type" == was || "$type" == both ]]; then
    pairs+=("$agent_dir/was/deploy-was-app.sh|/usr/local/deploy/bin/deploy-was-app.sh")
  fi
  if [[ "$type" == web || "$type" == both ]]; then
    [[ -f /usr/local/deploy/bin/deploy-container-core.sh && ! -L /usr/local/deploy/bin/deploy-container-core.sh
      && "$(stat -c '%u:%g:%a' /usr/local/deploy/bin/deploy-container-core.sh)" == '0:0:750' ]] \
      || { echo 'drift(container-core)'; return 0; }
    [[ -f /usr/local/deploy/bin/nginx-config-validator.py && ! -L /usr/local/deploy/bin/nginx-config-validator.py \
      && "$(stat -c '%u:%g:%a' /usr/local/deploy/bin/nginx-config-validator.py)" == '0:0:640' ]] \
      || { echo 'drift(nginx-editor-helper)'; return 0; }
    cmp -s "$agent_dir/web/nginx-config-validator.py" /usr/local/deploy/bin/nginx-config-validator.py \
      || { echo 'drift(nginx-editor-helper-version)'; return 0; }
    [[ -f /usr/local/deploy/bin/nginx-rollout.py && ! -L /usr/local/deploy/bin/nginx-rollout.py \
      && "$(stat -c '%u:%g:%a' /usr/local/deploy/bin/nginx-rollout.py)" == '0:0:640' ]] \
      || { echo 'drift(nginx-rollout-helper)'; return 0; }
    cmp -s "$agent_dir/web/nginx-rollout.py" /usr/local/deploy/bin/nginx-rollout.py \
      || { echo 'drift(nginx-rollout-helper-version)'; return 0; }
    pairs+=(
      "$agent_dir/was/deploy-was-app.sh|/usr/local/deploy/bin/deploy-container-core.sh"
      "$agent_dir/web/deploy-web-app.sh|/usr/local/deploy/bin/deploy-web-app.sh"
      "$agent_dir/web/deploy-web-static.sh|/usr/local/deploy/bin/deploy-web-static.sh"
      "$agent_dir/web/deploy-web-proxy.sh|/usr/local/deploy/bin/deploy-web-proxy.sh"
      "$agent_dir/web/deploy-nginx-policy.sh|/usr/local/deploy/bin/deploy-nginx-policy.sh"
    )
  fi
  for pair in "${pairs[@]}"; do
    IFS='|' read -r source target <<< "$pair"
    [[ -x "$target" && -f "$source" ]] || { echo "drift(agent-files:$type)"; return 0; }
    cmp -s "$source" "$target" || { echo "drift(agent-version:$type)"; return 0; }
  done
  [[ -f /usr/local/deploy/bin/deploy-runtime.py && ! -L /usr/local/deploy/bin/deploy-runtime.py
    && "$(stat -c '%u:%g:%a' /usr/local/deploy/bin/deploy-runtime.py)" == '0:0:640' ]] \
    || { echo 'drift(agent-runtime-python)'; return 0; }
  cmp -s "$agent_dir/was/deploy-runtime.py" /usr/local/deploy/bin/deploy-runtime.py \
    || { echo 'drift(agent-runtime-python-version)'; return 0; }
  [[ -f /usr/local/deploy/bin/deploy-storage.py && ! -L /usr/local/deploy/bin/deploy-storage.py
    && "$(stat -c '%u:%g:%a' /usr/local/deploy/bin/deploy-storage.py)" == '0:0:640' ]] \
    || { echo 'drift(agent-storage-python)'; return 0; }
  cmp -s "$agent_dir/was/deploy-storage.py" /usr/local/deploy/bin/deploy-storage.py \
    || { echo 'drift(agent-storage-python-version)'; return 0; }
  for target in deploy-storage-guard.service deploy-storage-guard.timer; do
    [[ -f "/etc/systemd/system/$target" && ! -L "/etc/systemd/system/$target"
      && "$(stat -c '%u:%g:%a' "/etc/systemd/system/$target")" == '0:0:644' ]] \
      && cmp -s "$agent_dir/was/$target" "/etc/systemd/system/$target" \
      || { echo 'drift(agent-storage-guard)'; return 0; }
    if [[ "$type" == was || "$type" == both ]]; then
      systemctl is-enabled "$target" >/dev/null 2>&1 || { echo 'drift(agent-storage-guard-enabled)'; return 0; }
    fi
  done
  command -v findmnt >/dev/null || { echo 'drift(agent-storage-findmnt)'; return 0; }
  [[ -x /usr/libexec/platform-python || -x /usr/bin/python3 ]] || { echo 'drift(agent-runtime-interpreter)'; return 0; }
  echo "installed($type)"
}

deploy_agent_install() {
  local force="${1:-}"
  local agent_dir="$SCRIPT_DIR/deploy-agent"
  [[ -f "$agent_dir/install-deploy-agent.sh" ]] || { err "deploy-agent 소스 없음: $agent_dir"; return 1; }
  DEPLOY_AGENT_TRANSPORT="${DEPLOY_AGENT_TRANSPORT:-agent}"
  target_prerequisites_install || return 1

  # 무인 응답 키: DEPLOY_AGENT_TYPE(was|web|deploy|nexus|gitlab|runner) / DEPLOY_AGENT_USER /
  #   DEPLOY_AGENT_HEALTH_PORT / DEPLOY_AGENT_HEALTH_PATH /
  #   DEPLOY_AGENT_REGISTRY_URL / DEPLOY_AGENT_REGISTRY_USERNAME / DEPLOY_AGENT_REGISTRY_PASSWORD
  local atype auser hport hpath rurl ruser rpass transport
  atype="$(ask DEPLOY_AGENT_TYPE "에이전트 역할 (was|web|deploy|nexus|gitlab|runner) [was]: " "was")"
  transport="${DEPLOY_AGENT_TRANSPORT:-agent}"
  [[ "$transport" == agent ]] || { err "SSH 전송은 지원하지 않습니다. 에이전트 방식으로 등록하세요."; return 1; }
  auser="$(ask DEPLOY_AGENT_USER "배포 계정 [deploy]: " "deploy")"
  # 헬스체크 기본값은 타입에 따라 다르다.
  #   WAS  — 앱 컨테이너의 actuator (8080 /actuator/health)
  #   WEB  — nginx 가 서빙하는 정적 산출물의 루트 (80 /)
  # 이걸 구분하지 않으면 WEB 노드에서 8080/actuator 를 때리게 되어, 배포는 성공하는데
  # 헬스체크가 실패해 매번 직전 릴리스로 롤백된다(원인이 화면에 드러나지 않는다).
  local d_hport="8080" d_hpath="/actuator/health"
  if [[ "$atype" == "web" ]]; then d_hport="80"; d_hpath="/"; fi
  hport="$(ask DEPLOY_AGENT_HEALTH_PORT "기본 헬스체크 포트 [$d_hport]: " "$d_hport")"
  hpath="$(ask DEPLOY_AGENT_HEALTH_PATH "기본 헬스체크 경로 [$d_hpath]: " "$d_hpath")"
  rurl="$(ask DEPLOY_AGENT_REGISTRY_URL "Nexus 레지스트리 URL(인증 필요 시) [없으면 엔터]: " "")"

  local args=(--type "$atype" --transport "$transport" --deploy-user "$auser" --health-port "$hport" --health-path "$hpath")
    local control_url server_id code_file ca_file
    control_url="$(ask DEPLOY_AGENT_CONTROL_URL 'Deploy 접속 URL (https://...): ' '')"
    server_id="$(ask DEPLOY_AGENT_SERVER_ID 'Deploy UI에 등록한 서버 ID: ' '')"
    code_file="$(ask DEPLOY_AGENT_ENROLLMENT_CODE_FILE '일회성 등록 코드 파일(root 600): ' '')"
    ca_file="$(ask DEPLOY_AGENT_CA_FILE '사내 CA 파일 [OS 신뢰 저장소 사용 시 엔터]: ' '')"
    args+=(--control-url "$control_url" --server-id "$server_id" --enrollment-code-file "$code_file")
    [[ -z "$ca_file" ]] || args+=(--ca-file "$ca_file")
    [[ "${DEPLOY_AGENT_ALLOW_HTTP:-n}" != y ]] || args+=(--allow-http)
  if [[ -n "$rurl" ]]; then
    ruser="$(ask DEPLOY_AGENT_REGISTRY_USERNAME "레지스트리 계정: " "")"
    rpass="$(ask_secret DEPLOY_AGENT_REGISTRY_PASSWORD "레지스트리 비밀번호: " allow_empty)"
    args+=(--registry-url "$rurl" --registry-username "$ruser" --registry-password "$rpass")
  fi
  [[ "$force" == "force" ]] && args+=(--force-reinstall)

  GITLAB_CONTAINER="${GITLAB_CONTAINER:-worksystem-gitlab}" NEXUS_CONTAINER="${NEXUS_CONTAINER:-worksystem-nexus}" RUNNER_CONTAINER="${RUNNER_CONTAINER:-worksystem-gitlab-runner}" \
    bash "$agent_dir/install-deploy-agent.sh" "${args[@]}" || { err "배포 에이전트 설치 실패"; return 1; }
  success "배포 에이전트 설치 완료 — 배포시스템 '서버 관리'에 이 서버를 등록하세요."
}
