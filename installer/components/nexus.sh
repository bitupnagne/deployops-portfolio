#!/usr/bin/env bash
# nexus.sh - Nexus(docker 이미지) 설치 컴포넌트
#   번들: bundles/common/nexus/*.tar.gz (release BOM의 명시적 버전)

NEXUS_CONTAINER="${NEXUS_CONTAINER:-worksystem-nexus}"
NEXUS_IMAGE="$BOM_NEXUS_IMAGE"
NEXUS_VOLUME="${NEXUS_VOLUME:-nexus-data}"
# Distinct non-success result: a verified web runtime must remain available for human consent.
NEXUS_EULA_PENDING_RC=20
. "$SCRIPT_DIR/lib/nexus-secrets.sh" || return 1

nexus_validate_ports() {
  local web="${1:-${NEXUS_WEB_PORT:-8081}}" reg="${2:-${NEXUS_REGISTRY_PORT:-5000}}" extra="${NEXUS_ADDITIONAL_REGISTRY_PORT:-}" port
  for port in "$web" "$reg"; do
    [[ "$port" =~ ^[1-9][0-9]{0,4}$ ]] && (( port <= 65535 )) || { err "Nexus 포트는 1~65535 정수여야 합니다."; return 1; }
  done
  [[ "$web" != "$reg" && "$reg" != 8081 ]] || { err "Nexus Web/Registry 포트가 충돌합니다."; return 1; }
  [[ -n "$extra" ]] || return 0
  [[ "$extra" =~ ^[1-9][0-9]{0,4}$ ]] && (( extra <= 65535 )) || { err "추가 Registry 포트는 1~65535 정수여야 합니다."; return 1; }
  [[ "$extra" != "$web" && "$extra" != "$reg" && "$extra" != 8081 ]] || { err "추가 Registry 포트가 기존 포트와 충돌합니다."; return 1; }
}

nexus_additional_port_contract() {
  local extra="${NEXUS_ADDITIONAL_REGISTRY_PORT:-}"
  [[ -n "$extra" ]] || return 0
  container_port_binding_matches "$NEXUS_CONTAINER" "${extra}/tcp" "$extra"
}

nexus_validate_maven_options() {
  case "${NEXUS_MAVEN_SETUP:-n}" in y|n) ;; *) err "NEXUS_MAVEN_SETUP must be y or n"; return 1 ;; esac
  case "${NEXUS_MAVEN_REPAIR_DRIFT:-n}" in y|n) ;; *) err "NEXUS_MAVEN_REPAIR_DRIFT must be y or n"; return 1 ;; esac
  if [[ "${NEXUS_MAVEN_REPAIR_DRIFT:-n}" == y && "${NEXUS_MAVEN_SETUP:-n}" != y ]]; then
    err "NEXUS_MAVEN_REPAIR_DRIFT=y requires NEXUS_MAVEN_SETUP=y"; return 1
  fi
  if [[ "${NEXUS_MAVEN_SETUP:-n}" == y ]]; then
    case "${NEXUS_AUTO_SETUP:-y}" in y|Y|yes|YES|true|1) ;; *) err "NEXUS_MAVEN_SETUP=y requires NEXUS_AUTO_SETUP=y"; return 1 ;; esac
    [[ -r "$SCRIPT_DIR/lib/nexus-maven.sh" ]] || { err "Nexus Maven helper is missing: $SCRIPT_DIR/lib/nexus-maven.sh"; return 1; }
    . "$SCRIPT_DIR/lib/nexus-maven.sh" || return 1
  fi
}

# 상태 확인은 GET만 사용한다. setup/repair를 호출하지 않는다.
nexus_maven_runtime_contract() (
  local base="$1" jq_bin="$2" pass="$3" task_dir escaped name kind
  [[ "${NEXUS_MAVEN_SETUP:-n}" == y ]] || return 0
  nexus_validate_maven_options || return 1
  [[ -n "$pass" && "$pass" != *$'\n'* && "$pass" != *$'\r'* ]] || return 1
  task_dir="$(mktemp -d)" || return 1
  trap 'rm -rf "$task_dir"' EXIT
  chmod 700 "$task_dir" || return 1
  escaped="${pass//\\/\\\\}"; escaped="${escaped//\"/\\\"}"
  (umask 077; printf 'user = "admin:%s"\n' "$escaped" > "$task_dir/auth") || return 1
  for name in maven-offline-seed maven-releases maven-snapshots maven-public; do
    kind=hosted; [[ "$name" != maven-public ]] || kind=group
    nexus_maven_payload "$jq_bin" "$name" > "$task_dir/expected" || return 1
    curl --config "$task_dir/auth" -fsS --max-time 10 -o "$task_dir/actual" \
      "$base/service/rest/v1/repositories/maven/$kind/$name" || return 1
    nexus_maven_contract_json "$jq_bin" "$task_dir/actual" "$task_dir/expected" "$kind" || return 1
  done
)

nexus_setup_maven_if_selected() {
  nexus_validate_maven_options || return 1
  [[ "${NEXUS_MAVEN_SETUP:-n}" == y ]] || return 0
  nexus_maven_setup "$1" "$2" || { err "Nexus Maven 저장소 구성/검증 실패(기존 데이터는 삭제하지 않습니다)."; return 1; }
  success "Nexus Maven 저장소 4개 계약 검증 완료. Maven seed 반입 및 실제 CI 검증은 별도 단계입니다."
}

nexus_status() {
  nexus_validate_ports || { echo drift; return; }
  if container_exists "$NEXUS_CONTAINER"; then
    if nexus_runtime_contract "${NEXUS_WEB_PORT:-8081}" "${NEXUS_REGISTRY_PORT:-5000}"; then
      echo installed
    elif nexus_eula_pending_contract "${NEXUS_WEB_PORT:-8081}" "${NEXUS_REGISTRY_PORT:-5000}"; then
      echo eula-pending
    elif nexus_runtime_contract_base "${NEXUS_WEB_PORT:-8081}" "${NEXUS_REGISTRY_PORT:-5000}" \
        && [[ -z "$(nexus_admin_password)" ]]; then
      echo unverified
    else
      echo drift
    fi
  elif image_present "$NEXUS_IMAGE"; then echo partial
  else echo missing; fi
}

nexus_expected_heap() {
  printf '%s' "-Xms${1} -Xmx${1} -XX:MaxDirectMemorySize=${1} -Djava.util.prefs.userRoot=/nexus-data/javaprefs"
}

nexus_runtime_contract_base() {
  local web_port="$1" reg_port="$2"
  nexus_validate_ports "$web_port" "$reg_port" || return 1
  image_matches_archive_id "$NEXUS_IMAGE" "${LOCK_NEXUS_ARCHIVE_ID:-}" \
    && nexus_secrets_runtime_contract \
    && container_matches_archive_id "$NEXUS_CONTAINER" "${LOCK_NEXUS_ARCHIVE_ID:-}" \
    && [[ "$(container_environment_value "$NEXUS_CONTAINER" INSTALL4J_ADD_VM_PARAMS)" == "$(nexus_expected_heap "${NEXUS_HEAP:-1g}")" ]] \
    && container_runtime_healthy "$NEXUS_CONTAINER" \
    && container_port_binding_matches "$NEXUS_CONTAINER" 8081/tcp "$web_port" \
    && container_port_binding_matches "$NEXUS_CONTAINER" "${reg_port}/tcp" "$reg_port" \
    && nexus_additional_port_contract \
    && curl -fsS -o /dev/null --max-time 5 "http://127.0.0.1:${web_port}/service/rest/v1/status" 2>/dev/null
}

nexus_runtime_contract() {
  local web_port="$1" reg_port="$2" pass jq_bin detail eula realms
  nexus_validate_maven_options || return 1
  nexus_runtime_contract_base "$web_port" "$reg_port" || return 1
  pass="$(nexus_admin_password)"
  [[ -n "$pass" ]] || return 1
  jq_bin="$(docker_jq_binary)" || return 1
  detail="$(mktemp)" || return 1
  eula="$(mktemp)" || { rm -f "$detail"; return 1; }
  realms="$(mktemp)" || { rm -f "$detail" "$eula"; return 1; }
  if ! curl -fsS -u "admin:$pass" --max-time 10 \
      -o "$detail" "http://127.0.0.1:${web_port}/service/rest/v1/repositories/docker/hosted/${NEXUS_DOCKER_REPO_NAME:-docker-hosted}"; then
    rm -f "$detail" "$eula" "$realms"; return 1
  fi
  curl -fsS -u "admin:$pass" --max-time 10 -o "$eula" \
    "http://127.0.0.1:${web_port}/service/rest/v1/system/eula" \
    && curl -fsS -u "admin:$pass" --max-time 10 -o "$realms" \
      "http://127.0.0.1:${web_port}/service/rest/v1/security/realms/active" \
    && nexus_repository_contract_json "$detail" "$jq_bin" "${NEXUS_DOCKER_REPO_NAME:-docker-hosted}" "$reg_port" \
    && "$jq_bin" -e '.accepted == true' "$eula" >/dev/null 2>&1 \
    && "$jq_bin" -e 'type == "array" and index("DockerToken") != null' "$realms" >/dev/null 2>&1
  local result=$?
  rm -f "$detail" "$eula" "$realms"
  (( result == 0 )) || return "$result"
  nexus_secrets_health_contract "http://127.0.0.1:${web_port}" "$jq_bin" || return 1
  nexus_maven_runtime_contract "http://127.0.0.1:${web_port}" "$jq_bin" "$pass"
}

nexus_eula_pending_contract() {
  local web_port="$1" reg_port="$2" pass jq_bin response
  nexus_runtime_contract_base "$web_port" "$reg_port" || return 1
  pass="$(docker exec "$NEXUS_CONTAINER" cat /nexus-data/admin.password 2>/dev/null || true)"
  [[ -n "$pass" ]] || pass="${NEXUS_ADMIN_PASSWORD:-}"
  [[ -n "$pass" ]] || return 1
  jq_bin="$(docker_jq_binary)" || return 1
  response="$(curl -fsS --max-time 10 -u "admin:$pass" "http://127.0.0.1:${web_port}/service/rest/v1/system/eula" 2>/dev/null)" || return 1
  printf '%s' "$response" | "$jq_bin" -e '.accepted == false' >/dev/null 2>&1
}

nexus_admin_password() {
  local pass="${NEXUS_ADMIN_PASSWORD:-}"
  [[ -n "$pass" ]] || pass="$(docker exec "$NEXUS_CONTAINER" cat /nexus-data/admin.password 2>/dev/null || true)"
  printf '%s' "$pass"
}

nexus_install() {
  nexus_validate_ports || return 1
  nexus_validate_maven_options || return 1
  local force="${1:-}"
  docker_available || { err "Docker 가 먼저 필요합니다. 'docker' 컴포넌트를 설치하세요."; return 1; }

  if ! image_matches_archive_id "$NEXUS_IMAGE" "${LOCK_NEXUS_ARCHIVE_ID:-}" \
      || [[ "$force" == "force" ]]; then
    image_present "$NEXUS_IMAGE" \
      && warn "Nexus local image tag drift를 검증된 archive로 교체합니다."
    docker_load_bundle nexus || return 1
  fi
  # 무인: NEXUS_WEB_PORT / NEXUS_REGISTRY_PORT / NEXUS_HEAP
  local web_port reg_port extra_port heap container_started=0
  local -a additional_port_args=()
  web_port="$(ask NEXUS_WEB_PORT "Nexus Web 포트 [8081]: " "8081")"
  reg_port="$(ask NEXUS_REGISTRY_PORT "Nexus Docker Registry 포트 [5000]: " "5000")"
  extra_port="$(ask NEXUS_ADDITIONAL_REGISTRY_PORT "추가 Docker Registry 포트 [없음]: " "")"
  NEXUS_WEB_PORT="$web_port"; NEXUS_REGISTRY_PORT="$reg_port"; NEXUS_ADDITIONAL_REGISTRY_PORT="$extra_port"
  nexus_validate_ports "$web_port" "$reg_port" || return 1
  [[ -z "$extra_port" ]] || additional_port_args=(-p "${extra_port}:${extra_port}")
  # 이미지 기본값은 -Xmx2703m + MaxDirectMemorySize 2703m 로 상시 5~6GB 를 잡는다.
  # 사내 규모(프로젝트 수십 개 수준)에선 1g 로 충분하며 호스트 RAM 요구를 4GB 낮춘다.
  # 대형 저장소로 늘릴 때는 NEXUS_HEAP 만 올리면 된다(Direct 도 같은 값으로 맞춘다).
  heap="$(ask NEXUS_HEAP "Nexus 힙 크기 [1g]: " "1g")"
  NEXUS_HEAP="$heap"

  image_matches_archive_id "$NEXUS_IMAGE" "${LOCK_NEXUS_ARCHIVE_ID:-}" \
    || { err "Nexus tag의 image ID가 검증된 archive lock과 다릅니다."; return 1; }
  # Must finish before any container replacement: a missing/wrong key is never repaired by reinstalling.
  nexus_secrets_prepare || return 1
  if container_exists "$NEXUS_CONTAINER"; then
    if [[ "$force" != "force" ]] && nexus_runtime_contract_base "$web_port" "$reg_port"; then
      info "기존 Nexus 컨테이너의 image/health/port 계약이 일치합니다. repository drift만 점검합니다."
    else
      err "기존 Nexus 컨테이너에 image/health/port drift가 있습니다."
      recreate_container_if_confirmed "$NEXUS_CONTAINER" \
        || { err "drift 상태를 유지한 채 설치 완료로 처리할 수 없습니다."; return 1; }
    fi
  fi

  if ! container_exists "$NEXUS_CONTAINER"; then
    # Only introduce an ownership label for a genuinely new dedicated interactive volume.
    if [[ "${COMPONENT_STATE_DIR:-}" == /root/deploy-installer/deploy ]]; then
      /usr/libexec/platform-python -I -B "$SCRIPT_DIR/lib/nexus-volume-ownership.py" \
        "$COMPONENT_STATE_DIR" "$NEXUS_VOLUME" || return 1
    fi
    info "Nexus 컨테이너 기동... (힙 $heap)"
    docker run -d \
      --name "$NEXUS_CONTAINER" \
      --restart always \
      -p "${web_port}:8081" \
      -p "${reg_port}:${reg_port}" \
      "${additional_port_args[@]}" \
      -e INSTALL4J_ADD_VM_PARAMS="-Xms${heap} -Xmx${heap} -XX:MaxDirectMemorySize=${heap} -Djava.util.prefs.userRoot=/nexus-data/javaprefs" \
      -e NEXUS_SECRETS_KEY_FILE=/run/nexus-secrets/secrets.json \
      -v "$(nexus_secrets_path):/run/nexus-secrets/secrets.json:ro,Z" \
      -v "${NEXUS_VOLUME}:/nexus-data" \
      "$NEXUS_IMAGE" || { err "Nexus 컨테이너 기동 실패"; return 1; }
    container_started=1
  fi

  info "Nexus health 응답을 기다립니다(최대 5분)..."
  wait_container_contract "$NEXUS_CONTAINER" "http://127.0.0.1:${web_port}/service/rest/v1/status" 60 \
    || { err "Nexus health 확인 실패"; return 1; }
  nexus_runtime_contract_base "$web_port" "$reg_port" \
    || { err "Nexus 기동 후 image/health/port 계약 검증 실패"; return 1; }
  success "Nexus 기동·기본 계약 검증 완료: web :$web_port / registry :$reg_port / 힙 $heap"

  # ── 초기 자동 구성(선택): Realm + docker(hosted) 저장소 + HTTP 커넥터
  # 무인 키: NEXUS_AUTO_SETUP(y/n, 기본 y) / NEXUS_ADMIN_PASSWORD(새 비번, 비우면 초기비번 유지)
  #          NEXUS_DOCKER_REPO_NAME(기본 docker-hosted)
  if ask_confirm NEXUS_AUTO_SETUP "Nexus 초기 구성(docker 저장소 + 레지스트리 포트 + Realm)을 자동으로 할까요?" y; then
    local setup_result=0
    nexus_auto_setup "$web_port" "$reg_port" || setup_result=$?
    if (( setup_result == NEXUS_EULA_PENDING_RC )); then
      # Recheck after the authenticated pending response; never preserve an unverified runtime.
      if nexus_runtime_contract_base "$web_port" "$reg_port"; then
        warn "Nexus EULA_PENDING: Web :$web_port는 약관 검토를 위해 유지합니다. Registry 설치는 완료되지 않았습니다."
        info "Nexus UI에서 약관을 처리한 뒤 현재 관리자 비밀번호를 지정하고 FORCE_REINSTALL=n으로 다시 실행하세요."
        return "$NEXUS_EULA_PENDING_RC"
      fi
      err "EULA 대기 중 Nexus 기본 계약이 달라졌습니다. 대기 상태로 유지할 수 없습니다."
      setup_result=1
    fi
    if (( setup_result != 0 )); then
      err "Nexus 자동 구성 실패 — registry를 사용할 수 없으므로 설치를 중단합니다."
      if (( container_started )); then
        docker rm -f "$NEXUS_CONTAINER" >/dev/null 2>&1 \
          || err "실패한 Nexus 컨테이너 정리 실패: $NEXUS_CONTAINER"
      fi
      info "데이터 volume은 보존했습니다. 원인을 수정한 뒤 재실행하세요."
      return 1
    fi
  else
    info "초기 admin 비번: docker exec $NEXUS_CONTAINER cat /nexus-data/admin.password"
    info "Registry 포트($reg_port)는 Nexus UI에서 docker(hosted) repository의 HTTP 커넥터로 노출해야 합니다."
    err "수동 구성은 아직 검증되지 않아 Nexus를 installed로 처리하지 않습니다. UI 구성 후 NEXUS_AUTO_SETUP=y로 다시 실행해 계약을 검증하세요."
    return 1
  fi
}

nexus_repository_contract_json() {
  local file="$1" jq_bin="$2" repo_name="$3" reg_port="$4"
  "$jq_bin" -e --arg name "$repo_name" --argjson port "$reg_port" '
    .name == $name
    and .format == "docker"
    and .type == "hosted"
    and .online == true
    and .storage.writePolicy == "ALLOW_ONCE"
    and .storage.strictContentTypeValidation == true
    and .storage.latestPolicy == false
    and .docker.httpPort == $port
    and .docker.v1Enabled == false
    and .docker.forceBasicAuth == false
  ' "$file" >/dev/null 2>&1
}

nexus_repository_payload() {
  local jq_bin="$1" repo_name="$2" reg_port="$3"
  "$jq_bin" -cn --arg name "$repo_name" --argjson port "$reg_port" '{
    name:$name, online:true,
    storage:{blobStoreName:"default", strictContentTypeValidation:true, writePolicy:"ALLOW_ONCE", latestPolicy:false},
    docker:{v1Enabled:false, forceBasicAuth:false, httpPort:$port}
  }'
}

# Nexus REST API로 초기 구성: admin 비번 변경(선택) → Docker Bearer Token Realm → docker(hosted) 저장소
nexus_auto_setup() {
  local web_port="$1" reg_port="$2"
  local base="http://127.0.0.1:$web_port" i pass new_pass repo_name jq_bin
  jq_bin="$(docker_jq_binary)" || return 1

  info "Nexus 초기화 대기(최대 5분)..."
  for i in $(seq 1 60); do
    curl -fsS -o /dev/null --max-time 3 "$base/service/rest/v1/status" 2>/dev/null && break
    sleep 5
  done
  curl -fsS -o /dev/null --max-time 3 "$base/service/rest/v1/status" 2>/dev/null \
    || { err "Nexus 가 아직 응답하지 않습니다."; return 1; }

  # admin 비밀번호 확보: 신규 설치면 초기비번 파일, 기성 설치면 NEXUS_ADMIN_PASSWORD 로
  pass="$(docker exec "$NEXUS_CONTAINER" cat /nexus-data/admin.password 2>/dev/null || true)"
  if [[ -z "$pass" ]]; then
    pass="${NEXUS_ADMIN_PASSWORD:-}"
    [[ -n "$pass" ]] || { err "현재 Nexus admin 비밀번호를 알 수 없습니다(NEXUS_ADMIN_PASSWORD 지정 필요)."; return 1; }
  fi

  # EULA는 설치기가 대신 동의하지 않는다. 미수락 상태면 관리자가 약관을
  # 검토한 뒤 Nexus UI에서 직접 선택해야 하며, 계약 검증도 그 뒤에 재실행한다.
  local eula_json eula_accepted
  eula_json="$(curl -fsS --max-time 10 -u "admin:$pass" "$base/service/rest/v1/system/eula" 2>/dev/null)" \
    || { err "Nexus EULA 상태 조회 실패."; return 1; }
  eula_accepted="$(printf '%s' "$eula_json" | "$jq_bin" -er '.accepted | if type == "boolean" then tostring else error("boolean required") end' 2>/dev/null)" \
    || { err "Nexus EULA 응답에 accepted 상태가 없습니다."; return 1; }
  if [[ "$eula_accepted" != "true" ]]; then
    err "Nexus EULA 미수락 상태입니다. 설치기가 약관에 동의하지 않습니다. 관리자가 Nexus UI에서 약관을 검토·처리한 뒤 다시 실행하세요."
    return "$NEXUS_EULA_PENDING_RC"
  fi

  new_pass="$(ask_secret NEXUS_ADMIN_PASSWORD "Nexus admin 새 비밀번호 [엔터=현재 비밀번호 유지]: " allow_empty)"
  if [[ -n "$new_pass" && "$pass" != "$new_pass" ]]; then
    if curl -fsS -u "admin:$pass" -X PUT -H 'Content-Type: text/plain' -d "$new_pass" \
        "$base/service/rest/v1/security/users/admin/change-password"; then
      pass="$new_pass"; info "admin 비밀번호 변경 완료."
    else
      err "admin 비밀번호 변경 실패."
      return 1
    fi
  fi

  # docker login 이 동작하려면 Docker Bearer Token Realm 필요. 기존 LDAP 등 realm은 보존한다.
  local realms_json realms_payload
  realms_json="$(curl -fsS -u "admin:$pass" "$base/service/rest/v1/security/realms/active" 2>/dev/null)" \
    || { err "현재 Realm 설정 조회 실패."; return 1; }
  realms_payload="$(printf '%s' "$realms_json" | "$jq_bin" -ce \
    'if type != "array" then error("realm response is not an array") elif index("DockerToken") then . else . + ["DockerToken"] end' 2>/dev/null)" \
    || { err "현재 Realm 설정 JSON 해석 실패."; return 1; }
  if curl -fsS -u "admin:$pass" -X PUT -H 'Content-Type: application/json' \
      -d "$realms_payload" \
      "$base/service/rest/v1/security/realms/active"; then
    info "Docker Bearer Token Realm 활성화."
  else
    err "Realm 설정 실패 — Docker Bearer Token 없이는 registry 로그인이 동작하지 않습니다."
    return 1
  fi

  repo_name="$(ask NEXUS_DOCKER_REPO_NAME "docker(hosted) 저장소 이름 [docker-hosted]: " "docker-hosted")"
  [[ "$repo_name" =~ ^[A-Za-z0-9._-]+$ ]] \
    || { err "Nexus 저장소 이름은 영문·숫자·점·밑줄·하이픈만 사용할 수 있습니다."; return 1; }
  local repositories_json repository_exists repository_detail repository_payload
  repositories_json="$(curl -fsS -u "admin:$pass" "$base/service/rest/v1/repositories" 2>/dev/null)" \
    || { err "Nexus 저장소 목록 조회 실패."; return 1; }
  repository_exists="$(printf '%s' "$repositories_json" | "$jq_bin" -r --arg name "$repo_name" \
    'if type != "array" then error("repository response is not an array") else any(.[]; .name == $name) end' 2>/dev/null)" \
    || { err "Nexus 저장소 목록 JSON 해석 실패."; return 1; }
  if [[ "$repository_exists" == "true" ]]; then
    repository_detail="$(mktemp)" || return 1
    if ! curl -fsS -u "admin:$pass" -o "$repository_detail" \
        "$base/service/rest/v1/repositories/docker/hosted/$repo_name"; then
      rm -f "$repository_detail"; err "기존 저장소 상세 조회 실패."; return 1
    fi
    if nexus_repository_contract_json "$repository_detail" "$jq_bin" "$repo_name" "$reg_port"; then
      info "저장소 '$repo_name'의 format/type/httpPort/writePolicy 계약이 일치합니다."
    else
      rm -f "$repository_detail"
      err "저장소 '$repo_name'에 format/type/httpPort/writePolicy drift가 있습니다."
      ask_confirm NEXUS_REPAIR_DRIFT "기존 docker(hosted) 저장소를 설치 계약값으로 복구할까요?" n \
        || { err "Nexus repository drift를 복구하지 않아 설치를 중단합니다."; return 1; }
      repository_payload="$(nexus_repository_payload "$jq_bin" "$repo_name" "$reg_port")" \
        || { err "Nexus 저장소 복구 JSON 생성 실패."; return 1; }
      curl -fsS -u "admin:$pass" -X PUT -H 'Content-Type: application/json' \
        -d "$repository_payload" "$base/service/rest/v1/repositories/docker/hosted/$repo_name" \
        || { err "Nexus 저장소 drift 복구 실패."; return 1; }
      info "Nexus 저장소 drift 복구 요청 완료."
    fi
    rm -f "$repository_detail"
  else
    repository_payload="$(nexus_repository_payload "$jq_bin" "$repo_name" "$reg_port")" \
      || { err "Nexus 저장소 생성 JSON 생성 실패."; return 1; }
    curl -fsS -u "admin:$pass" -X POST -H 'Content-Type: application/json' -d "$repository_payload" \
      "$base/service/rest/v1/repositories/docker/hosted" \
      || { err "저장소 생성 실패."; return 1; }
    success "docker(hosted) '$repo_name' 생성 — registry :$reg_port 사용 가능."
  fi
  repository_detail="$(mktemp)" || return 1
  if ! curl -fsS -u "admin:$pass" -o "$repository_detail" \
      "$base/service/rest/v1/repositories/docker/hosted/$repo_name"; then
    rm -f "$repository_detail"; err "Nexus 저장소 최종 상세 조회 실패."; return 1
  fi
  nexus_repository_contract_json "$repository_detail" "$jq_bin" "$repo_name" "$reg_port" \
    || { rm -f "$repository_detail"; err "Nexus 저장소 최종 계약 검증 실패."; return 1; }
  rm -f "$repository_detail"
  NEXUS_ADMIN_PASSWORD="$pass"
  export NEXUS_ADMIN_PASSWORD
  nexus_setup_maven_if_selected "$base" "$jq_bin" || return 1
  nexus_runtime_contract "$web_port" "$reg_port" \
    || { err "Nexus EULA/Realm/repository 최종 drift 검증 실패."; return 1; }
  success "Nexus repository 계약 검증 완료: docker/hosted, httpPort=$reg_port, writePolicy=ALLOW_ONCE, latestPolicy=false"
  # forceBasicAuth=false: docker CLI 의 Bearer 토큰 로그인 플로우가 401 챌린지를 받게 한다
  #   (true 면 /v2/ 가 토큰 챌린지 없이 막혀 `docker login` 이 실패). 인증 자체는 DockerToken realm 이 담당.
  info "다음 단계: CI 계정 생성(권장) 후 GitLab CI 변수 NEXUS_REGISTRY_USER/PASSWORD 등록, 각 docker 호스트 insecure-registries 에 <IP>:$reg_port."
}
