#!/usr/bin/env bash
# gitlab.sh - GitLab(docker 이미지) 설치 컴포넌트
#   번들: bundles/common/gitlab/*.tar.gz (release BOM의 명시적 버전)

GITLAB_CONTAINER="${GITLAB_CONTAINER:-worksystem-gitlab}"
GITLAB_IMAGE="$BOM_GITLAB_IMAGE"
GITLAB_DATA="${GITLAB_DATA:-/srv/gitlab}"

gitlab_status() {
  if container_exists "$GITLAB_CONTAINER"; then
    if gitlab_runtime_contract "${GITLAB_HTTP_PORT:-8929}" "${GITLAB_SSH_PORT:-2222}"; then
      echo installed
    else
      echo drift
    fi
  elif image_present "$GITLAB_IMAGE"; then echo partial
  else echo missing; fi
}

gitlab_runtime_contract() {
  local http_port="$1" ssh_port="$2" external_url hostname puma_workers sidekiq_conc prometheus omnibus expected_omnibus
  external_url="${GITLAB_EXTERNAL_URL:-http://192.0.2.10:$http_port}"
  puma_workers="${GITLAB_PUMA_WORKERS:-2}"
  sidekiq_conc="${GITLAB_SIDEKIQ_CONCURRENCY:-5}"
  prometheus="${GITLAB_PROMETHEUS:-n}"
  hostname="$(printf '%s' "$external_url" | sed -E 's#^https?://##; s#[:/].*$##')"
  omnibus="$(container_environment_value "$GITLAB_CONTAINER" GITLAB_OMNIBUS_CONFIG)" || return 1
  expected_omnibus="$(gitlab_expected_omnibus_config "$external_url" "$http_port" "$ssh_port" \
    "$puma_workers" "$sidekiq_conc" "$prometheus")" || return 1
  image_matches_archive_id "$GITLAB_IMAGE" "${LOCK_GITLAB_ARCHIVE_ID:-}" \
    && container_matches_archive_id "$GITLAB_CONTAINER" "${LOCK_GITLAB_ARCHIVE_ID:-}" \
    && container_runtime_healthy "$GITLAB_CONTAINER" \
    && container_port_binding_matches "$GITLAB_CONTAINER" "${http_port}/tcp" "$http_port" \
    && [[ "$(container_environment_value "$GITLAB_CONTAINER" GITLAB_DISABLE_OPENSSH)" == true ]] \
    && [[ "$(docker exec "$GITLAB_CONTAINER" gitlab-psql -d gitlabhq_production -Atc 'SELECT enabled_git_access_protocol FROM application_settings LIMIT 1')" == http ]] \
    && docker exec "$GITLAB_CONTAINER" test ! -e /opt/gitlab/service/sshd \
    && [[ -z "$(docker port "$GITLAB_CONTAINER" 22/tcp 2>/dev/null)" ]] \
    && container_mount_source_matches "$GITLAB_CONTAINER" /etc/gitlab "$GITLAB_DATA/config" \
    && container_mount_source_matches "$GITLAB_CONTAINER" /var/log/gitlab "$GITLAB_DATA/logs" \
    && container_mount_source_matches "$GITLAB_CONTAINER" /var/opt/gitlab "$GITLAB_DATA/data" \
    && container_inspect_value_matches "$GITLAB_CONTAINER" '{{.Config.Hostname}}' "$hostname" \
    && container_inspect_value_matches "$GITLAB_CONTAINER" '{{.HostConfig.ShmSize}}' 268435456 \
    && container_inspect_value_matches "$GITLAB_CONTAINER" '{{.HostConfig.RestartPolicy.Name}}' always \
    && gitlab_omnibus_config_matches "$omnibus" "$external_url" "$http_port" "$ssh_port" \
      "$puma_workers" "$sidekiq_conc" "$prometheus" \
    && gitlab_health_contract "$http_port"
}

# 모니터링 endpoint는 기본 localhost allowlist를 유지한다.
# Docker bridge에서 보낸 호스트 요청은 localhost로 인정되지 않을 수 있다.
gitlab_health_contract() {
  local http_port="$1"
  # HTTP can answer before the initial reconfigure has completed. The native
  # backup/restore commands require configured Rails and running app services.
  gitlab_configuration_ready \
    && docker exec "$GITLAB_CONTAINER" curl -fsS -o /dev/null --max-time 5 \
    "http://127.0.0.1:${http_port}/-/health" 2>/dev/null \
    && curl -fsS -o /dev/null --max-time 5 \
      "http://127.0.0.1:${http_port}/users/sign_in" 2>/dev/null
}

gitlab_configuration_ready() {
  docker exec "$GITLAB_CONTAINER" test -f /var/opt/gitlab/bootstrapped >/dev/null 2>&1 \
    && docker exec "$GITLAB_CONTAINER" test -s /var/opt/gitlab/gitlab-rails/etc/database.yml >/dev/null 2>&1 \
    && docker exec "$GITLAB_CONTAINER" sh -c 'command -v pgrep >/dev/null && ! pgrep -f "/opt/gitlab/embedded/bin/[c]inc-client" >/dev/null' >/dev/null 2>&1 \
    && docker exec "$GITLAB_CONTAINER" gitlab-ctl status puma >/dev/null 2>&1 \
    && docker exec "$GITLAB_CONTAINER" gitlab-ctl status sidekiq >/dev/null 2>&1
}

gitlab_wait_healthy() {
  local http_port="$1" attempts="${2:-120}" i
  for ((i=0; i<attempts; i++)); do
    if container_runtime_healthy "$GITLAB_CONTAINER" && gitlab_health_contract "$http_port"; then
      return 0
    fi
    sleep 5
  done
  return 1
}

gitlab_omnibus_config_matches() {
  local actual="$1" expected
  shift
  expected="$(gitlab_expected_omnibus_config "$@")" || return 1
  [[ "$actual" == "$expected" ]]
}

gitlab_expected_omnibus_config() {
  local external_url="$1" http_port="$2" ssh_port="$3" puma_workers="$4" sidekiq_conc="$5" prometheus="${6,,}"
  local prometheus_enabled=false
  case "$prometheus" in y|yes|true|1) prometheus_enabled=true ;; n|no|false|0) ;; *) return 1 ;; esac
  printf "external_url '%s'; gitlab_rails['gitlab_shell_ssh_port'] = 0; gitlab_rails['signup_enabled'] = false; nginx['listen_port'] = %s; puma['worker_processes'] = %s; sidekiq['concurrency'] = %s; prometheus_monitoring['enable'] = %s;" \
    "$external_url" "$http_port" "$puma_workers" "$sidekiq_conc" "$prometheus_enabled"
}

gitlab_install() {
  local force="${1:-}"
  docker_available || { err "Docker 가 먼저 필요합니다. 'docker' 컴포넌트를 설치하세요."; return 1; }

  if ! image_matches_archive_id "$GITLAB_IMAGE" "${LOCK_GITLAB_ARCHIVE_ID:-}" \
      || [[ "$force" == "force" ]]; then
    image_present "$GITLAB_IMAGE" \
      && warn "GitLab local image tag drift를 검증된 archive로 교체합니다."
    docker_load_bundle gitlab || return 1
  fi
  image_matches_archive_id "$GITLAB_IMAGE" "${LOCK_GITLAB_ARCHIVE_ID:-}" \
    || { err "GitLab tag의 image ID가 검증된 archive lock과 다릅니다."; return 1; }
  if container_exists "$GITLAB_CONTAINER"; then
    if [[ "$force" != "force" ]] && gitlab_runtime_contract "${GITLAB_HTTP_PORT:-8929}" "${GITLAB_SSH_PORT:-2222}"; then
      info "GitLab 이미지·상태·포트 계약이 일치합니다 — 건너뜀."; return 0
    fi
    err "기존 GitLab 컨테이너에 image/health/port drift가 있습니다."
    recreate_container_if_confirmed "$GITLAB_CONTAINER" \
      || { err "drift 상태를 유지한 채 설치 완료로 처리할 수 없습니다."; return 1; }
  fi

  # 무인: GITLAB_HTTP_PORT / GITLAB_SSH_PORT / GITLAB_EXTERNAL_URL
  local http_port ssh_port external_url
  http_port="$(ask GITLAB_HTTP_PORT "GitLab HTTP 포트 [8929]: " "8929")"
  ssh_port=2222 # Legacy config value only; SSH is not published.
  external_url="$(ask GITLAB_EXTERNAL_URL "external_url [http://192.0.2.10:$http_port]: " "http://192.0.2.10:$http_port")"

  mkdir -p "$GITLAB_DATA/config" "$GITLAB_DATA/logs" "$GITLAB_DATA/data"
  # 리소스 튜닝 — omnibus 기본값(puma 워커가 CPU 수만큼, prometheus 스택 전부 기동)은
  # 사내 사용자 수십 명 규모에 과하다. 아래 값이면 유휴 메모리가 4GB 대에서 2GB 대로 내려간다.
  # 사용자가 늘면 GITLAB_PUMA_WORKERS / GITLAB_SIDEKIQ_CONCURRENCY 만 올리면 된다.
  local puma_workers sidekiq_conc prometheus
  puma_workers="$(ask GITLAB_PUMA_WORKERS "GitLab puma 워커 수 [2]: " "2")"
  sidekiq_conc="$(ask GITLAB_SIDEKIQ_CONCURRENCY "GitLab sidekiq 동시성 [5]: " "5")"
  prometheus="$(ask GITLAB_PROMETHEUS "GitLab 내장 모니터링(prometheus) 사용 (y|n) [n]: " "n")"

  local omnibus
  omnibus="$(gitlab_expected_omnibus_config "$external_url" "$http_port" "$ssh_port" \
    "$puma_workers" "$sidekiq_conc" "$prometheus")" \
    || { err "GITLAB_PROMETHEUS는 y/n 또는 true/false여야 합니다."; return 1; }

  info "GitLab 컨테이너 기동... (puma ${puma_workers} / sidekiq ${sidekiq_conc} / prometheus ${prometheus})"
  docker run -d \
    --name "$GITLAB_CONTAINER" \
    --restart always \
    --hostname "$(printf '%s' "$external_url" | sed -E 's#^https?://##; s#[:/].*$##')" \
    -p "${http_port}:${http_port}" \
    --shm-size 256m \
    -e GITLAB_DISABLE_OPENSSH=true \
    -e GITLAB_OMNIBUS_CONFIG="$omnibus" \
    -v "$GITLAB_DATA/config:/etc/gitlab:Z" \
    -v "$GITLAB_DATA/logs:/var/log/gitlab:Z" \
    -v "$GITLAB_DATA/data:/var/opt/gitlab:Z" \
    "$GITLAB_IMAGE" || { err "GitLab 컨테이너 기동 실패"; return 1; }

  info "GitLab 구성·Chef 초기화 완료, Puma/Sidekiq와 health 응답을 기다립니다(최대 10분)..."
  gitlab_wait_healthy "$http_port" 120 \
    || { err "GitLab health 확인 실패 — docker logs $GITLAB_CONTAINER"; return 1; }
  docker exec "$GITLAB_CONTAINER" gitlab-rails runner "ApplicationSetting.current.update!(enabled_git_access_protocol: 'http')" \
    || { err "GitLab HTTP Git policy setup failed"; return 1; }
  gitlab_runtime_contract "$http_port" "$ssh_port" \
    || { err "GitLab 기동 후 image/health/port 계약 검증 실패"; return 1; }
  success "GitLab 기동·계약 검증 완료: $external_url"
  info "초기 root 비번: docker exec $GITLAB_CONTAINER cat /etc/gitlab/initial_root_password (24h 후 삭제)"
}
