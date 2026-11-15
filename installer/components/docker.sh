#!/usr/bin/env bash
# docker.sh - Docker 엔진 설치 컴포넌트 (OS별 패키지: rocky=rpm, ubuntu=deb)
#   GitLab/Nexus/Runner 가 docker 이미지로 동작하므로 가장 먼저 필요하다.

docker_status() {
  if command -v docker >/dev/null 2>&1; then
    if docker_runtime_contract; then
      echo installed
    else
      echo partial
    fi
  elif pkg_is_installed docker-ce || pkg_is_installed docker.io; then echo partial
  else echo missing; fi
}

docker_runtime_contract() {
  systemctl is-active --quiet docker 2>/dev/null && docker info >/dev/null 2>&1 \
    && docker_package_contract || return 1
  case "${DOCKER_SET_INSECURE_REGISTRY:-y}" in
    y|Y|yes|YES|true|TRUE|1)
      local reg="${DOCKER_INSECURE_REGISTRY:-192.0.2.10:5000}" jq_bin
      local daemon_config="${DOCKER_DAEMON_CONFIG:-/etc/docker/daemon.json}"
      [[ -f "$daemon_config" ]] || return 1
      jq_bin="$(docker_jq_binary)" || return 1
      "$jq_bin" -e --arg registry "$reg" \
        '(."insecure-registries" // []) | index($registry) != null' \
        "$daemon_config" >/dev/null 2>&1 \
        && docker info --format '{{json .RegistryConfig.IndexConfigs}}' 2>/dev/null \
          | "$jq_bin" -e --arg registry "$reg" '.[$registry].Secure == false' >/dev/null 2>&1
      ;;
    *) return 0 ;;
  esac
}

docker_package_contract() {
  local lock="$BUNDLE_ROOT/$BUNDLE_OS/docker/PACKAGES.lock" package version installed
  local required=()
  [[ -r "$lock" ]] || return 1
  if [[ "$BUNDLE_OS" == rocky8 ]]; then
    required=(docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin)
  else
    required=(docker.io containerd docker-buildx docker-compose-v2)
  fi
  for package in "${required[@]}"; do
    version="$(awk -F'|' -v package="$package" '$1 == package { print $2 }' "$lock")"
    [[ -n "$version" && "$version" != *$'\n'* ]] || return 1
    if [[ "$BUNDLE_OS" == rocky8 ]]; then
      installed="$(rpm -q --qf '%{EPOCHNUM}:%{VERSION}-%{RELEASE}' "$package" 2>/dev/null)" || return 1
    else
      installed="$(dpkg-query -W -f='${Version}' "$package" 2>/dev/null)" || return 1
    fi
    [[ "$installed" == "$version" ]] || return 1
  done
}

docker_install() {
  local force="${1:-}"
  if [[ "$OS_FAMILY" == rhel ]] && rpm -q runc >/dev/null 2>&1; then
    err '기존 runc 패키지가 Docker containerd.io와 충돌합니다. 대화형 설치기의 설정 검사 후 설치/재개에서 제거 영향을 확인하세요. 강제 삭제하지 않았습니다.'
    return 1
  fi
  if command -v docker >/dev/null 2>&1 && docker_package_contract && [[ "$force" != "force" ]]; then
    info "Docker 이미 설치됨: $(docker --version 2>/dev/null)"
  else
    local dir; dir="$(bundle_dir docker)" || { err "Docker 번들 없음: $dir"; return 1; }
    info "Docker 엔진 패키지 설치: $dir"
    install_local_packages "$dir" || return 1
  fi
  # distro docker.io 는 socket activation(docker.socket)을 사용하므로 socket 을 먼저 활성화한다.
  # (docker.service 만 start 하면 'no sockets found via socket activation' 으로 실패)
  systemctl enable --now containerd 2>/dev/null || true
  systemctl enable docker.socket 2>/dev/null || true
  systemctl start docker.socket 2>/dev/null || true
  service_enable_now docker || { err "docker 서비스 시작 실패"; return 1; }
  docker info >/dev/null 2>&1 \
    || { err "docker daemon이 기동됐지만 API에 응답하지 않습니다."; return 1; }

  # 폐쇄망 정책: Nexus 레지스트리를 insecure-registries 로 신뢰
  # 무인: DOCKER_SET_INSECURE_REGISTRY(기본 y) / DOCKER_INSECURE_REGISTRY
  if ask_confirm DOCKER_SET_INSECURE_REGISTRY "Nexus 레지스트리를 insecure-registries 로 등록할까요? (폐쇄망 표준)" y; then
    local reg jq_bin tmp_config backup="" had_existing=0
    reg="$(ask DOCKER_INSECURE_REGISTRY "레지스트리 host:port [192.0.2.10:5000]: " "192.0.2.10:5000")"
    [[ "$reg" =~ ^[A-Za-z0-9._-]+:[0-9]{1,5}$ ]] \
      || { err "레지스트리는 host:port 형식이어야 합니다: $reg"; return 1; }
    jq_bin="$(docker_jq_binary)" || return 1
    mkdir -p /etc/docker
    tmp_config="$(mktemp /etc/docker/daemon.json.tmp.XXXXXX)" || return 1
    if [[ -f /etc/docker/daemon.json ]]; then
      had_existing=1
      backup="/etc/docker/daemon.json.bak.$(date +%s)"
      "$jq_bin" empty /etc/docker/daemon.json >/dev/null 2>&1 \
        || { rm -f "$tmp_config"; err "기존 /etc/docker/daemon.json 이 올바른 JSON이 아닙니다."; return 1; }
      cp -p /etc/docker/daemon.json "$backup" || return 1
    fi
    docker_merge_daemon_json "$jq_bin" /etc/docker/daemon.json "$tmp_config" "$reg" \
      || { rm -f "$tmp_config"; return 1; }
    install -m 0644 -o root -g root "$tmp_config" /etc/docker/daemon.json \
      || { rm -f "$tmp_config"; return 1; }
    rm -f "$tmp_config"
    if ! systemctl restart docker || ! docker info >/dev/null 2>&1; then
      err "daemon.json 적용 후 Docker 재기동/API 검증 실패 — 이전 설정을 복원합니다."
      if (( had_existing )); then
        cp -p "$backup" /etc/docker/daemon.json
      else
        rm -f /etc/docker/daemon.json
      fi
      systemctl restart docker >/dev/null 2>&1 \
        || err "이전 daemon.json 복원 후에도 Docker 재기동 실패 — 즉시 수동 점검이 필요합니다."
      return 1
    fi
    info "daemon.json 병합 적용: insecure-registries에 $reg 추가 (기존 키 보존)"
  fi

  # 운영 계정을 docker 그룹에 추가(선택) — 무인: DOCKER_GROUP_USER
  local du; du="$(ask DOCKER_GROUP_USER "docker 그룹에 추가할 계정 [없으면 엔터]: " "")"
  if [[ -n "$du" ]] && id "$du" >/dev/null 2>&1; then
    usermod -aG docker "$du" && info "$du 를 docker 그룹에 추가(재로그인 후 적용)."
  fi
  docker_runtime_contract \
    || { err "Docker 최종 daemon/API/registry 계약 검증 실패"; return 1; }
  success "Docker 설치/기동 완료."
}

docker_jq_binary() {
  local bundled
  if command -v jq >/dev/null 2>&1; then
    command -v jq
    return 0
  fi
  bundled="$(bundle_file jq 'jq-linux-amd64')" \
    || { err "daemon.json 병합에 필요한 jq가 없습니다: bundles/common/jq/jq-linux-amd64"; return 1; }
  if [[ "${OFFLINE_VERIFY_AUTHENTICITY:-0}" == 1 ]]; then
  local actual_sha
  actual_sha="$(sha256sum "$bundled" | awk '{print $1}')" || return 1
  [[ "$actual_sha" == "$BOM_JQ_SHA256" ]] \
    || { err "번들 jq SHA-256이 release BOM과 다릅니다."; return 1; }
  fi
  # 읽기 전용 반입 매체에서도 이미 실행 가능한 바이너리는 변경하지 않는다.
  [[ -x "$bundled" ]] || chmod +x "$bundled" 2>/dev/null \
    || { err "번들 jq를 실행 가능하게 만들 수 없습니다: $bundled"; return 1; }
  printf '%s' "$bundled"
}

# 기존 JSON의 모든 키를 유지하고 registry만 집합처럼 추가한다.
# 입력 파일이 없으면 빈 객체에서 시작한다. 결과는 호출자가 원자적으로 설치한다.
docker_merge_daemon_json() {
  local jq_bin="$1" current="$2" output="$3" registry="$4"
  if [[ -f "$current" ]]; then
    "$jq_bin" --arg registry "$registry" \
      '. + {"insecure-registries": (((.["insecure-registries"] // []) + [$registry]) | unique)}' \
      "$current" > "$output"
  else
    printf '{}\n' | "$jq_bin" --arg registry "$registry" \
      '. + {"insecure-registries": [$registry]}' > "$output"
  fi
  "$jq_bin" empty "$output" >/dev/null 2>&1
}
