#!/usr/bin/env bash
# gitlab-runner.sh - GitLab Runner(docker 이미지) 설치 컴포넌트
#   번들: bundles/common/gitlab-runner/*.tar.gz
#         (release BOM의 runner + 정확히 일치하는 helper + 기본 build image)

RUNNER_CONTAINER="${RUNNER_CONTAINER:-worksystem-gitlab-runner}"
RUNNER_IMAGE="$BOM_RUNNER_IMAGE"
RUNNER_HELPER_IMAGE="$BOM_RUNNER_HELPER_IMAGE"
RUNNER_BUILD_IMAGE="$BOM_RUNNER_BUILD_IMAGE"
RUNNER_CONFIG="${RUNNER_CONFIG:-/srv/gitlab-runner/config}"
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib/java-ci-payload.sh" || return 1

gitlab_runner_ci_contract() {
  local root="${BUNDLE_ROOT:-}/common/java-ci" jq_bin
  [[ -e "$root" || -L "$root" ]] || return 0
  jq_bin="$(docker_jq_binary)" || return 1
  java_ci_loaded_contract "$root" "$jq_bin"
}

gitlab_runner_ci_preload() {
  local root="${BUNDLE_ROOT:-}/common/java-ci" jq_bin
  [[ -e "$root" || -L "$root" ]] || return 0
  jq_bin="$(docker_jq_binary)" || return 1
  java_ci_preload "$root" "$jq_bin"
}

prepare_runner_config() {
  install -d -o root -g root -m 0750 "$RUNNER_CONFIG"
  if [[ ! -e "$RUNNER_CONFIG/config.toml" ]]; then
    install -o root -g root -m 0600 /dev/null "$RUNNER_CONFIG/config.toml"
  fi
}

gitlab_runner_status() {
  if container_exists "$RUNNER_CONTAINER"; then
    if gitlab_runner_runtime_contract; then
      if [[ -s "$RUNNER_CONFIG/config.toml" ]]; then
        if gitlab_runner_registered_config_contract \
            && docker exec "$RUNNER_CONTAINER" gitlab-runner verify >/dev/null 2>&1 \
            && gitlab_runner_server_policy_contract; then
          echo installed
        elif ! gitlab_runner_registered_config_contract; then
          echo drift
        else
          # 등록된 Runner를 자격 증명 없이 단순 runtime만 보고 installed로 오판하지 않는다.
          echo unverified
        fi
      elif [[ -e "$RUNNER_CONFIG/policy.json" ]]; then
        echo drift
      else
        # Runner 등록은 선택 사항이다. 빈 manager container 자체의 runtime 계약은 완료 상태다.
        echo unregistered
      fi
    else
      echo drift
    fi
  elif image_present "$RUNNER_IMAGE"; then echo partial
  else echo missing; fi
}

gitlab_runner_runtime_contract() {
  image_matches_archive_id "$RUNNER_IMAGE" "${LOCK_RUNNER_ARCHIVE_ID:-}" \
    && image_matches_archive_id "$RUNNER_HELPER_IMAGE" "${LOCK_RUNNER_HELPER_ARCHIVE_ID:-}" \
    && image_matches_archive_id "$RUNNER_BUILD_IMAGE" "${LOCK_RUNNER_BUILD_ARCHIVE_ID:-}" \
    && container_matches_archive_id "$RUNNER_CONTAINER" "${LOCK_RUNNER_ARCHIVE_ID:-}" \
    && container_runtime_healthy "$RUNNER_CONTAINER" \
    && container_mount_destination_present "$RUNNER_CONTAINER" /etc/gitlab-runner \
    && container_mount_destination_present "$RUNNER_CONTAINER" /var/run/docker.sock \
    && docker exec "$RUNNER_CONTAINER" gitlab-runner --version >/dev/null 2>&1 \
    && gitlab_runner_ci_contract
  # CI readiness is separate from server registration/policy verification.
}

gitlab_runner_install() {
  local force="${1:-}"
  docker_available || { err "Docker 가 먼저 필요합니다. 'docker' 컴포넌트를 설치하세요."; return 1; }
  gitlab_runner_ci_preload || { err '검증된 Java CI 이미지 preload/identity 검사 실패'; return 1; }
  if [[ -s "$RUNNER_CONFIG/config.toml" ]] && ! gitlab_runner_registered_config_contract; then
    err "기존 Runner config.toml의 helper_image/기본 이미지/clone_url/오프라인 pull 정책을 현재 번들과 맞춰야 합니다."
    err "필요한 helper_image: $RUNNER_HELPER_IMAGE"
    err "설치기는 등록 파일을 자동으로 바꾸지 않습니다. 백업 후 Runner를 명시적으로 재등록하세요."
    return 1
  fi

  if ! image_matches_archive_id "$RUNNER_IMAGE" "${LOCK_RUNNER_ARCHIVE_ID:-}" \
      || ! image_matches_archive_id "$RUNNER_HELPER_IMAGE" "${LOCK_RUNNER_HELPER_ARCHIVE_ID:-}" \
      || ! image_matches_archive_id "$RUNNER_BUILD_IMAGE" "${LOCK_RUNNER_BUILD_ARCHIVE_ID:-}" \
      || [[ "$force" == "force" ]]; then
    image_present "$RUNNER_IMAGE" \
      && warn "Runner local image tag drift를 검증된 archive로 교체합니다."
    docker_load_bundle gitlab-runner || return 1
  fi
  local required_image
  for required_image in "$RUNNER_IMAGE" "$RUNNER_HELPER_IMAGE" "$RUNNER_BUILD_IMAGE"; do
    image_present "$required_image" || {
      err "Runner 오프라인 필수 이미지가 없습니다: $required_image"
      err "release BOM에 맞춰 gitlab-runner 번들을 다시 생성하세요."
      return 1
    }
  done
  image_matches_archive_id "$RUNNER_IMAGE" "${LOCK_RUNNER_ARCHIVE_ID:-}" \
    && image_matches_archive_id "$RUNNER_HELPER_IMAGE" "${LOCK_RUNNER_HELPER_ARCHIVE_ID:-}" \
    && image_matches_archive_id "$RUNNER_BUILD_IMAGE" "${LOCK_RUNNER_BUILD_ARCHIVE_ID:-}" \
    || { err "Runner 필수 image tag가 검증된 archive lock과 다릅니다."; return 1; }
  if container_exists "$RUNNER_CONTAINER"; then
    if [[ "$force" != "force" ]] && gitlab_runner_runtime_contract; then
      if [[ -s "$RUNNER_CONFIG/config.toml" ]]; then
        if gitlab_runner_registered_config_contract \
            && docker exec "$RUNNER_CONTAINER" gitlab-runner verify >/dev/null 2>&1 \
            && gitlab_runner_server_policy_contract; then
          info "Runner 런타임과 GitLab 서버 정책 계약이 일치합니다 — 건너뜀."; return 0
        fi
        if ! gitlab_runner_registered_config_contract; then
          err "등록된 Runner의 helper_image/기본 이미지/clone_url/Docker pull 정책이 현재 번들과 다릅니다."
          err "config.toml을 백업한 뒤 Runner를 명시적으로 재등록하세요. 기존 등록 설정은 자동 변경하지 않았습니다."
          return 1
        fi
        err "Runner는 등록됐지만 GitLab 서버의 tag/run-untagged/protected/locked 정책을 검증할 수 없거나 drift가 있습니다."
        err "GITLAB_RUNNER_API_TOKEN과 $RUNNER_CONFIG/policy.json을 확인한 뒤 서버 정책을 복구하세요."
        return 1
      fi
      if [[ -e "$RUNNER_CONFIG/policy.json" ]]; then
        err "Runner policy 증적은 있지만 로컬 등록 config.toml이 없습니다. 불완전 등록을 수동 정리하세요."
        return 1
      fi
      if ! runner_true_value "${GITLAB_RUNNER_REGISTER:-n}"; then
        info "Runner 이미지·상태·mount 계약이 일치합니다 — 건너뜀."; return 0
      fi
      info "Runner 컨테이너는 정상이며 아직 등록되지 않았습니다. 등록 단계로 계속합니다."
    else
      err "기존 Runner 컨테이너에 image/health/mount drift가 있습니다."
      recreate_container_if_confirmed "$RUNNER_CONTAINER" \
        || { err "drift 상태를 유지한 채 설치 완료로 처리할 수 없습니다."; return 1; }
    fi
  fi

  if ! container_exists "$RUNNER_CONTAINER"; then
    prepare_runner_config || { err "Runner 설정 디렉터리를 안전하게 준비하지 못했습니다."; return 1; }
    info "GitLab Runner 컨테이너 기동..."
    docker run -d \
      --name "$RUNNER_CONTAINER" \
      --restart always \
      -v "$RUNNER_CONFIG:/etc/gitlab-runner:Z" \
      -v /var/run/docker.sock:/var/run/docker.sock \
      "$RUNNER_IMAGE" || { err "Runner 컨테이너 기동 실패"; return 1; }

    wait_container_contract "$RUNNER_CONTAINER" "" 12 \
      && gitlab_runner_runtime_contract \
      || { err "Runner 기동 후 image/health/mount 계약 검증 실패"; return 1; }
    success "GitLab Runner 기동·계약 검증 완료."
  fi

  # ── 자동 등록(선택) — 프로젝트 CI와 맞는 tag/protection 정책까지 답변파일로 재현
  # 권장: 전용 서비스 계정 PAT(create_runner + manage_runner + read_api scope)로 생성 후 상세 정책까지 검증한다.
  # 대안: 이미 만든 GITLAB_RUNNER_TOKEN(glrt-…)은 ID와 읽기 PAT로 서버 정책을 대조한 뒤 사용한다.
  # 무인 키: GITLAB_RUNNER_REGISTER(y/n) / GITLAB_RUNNER_URL / GITLAB_RUNNER_API_TOKEN /
  #          GITLAB_RUNNER_TOKEN / GITLAB_RUNNER_ID / GITLAB_RUNNER_VERIFY_API_TOKEN /
  #          GITLAB_RUNNER_CLONE_URL(옵션: Runner가 GitLab에서 clone할 접근 가능 주소)
  #          GITLAB_RUNNER_NAME / GITLAB_RUNNER_DOCKER_IMAGE / GITLAB_RUNNER_DOCKER_NETWORK(옵션) /
  #          GITLAB_RUNNER_TYPE / GITLAB_RUNNER_PROJECT_ID|GROUP_ID /
  #          GITLAB_RUNNER_TAG_LIST / GITLAB_RUNNER_RUN_UNTAGGED / GITLAB_RUNNER_PROTECTED / GITLAB_RUNNER_LOCKED
  if ask_confirm GITLAB_RUNNER_REGISTER "지금 GitLab에 Runner를 등록할까요? (API 토큰 또는 기존 Runner 토큰 필요)" n; then
    local rurl rclone_url rtoken rapi_token verify_token runner_id rname rnet rimage rtags run_untagged protected locked
    local runner_type project_id group_id
    rurl="$(ask GITLAB_RUNNER_URL "GitLab URL (예: http://<GitLab호스트IP>:8929): " "")"
    if [[ -z "$rurl" ]]; then err "GitLab URL이 필요합니다."; return 1; fi
    rclone_url="$(ask GITLAB_RUNNER_CLONE_URL "Runner 작업 컨테이너에서 접근 가능한 clone URL (기본값: GitLab URL): " "${GITLAB_RUNNER_CLONE_URL:-}")"
    if [[ -n "$rclone_url" ]] && ! runner_clone_url_valid "$rclone_url"; then
      err "GITLAB_RUNNER_CLONE_URL은 사용자정보·쿼리·공백이 없는 http(s) URL이어야 합니다."
      return 1
    fi
    GITLAB_RUNNER_CLONE_URL="$rclone_url"
    rname="$(ask GITLAB_RUNNER_NAME "Runner 이름 [offline-docker-runner]: " "offline-docker-runner")"
    rimage="$(ask GITLAB_RUNNER_DOCKER_IMAGE "기본 빌드 이미지 [$RUNNER_BUILD_IMAGE]: " "$RUNNER_BUILD_IMAGE")"
    runner_build_image_contract "$rimage" || return 1
    rnet="$(ask GITLAB_RUNNER_DOCKER_NETWORK "빌드 컨테이너 docker network [없으면 엔터]: " "")"
    rtags="$(ask GITLAB_RUNNER_TAG_LIST "Runner 태그(쉼표 구분) [docker,offline]: " "docker,offline")"
    run_untagged="$(runner_bool_value GITLAB_RUNNER_RUN_UNTAGGED "$(ask GITLAB_RUNNER_RUN_UNTAGGED "태그 없는 job 실행 (y|n) [n]: " "n")")" || return 1
    protected="$(runner_bool_value GITLAB_RUNNER_PROTECTED "$(ask GITLAB_RUNNER_PROTECTED "protected ref job 전용 (y|n) [y]: " "y")")" || return 1
    locked="$(runner_bool_value GITLAB_RUNNER_LOCKED "$(ask GITLAB_RUNNER_LOCKED "현재 프로젝트에 lock (y|n) [y]: " "y")")" || return 1
    [[ -n "$rtags" || "$run_untagged" == "true" ]] || {
      err "GITLAB_RUNNER_TAG_LIST가 비어 있으면 GITLAB_RUNNER_RUN_UNTAGGED=true여야 합니다."
      return 1
    }

    rtoken="$(ask_secret GITLAB_RUNNER_TOKEN "기존 glrt-* Runner 토큰 [새로 만들면 엔터]: " allow_empty)" || return 1
    if [[ -z "$rtoken" ]]; then
      rapi_token="$(ask_secret GITLAB_RUNNER_API_TOKEN "Runner 생성 PAT(create_runner + manage_runner + read_api): ")" || return 1
      runner_type="$(ask GITLAB_RUNNER_TYPE "Runner 범위(project_type|group_type|instance_type) [project_type]: " "project_type")"
      project_id="$(ask GITLAB_RUNNER_PROJECT_ID "Project ID(project_type일 때 필수): " "")"
      group_id="$(ask GITLAB_RUNNER_GROUP_ID "Group ID(group_type일 때 필수): " "")"
      rtoken="$(create_runner_auth_token "$rurl" "$rapi_token" "$runner_type" "$project_id" "$group_id" \
        "$rname" "$rtags" "$run_untagged" "$protected" "$locked")" || return 1
      # 현재 installer process의 후속 status도 즉시 같은 PAT로 서버 정책을 재조회한다.
      GITLAB_RUNNER_API_TOKEN="$rapi_token"
      success "GitLab API에 Runner 정책 생성 완료: type=$runner_type, tags=$rtags"
    else
      [[ "$rtoken" == glrt-* ]] \
        || { err "기존 Runner 경로는 정책 조회가 가능한 glrt-* 인증 토큰만 허용합니다."; return 1; }
      runner_id="$(ask GITLAB_RUNNER_ID "기존 GitLab Runner 숫자 ID: " "")"
      [[ "$runner_id" =~ ^[1-9][0-9]*$ ]] \
        || { err "GITLAB_RUNNER_ID는 양의 정수여야 합니다."; return 1; }
      verify_token="$(ask_secret GITLAB_RUNNER_VERIFY_API_TOKEN "기존 Runner 검증 PAT(manage_runner + read_api): ")" || return 1
      runner_type="$(ask GITLAB_RUNNER_TYPE "Runner 범위(project_type|group_type|instance_type) [project_type]: " "project_type")"
      if ! verify_and_persist_existing_runner_policy "$rurl" "$verify_token" "$runner_id" \
          "$runner_type" "$rtags" "$run_untagged" "$protected" "$locked"; then
        err "기존 Runner의 서버 정책을 검증·보존할 수 없습니다."
        return 1
      fi
      GITLAB_RUNNER_API_TOKEN="$verify_token"
    fi

    local reg_args=() template_path template_in_container registration_rc
    template_path="$(mktemp "$RUNNER_CONFIG/registration-template.XXXXXX")" || {
      err "Runner 등록 템플릿을 안전하게 만들지 못했습니다."; return 1;
    }
    chmod 0600 "$template_path" || { rm -f "$template_path"; return 1; }
    write_runner_registration_template "$template_path" "$rclone_url" \
      || { rm -f "$template_path"; return 1; }
    template_in_container="/etc/gitlab-runner/${template_path##*/}"
    build_runner_registration_args reg_args "$rurl" "$rtoken" "$rname" "$rimage" "$rnet" \
      "$rtags" "$run_untagged" "$protected" "$locked" "$template_in_container" \
      || { rm -f "$template_path"; return 1; }
    if runner_register_secret "$rtoken" "${reg_args[@]}"; then
      registration_rc=0
      success "Runner 등록 완료: $rname → $rurl (tags=$rtags, protected=$protected, locked=$locked)"
    else
      registration_rc=$?
      err "Runner 등록 실패 — URL/토큰을 확인하세요. 수동: docker exec -it $RUNNER_CONTAINER gitlab-runner register"
    fi
    rm -f "$template_path" || { err "임시 Runner 등록 템플릿을 제거하지 못했습니다."; return 1; }
    (( registration_rc == 0 )) || return "$registration_rc"
    gitlab_runner_registered_config_contract || {
      err "Runner 등록 후 clone_url 또는 폐쇄망 Docker pull 정책 계약을 확인하지 못했습니다."; return 1;
    }
  else
    info "등록 생략 — 수동: docker exec -it $RUNNER_CONTAINER gitlab-runner register"
    info "  → GitLab URL, 토큰, executor(docker), 기본 이미지($RUNNER_BUILD_IMAGE), docker.sock 볼륨."
  fi
}

runner_bool_value() {
  local name="$1" value="${2,,}"
  case "$value" in
    y|yes|true|1) printf 'true' ;;
    n|no|false|0) printf 'false' ;;
    *) err "$name 은(는) y/n 또는 true/false 여야 합니다."; return 1 ;;
  esac
}

runner_true_value() {
  case "${1,,}" in y|yes|true|1) return 0 ;; *) return 1 ;; esac
}

runner_clone_url_valid() {
  local value="$1"
  [[ "$value" =~ ^https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?(/[A-Za-z0-9._~/-]*)?$ ]]
}

runner_build_image_contract() {
  local candidate="$1"
  [[ "$candidate" == "$RUNNER_BUILD_IMAGE" ]] || {
    err "폐쇄망 Runner job 기본 이미지는 번들에 고정된 이미지($RUNNER_BUILD_IMAGE)만 사용할 수 있습니다."
    err "사용자 지정 이미지는 승인된 오프라인 번들에 포함하고 release lock 검증을 추가한 뒤 사용하세요."
    return 1
  }
  image_present "$RUNNER_BUILD_IMAGE" \
    && image_matches_archive_id "$RUNNER_BUILD_IMAGE" "${LOCK_RUNNER_BUILD_ARCHIVE_ID:-}" \
    || { err "Runner job 기본 이미지가 검증된 오프라인 번들에서 preload되지 않았습니다."; return 1; }
}

write_runner_registration_template() {
  local output="$1" clone_url="${2:-}"
  [[ -z "$clone_url" ]] || runner_clone_url_valid "$clone_url" \
    || { err "GITLAB_RUNNER_CLONE_URL 형식이 올바르지 않습니다."; return 1; }
  {
    printf '[[runners]]\n'
    [[ -z "$clone_url" ]] || printf '  clone_url = "%s"\n' "$clone_url"
    printf '[runners.docker]\n  pull_policy = ["never"]\n  allowed_pull_policies = ["never"]\n'
  } > "$output"
  chmod 0600 "$output"
}

# Registered Docker runner settings are part of the closed-network runtime contract.
# The config is only inspected here; existing registrations are never rewritten implicitly.
gitlab_runner_registered_config_contract() {
  local config="$RUNNER_CONFIG/config.toml"
  [[ -s "$config" ]] || return 1
  awk -v expected_clone="${GITLAB_RUNNER_CLONE_URL:-}" -v expected_image="$RUNNER_BUILD_IMAGE" \
      -v expected_helper="$RUNNER_HELPER_IMAGE" '
    function valid_runner() {
      return seen_executor && executor_value == "docker" && seen_pull && seen_allowed \
        && seen_image && image_value == expected_image \
        && seen_helper && helper_value == expected_helper \
        && (expected_clone == "" || (seen_clone && clone_value == expected_clone))
    }
    /^\[\[runners\]\][[:space:]]*$/ {
      if (in_runner && !valid_runner()) bad=1
      in_runner=1; in_root=1; in_docker=0; count++
      seen_executor=0; executor_value=""; seen_pull=0; seen_allowed=0
      seen_image=0; image_value=""; seen_clone=0; clone_value=""; seen_helper=0; helper_value=""
      next
    }
    in_runner {
      if ($0 ~ /^[[:space:]]*\[/) {
        in_root=0
        in_docker=($0 ~ /^[[:space:]]*\[runners\.docker\][[:space:]]*$/)
      }
      if (in_root && $0 ~ /^[[:space:]]*executor[[:space:]]*=/) {
        line=$0
        sub(/^[[:space:]]*executor[[:space:]]*=[[:space:]]*"/, "", line)
        sub(/"[[:space:]]*(#.*)?$/, "", line)
        executor_value=line; seen_executor=1
      }
      if (in_docker && $0 ~ /^[[:space:]]*pull_policy[[:space:]]*=[[:space:]]*\["never"\][[:space:]]*$/) seen_pull=1
      if (in_docker && $0 ~ /^[[:space:]]*allowed_pull_policies[[:space:]]*=[[:space:]]*\["never"\][[:space:]]*$/) seen_allowed=1
      if (in_docker && $0 ~ /^[[:space:]]*image[[:space:]]*=/) {
        line=$0
        sub(/^[[:space:]]*image[[:space:]]*=[[:space:]]*"/, "", line)
        sub(/"[[:space:]]*(#.*)?$/, "", line)
        image_value=line; seen_image=1
      }
      if (in_docker && $0 ~ /^[[:space:]]*helper_image[[:space:]]*=/) {
        line=$0
        sub(/^[[:space:]]*helper_image[[:space:]]*=[[:space:]]*"/, "", line)
        sub(/"[[:space:]]*(#.*)?$/, "", line)
        helper_value=line; seen_helper=1
      }
      if (in_root && $0 ~ /^[[:space:]]*clone_url[[:space:]]*=/) {
        line=$0
        sub(/^[[:space:]]*clone_url[[:space:]]*=[[:space:]]*"/, "", line)
        sub(/"[[:space:]]*(#.*)?$/, "", line)
        clone_value=line; seen_clone=1
      }
    }
    END {
      if (in_runner && !valid_runner()) bad=1
      exit (count > 0 && !bad) ? 0 : 1
    }
  ' "$config"
}

create_runner_auth_token() {
  local rurl="$1" api_token="$2" runner_type="$3" project_id="$4" group_id="$5"
  local description="$6" tag_list="$7" run_untagged="$8" protected="$9" locked="${10}"
  local jq_bin response detail token runner_id access_level="not_protected"
  local api_args=()
  command -v curl >/dev/null 2>&1 || { err "Runner 생성 API 호출에 curl이 필요합니다."; return 1; }
  jq_bin="$(docker_jq_binary)" || return 1
  [[ "$protected" == "true" ]] && access_level="ref_protected"
  build_runner_create_api_args api_args "$runner_type" "$project_id" "$group_id" "$description" \
    "$tag_list" "$run_untagged" "$locked" "$access_level" || return 1
  validate_runner_api_pat "$rurl" "$api_token" "$runner_type" "$project_id" "$group_id" "$jq_bin" \
    || return 1
  response="$(mktemp)" || return 1
  chmod 600 "$response" || { rm -f "$response"; return 1; }
  if ! runner_api_curl "$api_token" -fsS --connect-timeout 5 --max-time 30 --request POST \
      \
      --output "$response" \
      "${api_args[@]}" \
      "${rurl%/}/api/v4/user/runners"; then
    rm -f "$response"
    err "GitLab Runner 생성 API 실패 — create_runner/manage_runner/read_api scope와 Owner/Admin 역할을 확인하세요."
    return 1
  fi
  token="$("$jq_bin" -er '.token | select(type == "string" and length > 0)' "$response" 2>/dev/null)" \
    || { rm -f "$response"; err "Runner 생성 응답에 인증 토큰이 없습니다."; return 1; }
  runner_id="$("$jq_bin" -er '.id | select(type == "number")' "$response" 2>/dev/null)" \
    || { rm -f "$response"; err "Runner 생성 응답에 숫자 ID가 없습니다."; return 1; }
  rm -f "$response"
  [[ "$token" == glrt-* ]] \
    || { err "Runner 생성 API가 예상한 glrt-* 인증 토큰을 반환하지 않았습니다."; return 1; }
  detail="$(mktemp)" || return 1
  if ! runner_api_curl "$api_token" -fsS --connect-timeout 5 --max-time 30 \
      --output "$detail" \
      "${rurl%/}/api/v4/runners/$runner_id"; then
    rm -f "$detail"
    err "생성된 Runner 상세 조회 실패 — manage_runner scope와 대상 역할을 확인하세요."
    return 1
  fi
  if ! runner_detail_contract_json "$detail" "$jq_bin" "$runner_type" "$tag_list" \
      "$run_untagged" "$locked" "$access_level"; then
    rm -f "$detail"
    err "GitLab에 저장된 Runner 정책이 요청값과 다릅니다. Runner ID: $runner_id"
    return 1
  fi
  persist_runner_policy "$jq_bin" "$runner_id" "$rurl" "$runner_type" "$tag_list" \
    "$run_untagged" "$locked" "$access_level" \
    || { rm -f "$detail"; return 1; }
  rm -f "$detail"
  printf '%s' "$token"
}

persist_runner_policy() {
  local jq_bin="$1" runner_id="$2" rurl="$3" runner_type="$4" tag_list="$5"
  local run_untagged="$6" locked="$7" access_level="$8"
  mkdir -p "$RUNNER_CONFIG"
  local policy_tmp="$RUNNER_CONFIG/policy.json.part"
  "$jq_bin" -cn --argjson id "$runner_id" --arg url "${rurl%/}" \
    --arg runner_type "$runner_type" --arg tags "$tag_list" \
    --argjson run_untagged "$run_untagged" --argjson locked "$locked" \
    --arg access_level "$access_level" \
    '{id:$id,url:$url,runner_type:$runner_type,tags:$tags,run_untagged:$run_untagged,locked:$locked,access_level:$access_level}' \
    > "$policy_tmp" || { rm -f "$policy_tmp"; err "Runner policy 증적 저장 실패"; return 1; }
  chmod 0600 "$policy_tmp" || { rm -f "$policy_tmp"; return 1; }
  mv -f "$policy_tmp" "$RUNNER_CONFIG/policy.json"
}

verify_and_persist_existing_runner_policy() {
  local rurl="$1" token="$2" runner_id="$3" runner_type="$4" tags="$5"
  local run_untagged="$6" protected="$7" locked="$8" jq_bin token_json detail access_level=not_protected
  command -v curl >/dev/null 2>&1 || { err "기존 Runner 정책 조회에 curl이 필요합니다."; return 1; }
  jq_bin="$(docker_jq_binary)" || return 1
  token_json="$(mktemp)"; detail="$(mktemp)"
  if ! runner_api_curl "$token" -fsS --connect-timeout 5 --max-time 20 \
      --output "$token_json" "${rurl%/}/api/v4/personal_access_tokens/self" \
      || ! gitlab_pat_scopes_json_valid "$token_json" "$jq_bin" manage_runner read_api; then
    rm -f "$token_json" "$detail"
    err "기존 Runner 검증 PAT에는 manage_runner와 read_api scope가 필요합니다."
    return 1
  fi
  rm -f "$token_json"
  [[ "$protected" == true ]] && access_level=ref_protected
  if ! runner_api_curl "$token" -fsS --connect-timeout 5 --max-time 20 \
      --output "$detail" "${rurl%/}/api/v4/runners/$runner_id" \
      || ! runner_detail_contract_json "$detail" "$jq_bin" "$runner_type" "$tags" \
        "$run_untagged" "$locked" "$access_level"; then
    rm -f "$detail"
    return 1
  fi
  rm -f "$detail"
  persist_runner_policy "$jq_bin" "$runner_id" "$rurl" "$runner_type" "$tags" \
    "$run_untagged" "$locked" "$access_level"
}

gitlab_runner_server_policy_contract() {
  local policy="$RUNNER_CONFIG/policy.json" token="${GITLAB_RUNNER_API_TOKEN:-${GITLAB_RUNNER_VERIFY_API_TOKEN:-}}" jq_bin detail
  local runner_id url runner_type tags run_untagged locked access_level
  local desired_type desired_tags desired_run desired_locked desired_access=not_protected
  [[ -n "$token" && -r "$policy" ]] || return 1
  jq_bin="$(docker_jq_binary)" || return 1
  runner_id="$("$jq_bin" -er '.id | select(type == "number")' "$policy" 2>/dev/null)" || return 1
  url="$("$jq_bin" -er '.url | select(type == "string" and test("^https?://"))' "$policy" 2>/dev/null)" || return 1
  runner_type="$("$jq_bin" -er '.runner_type' "$policy" 2>/dev/null)" || return 1
  tags="$("$jq_bin" -er '.tags' "$policy" 2>/dev/null)" || return 1
  run_untagged="$("$jq_bin" -er '.run_untagged | if type == "boolean" then tostring else error("boolean required") end' "$policy" 2>/dev/null)" || return 1
  locked="$("$jq_bin" -er '.locked | if type == "boolean" then tostring else error("boolean required") end' "$policy" 2>/dev/null)" || return 1
  access_level="$("$jq_bin" -er '.access_level' "$policy" 2>/dev/null)" || return 1
  # 대화형 값은 다음 메뉴 렌더에서 env에 남지 않는다. policy.json 자체를 원본으로 삼되,
  # 답변파일에 명시된 값이 있으면 그 값과도 일치해야 한다.
  [[ -z "${GITLAB_RUNNER_URL+x}" || "$url" == "${GITLAB_RUNNER_URL%/}" ]] || return 1
  if [[ -n "${GITLAB_RUNNER_TYPE+x}" ]]; then
    desired_type="$GITLAB_RUNNER_TYPE"; [[ "$runner_type" == "$desired_type" ]] || return 1
  fi
  if [[ -n "${GITLAB_RUNNER_TAG_LIST+x}" ]]; then
    desired_tags="$GITLAB_RUNNER_TAG_LIST"; [[ "$tags" == "$desired_tags" ]] || return 1
  fi
  if [[ -n "${GITLAB_RUNNER_RUN_UNTAGGED+x}" ]]; then
    desired_run="$(runner_bool_value GITLAB_RUNNER_RUN_UNTAGGED "$GITLAB_RUNNER_RUN_UNTAGGED")" || return 1
    [[ "$run_untagged" == "$desired_run" ]] || return 1
  fi
  if [[ -n "${GITLAB_RUNNER_LOCKED+x}" ]]; then
    desired_locked="$(runner_bool_value GITLAB_RUNNER_LOCKED "$GITLAB_RUNNER_LOCKED")" || return 1
    [[ "$locked" == "$desired_locked" ]] || return 1
  fi
  if [[ -n "${GITLAB_RUNNER_PROTECTED+x}" ]]; then
    desired_access=not_protected
    runner_true_value "$GITLAB_RUNNER_PROTECTED" && desired_access=ref_protected
    [[ "$access_level" == "$desired_access" ]] || return 1
  fi
  detail="$(mktemp)" || return 1
  runner_api_curl "$token" -fsS --connect-timeout 5 --max-time 20 \
    --output "$detail" "$url/api/v4/runners/$runner_id" \
    && runner_detail_contract_json "$detail" "$jq_bin" "$runner_type" "$tags" \
      "$run_untagged" "$locked" "$access_level"
  local result=$?
  rm -f "$detail"
  return "$result"
}

gitlab_pat_scopes_json_valid() {
  local file="$1" jq_bin="$2"; shift 2
  local scope filter='(.active == true and .revoked == false)'
  for scope in "$@"; do
    filter+=" and ((.scopes // []) | index(\"$scope\") != null)"
  done
  "$jq_bin" -e "$filter" "$file" >/dev/null 2>&1
}

validate_runner_api_pat() {
  local rurl="$1" token="$2" runner_type="$3" project_id="$4" group_id="$5" jq_bin="$6"
  local token_json user_json member_json user_id access_level
  token_json="$(mktemp)"; user_json="$(mktemp)"; member_json="$(mktemp)"
  if ! runner_api_curl "$token" -fsS --connect-timeout 5 --max-time 20 \
      --output "$token_json" "${rurl%/}/api/v4/personal_access_tokens/self"; then
    rm -f "$token_json" "$user_json" "$member_json"
    err "Runner 서비스 PAT 자체 조회 실패 — 만료·폐기 여부와 URL을 확인하세요."
    return 1
  fi
  if ! gitlab_pat_scopes_json_valid "$token_json" "$jq_bin" create_runner manage_runner read_api; then
    rm -f "$token_json" "$user_json" "$member_json"
    err "Runner 서비스 PAT에는 create_runner, manage_runner, read_api scope가 모두 필요합니다."
    return 1
  fi
  if ! runner_api_curl "$token" -fsS --connect-timeout 5 --max-time 20 \
      --output "$user_json" "${rurl%/}/api/v4/user"; then
    rm -f "$token_json" "$user_json" "$member_json"
    err "Runner 서비스 계정 조회 실패."
    return 1
  fi
  user_id="$("$jq_bin" -er '.id | select(type == "number")' "$user_json" 2>/dev/null)" \
    || { rm -f "$token_json" "$user_json" "$member_json"; err "서비스 계정 ID를 확인할 수 없습니다."; return 1; }
  case "$runner_type" in
    project_type)
      runner_api_curl "$token" -fsS --connect-timeout 5 --max-time 20 \
        --output "$member_json" "${rurl%/}/api/v4/projects/$project_id/members/all/$user_id" \
        || { rm -f "$token_json" "$user_json" "$member_json"; err "Project membership 조회 실패."; return 1; }
      ;;
    group_type)
      runner_api_curl "$token" -fsS --connect-timeout 5 --max-time 20 \
        --output "$member_json" "${rurl%/}/api/v4/groups/$group_id/members/all/$user_id" \
        || { rm -f "$token_json" "$user_json" "$member_json"; err "Group membership 조회 실패."; return 1; }
      ;;
    instance_type)
      "$jq_bin" -e '.is_admin == true' "$user_json" >/dev/null 2>&1 \
        || { rm -f "$token_json" "$user_json" "$member_json"; err "instance Runner 생성은 GitLab Admin 계정이 필요합니다."; return 1; }
      rm -f "$token_json" "$user_json" "$member_json"
      success "Runner PAT 사전검증 완료: create_runner + manage_runner + read_api, GitLab Admin" >&2
      return 0
      ;;
  esac
  access_level="$("$jq_bin" -er '.access_level | select(type == "number")' "$member_json" 2>/dev/null)" \
    || { rm -f "$token_json" "$user_json" "$member_json"; err "서비스 계정 역할을 확인할 수 없습니다."; return 1; }
  rm -f "$token_json" "$user_json" "$member_json"
  (( access_level >= 50 )) \
    || { err "Runner 생성 서비스 계정은 대상 Project/Group의 Owner 역할이어야 합니다(access_level=50)."; return 1; }
  success "Runner PAT 사전검증 완료: create_runner + manage_runner + read_api, Owner" >&2
}

runner_detail_contract_json() {
  local file="$1" jq_bin="$2" runner_type="$3" tags="$4" run_untagged="$5" locked="$6" access_level="$7"
  "$jq_bin" -e --arg runner_type "$runner_type" --arg tags "$tags" \
    --argjson run_untagged "$run_untagged" --argjson locked "$locked" --arg access_level "$access_level" '
      .runner_type == $runner_type
      and .run_untagged == $run_untagged
      and .locked == $locked
      and .access_level == $access_level
      and ((.tag_list // [] | sort) == ($tags | split(",") | map(select(length > 0)) | sort))
    ' "$file" >/dev/null 2>&1
}

build_runner_create_api_args() {
  local -n out="$1"
  local runner_type="$2" project_id="$3" group_id="$4" description="$5"
  local tag_list="$6" run_untagged="$7" locked="$8" access_level="$9"
  out=(--data-urlencode "runner_type=$runner_type"
    --data-urlencode "description=$description"
    --data-urlencode "tag_list=$tag_list"
    --data-urlencode "run_untagged=$run_untagged"
    --data-urlencode "locked=$locked"
    --data-urlencode "access_level=$access_level")
  case "$runner_type" in
    project_type)
      [[ "$project_id" =~ ^[1-9][0-9]*$ ]] \
        || { err "GITLAB_RUNNER_PROJECT_ID는 project_type에서 양의 정수여야 합니다."; return 1; }
      out+=(--data-urlencode "project_id=$project_id")
      ;;
    group_type)
      [[ "$group_id" =~ ^[1-9][0-9]*$ ]] \
        || { err "GITLAB_RUNNER_GROUP_ID는 group_type에서 양의 정수여야 합니다."; return 1; }
      out+=(--data-urlencode "group_id=$group_id")
      ;;
    instance_type) ;;
    *) err "GITLAB_RUNNER_TYPE은 project_type, group_type, instance_type 중 하나여야 합니다."; return 1 ;;
  esac
}

# 등록 명령 구성을 함수로 분리해 답변파일 값이 실제 CLI 옵션까지 전달되는지 회귀 테스트한다.
build_runner_registration_args() {
  # clone_url belongs to the TOML template; an extra positional argument must not become a file path.
  (( $# == 11 )) || { err "Runner 등록 인자 개수가 올바르지 않습니다 (템플릿 포함 11개 필요)."; return 1; }
  local -n out="$1"
  local rurl="$2" rtoken="$3" rname="$4" rimage="$5" rnet="$6"
  local rtags="$7" run_untagged="$8" protected="$9" locked="${10}"
  local template_config="${11:-}"
  local access_level="not_protected"
  [[ "$protected" == "true" ]] && access_level="ref_protected"

  out=(register --non-interactive --url "$rurl" --name "$rname"
    --executor docker --docker-image "$rimage"
    --docker-helper-image "$RUNNER_HELPER_IMAGE"
    --docker-pull-policy never
    --docker-volumes /var/run/docker.sock:/var/run/docker.sock
    --docker-volumes /cache)
  [[ -n "$template_config" ]] && out+=(--template-config "$template_config")
  [[ -n "$rnet" ]] && out+=(--docker-network-mode "$rnet")
  if [[ "$rtoken" == glrt-* ]]; then
    # 서버 정책 옵션은 POST /user/runners 또는 GitLab UI에서 이미 정해져야 한다.
    # glrt-*와 아래 legacy 옵션을 함께 넘기면 Runner 15.11+가 등록을 거부한다.
    : # CI_SERVER_TOKEN is passed over stdin by runner_register_secret.
  else
    out+=(--tag-list "$rtags"
      --run-untagged="$run_untagged"
      --locked="$locked"
      --access-level="$access_level")      # 구형 등록 토큰 방식
  fi
}


# Curl reads the secret header from stdin, never from process arguments.
runner_api_curl() {
  local token="$1" escaped; shift
  [[ -n "$token" && "$token" != *$'\n'* && "$token" != *$'\r'* ]] || return 1
  escaped="${token//\\/\\\\}"
  escaped="${escaped//\"/\\\"}"
  printf 'header = "PRIVATE-TOKEN: %s"\n' "$escaped" | curl --config - "$@"
}

runner_register_secret() {
  local token="$1"; shift
  [[ -n "$token" && "$token" != *$'\n'* && "$token" != *$'\r'* ]] || return 1
  # Docker exec API contains only this fixed script and public options.
  # The child reads stdin and uses Runner's supported secret environment variable.
  printf '%s\n' "$token" | docker exec -i "$RUNNER_CONTAINER" sh -c '
    IFS= read -r runner_secret || exit 1
    case "$runner_secret" in
      glrt-*) CI_SERVER_TOKEN="$runner_secret"; export CI_SERVER_TOKEN ;;
      *) REGISTRATION_TOKEN="$runner_secret"; export REGISTRATION_TOKEN ;;
    esac
    unset runner_secret
    exec gitlab-runner "$@"
  ' runner-register "$@"
}
