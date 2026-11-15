#!/usr/bin/env bash
# Nexus UID/GID 200 is fixed by the pinned sonatype/nexus3 image.

nexus_secrets_path() { printf '%s' "${NEXUS_SECRETS_KEY_FILE:-/etc/deployops/nexus-secrets/secrets.json}"; }

nexus_secrets_generate_json() {
  local py=/usr/libexec/platform-python
  [[ -x "$py" ]] || py="$(command -v python3)" || { err "Nexus 초기 설정에 Python 3가 필요합니다."; return 1; }
  "$py" -I -B - <<'PY'
import base64, json, os, sys
identity = os.urandom(16).hex()
material = base64.b64encode(os.urandom(32)).decode('ascii')
json.dump({'active': identity, 'keys': [{'id': identity, 'key': material}]}, sys.stdout)
sys.stdout.write('\n')
PY
}

nexus_secrets_validate_path() {
  local file parent canonical installer_root bundle_root
  file="$(nexus_secrets_path)"; parent="${file%/*}"
  [[ "$file" =~ ^/[A-Za-z0-9_./-]+$ && "$parent" != / && "$file" != */ ]] \
    || { err "NEXUS_SECRETS_KEY_FILE: 전용 디렉터리 아래 절대 경로가 필요합니다."; return 1; }
  canonical="$(readlink -m -- "$file")" || return 1
  [[ "$canonical" == "$file" ]] \
    || { err "Nexus 키 경로에 symlink, .. 또는 중복 구분자를 사용할 수 없습니다."; return 1; }
  installer_root="$(readlink -m -- "$SCRIPT_DIR")" || return 1
  bundle_root="$(readlink -m -- "${BUNDLE_ROOT:-$SCRIPT_DIR/bundles}")" || return 1
  case "$file" in "$installer_root"/*|"$bundle_root"/*|/nexus-data/*)
    err "Nexus 키는 설치기·번들·nexus-data 외부에 보관해야 합니다."; return 1 ;;
  esac
}

nexus_secrets_validate_file() (
  set +x
  local file parent jq_bin
  file="$(nexus_secrets_path)"; parent="${file%/*}"
  nexus_secrets_validate_path || return 1
  [[ -f "$file" && ! -L "$file" && "$(stat -c '%u:%g:%a' "$file")" == 0:200:440 \
    && -d "$parent" && ! -L "$parent" && "$(stat -c '%u:%g:%a' "$parent")" == 0:200:750 ]] \
    || { err "Nexus 키 권한 불일치: 전용 디렉터리 root:200 0750, 파일 root:200 0440이 필요합니다."; return 1; }
  jq_bin="$(docker_jq_binary)" || return 1
  "$jq_bin" -e '
    type == "object" and (.active | type == "string" and length > 0)
    and (.keys | type == "array" and length > 0)
    and all(.keys[]; (.id | type == "string" and length > 0)
      and (.key | type == "string" and length >= 32))
    and ([.keys[].id] | length == (unique | length))
    and (.active as $active | any(.keys[]; .id == $active))
  ' "$file" >/dev/null 2>&1 \
    || { err "Nexus 키 JSON의 active/keys 구조가 올바르지 않습니다."; return 1; }
)

# Never mount/start an image to inspect existing data. The installer targets a local Docker daemon.
nexus_secrets_volume_path() {
  local names metadata mountpoint driver
  names="$(docker volume ls --format '{{.Name}}')" || return 1
  if ! printf '%s\n' "$names" | grep -Fxq -- "$NEXUS_VOLUME"; then return 0; fi
  metadata="$(docker volume inspect --format '{{.Driver}}|{{.Mountpoint}}' "$NEXUS_VOLUME")" || return 1
  driver="${metadata%%|*}"; mountpoint="${metadata#*|}"
  [[ "$driver" == local && "$mountpoint" == /* && -d "$mountpoint" && ! -L "$mountpoint" \
    && "$(readlink -f -- "$mountpoint")" == "$mountpoint" ]] \
    || { err "Nexus 키 검증은 접근 가능한 local Docker volume만 지원합니다."; return 1; }
  printf '%s' "$mountpoint"
}

nexus_secrets_binding_valid() {
  local volume_path="$1" file marker digest
  file="$(nexus_secrets_path)"; marker="$volume_path/.deployops-nexus-key.sha256"
  [[ -f "$marker" && ! -L "$marker" ]] || return 1
  digest="$(sha256sum -- "$file")" || return 1
  [[ "$(cat -- "$marker")" == "${digest%% *}" ]]
}

nexus_secrets_existing_container_contract() {
  local names mounts jq_bin volume_path
  names="$(docker container ls -a --format '{{.Names}}')" || return 1
  if ! printf '%s\n' "$names" | grep -Fxq -- "$NEXUS_CONTAINER"; then return 0; fi
  jq_bin="$(docker_jq_binary)" || return 1
  mounts="$(docker inspect --format '{{json .Mounts}}' "$NEXUS_CONTAINER")" || return 1
  printf '%s' "$mounts" | "$jq_bin" -e --arg volume "$NEXUS_VOLUME" '
    [.[] | select(.Destination == "/nexus-data")] as $data
    | ($data | length) == 1 and $data[0].Type == "volume"
      and $data[0].Name == $volume and $data[0].Driver == "local"
  ' >/dev/null 2>&1 || {
    err "기존 Nexus 컨테이너의 /nexus-data가 지정한 local volume과 다릅니다. 키 생성과 컨테이너 교체를 중단합니다."
    return 1
  }
  volume_path="$(nexus_secrets_volume_path)" || return 1
  [[ -n "$volume_path" ]] || { err "기존 Nexus 컨테이너의 데이터 volume을 확인할 수 없습니다."; return 1; }
}

nexus_secrets_prepare() (
  set +x
  local file parent volume_path entry tmp_key='' digest marker_tmp=''
  [[ "$EUID" == 0 ]] || { err "Nexus 키 생성/검증은 root 권한이 필요합니다."; return 1; }
  nexus_secrets_validate_path || return 1
  nexus_secrets_existing_container_contract || return 1
  file="$(nexus_secrets_path)"; parent="${file%/*}"
  volume_path="$(nexus_secrets_volume_path)" || return 1
  if [[ -n "$volume_path" ]]; then
    case "$file" in "$volume_path"/*) err "Nexus 키를 데이터 volume 안에 둘 수 없습니다."; return 1 ;; esac
    entry="$(find "$volume_path" -mindepth 1 -maxdepth 1 -print -quit)" || return 1
    if [[ -n "$entry" ]]; then
      if ! nexus_secrets_validate_file || ! nexus_secrets_binding_valid "$volume_path"; then
        err "기존 Nexus 데이터의 키가 없거나 일치하지 않습니다. 설치 중단: docs/03-gitlab-runner-nexus.md의 기존 volume 재암호화/복구 절차를 먼저 수행하세요."
        return 1
      fi
      return 0
    fi
  fi
  if [[ ! -e "$file" && ! -L "$file" ]]; then
    if [[ -e "$parent" ]]; then
      [[ -d "$parent" && "$(stat -c '%u:%g:%a' "$parent")" == 0:200:750 ]] \
        || { err "기존 키 디렉터리 권한을 임의 변경하지 않습니다. root:200 0750 전용 디렉터리를 지정하세요."; return 1; }
    else
      install -d -o 0 -g 200 -m 0750 -- "$parent" || return 1
    fi
    tmp_key="$(mktemp "$parent/.secrets.XXXXXXXX")" || return 1
    trap '[[ -z "$tmp_key" ]] || rm -f -- "$tmp_key"; [[ -z "$marker_tmp" ]] || rm -f -- "$marker_tmp"' EXIT
    nexus_secrets_generate_json > "$tmp_key" || return 1
    chown 0:200 "$tmp_key" && chmod 0440 "$tmp_key" || return 1
    # A hard link atomically publishes without replacing an existing key.
    ln -- "$tmp_key" "$file" || { err "Nexus 키가 이미 생성되었거나 생성할 수 없습니다. 기존 키를 보존합니다."; return 1; }
    rm -f -- "$tmp_key"; tmp_key=''
  fi
  nexus_secrets_validate_file || return 1
  if [[ -z "$volume_path" ]]; then
    docker volume create "$NEXUS_VOLUME" >/dev/null || return 1
    volume_path="$(nexus_secrets_volume_path)" || return 1
    [[ -n "$volume_path" ]] || return 1
  fi
  # Recheck after creation: concurrent or unexpectedly populated data must not get a new binding.
  entry="$(find "$volume_path" -mindepth 1 -maxdepth 1 -print -quit)" || return 1
  [[ -z "$entry" ]] || { err "Nexus volume이 비어 있지 않습니다. 키 연결을 중단합니다."; return 1; }
  # The marker makes the volume nonempty, so Docker will not copy the image directory ownership.
  chown 200:200 "$volume_path" && chmod 0755 "$volume_path" || return 1
  digest="$(sha256sum -- "$file")" || return 1
  marker_tmp="$(mktemp "$volume_path/.key-binding.XXXXXXXX")" || return 1
  trap '[[ -z "$tmp_key" ]] || rm -f -- "$tmp_key"; [[ -z "$marker_tmp" ]] || rm -f -- "$marker_tmp"' EXIT
  printf '%s\n' "${digest%% *}" > "$marker_tmp" || return 1
  chown 0:0 "$marker_tmp" && chmod 0400 "$marker_tmp" || return 1
  ln -- "$marker_tmp" "$volume_path/.deployops-nexus-key.sha256" || return 1
  rm -f -- "$marker_tmp"; marker_tmp=''
  info "Nexus 전용 암호화 키 연결 완료. 키 파일은 데이터와 함께 별도 보안 백업하세요."
)

nexus_secrets_runtime_contract() {
  local file volume_path mounts jq_bin
  file="$(nexus_secrets_path)"
  nexus_secrets_validate_file || return 1
  volume_path="$(nexus_secrets_volume_path)" || return 1
  [[ -n "$volume_path" ]] && nexus_secrets_binding_valid "$volume_path" || return 1
  [[ "$(container_environment_value "$NEXUS_CONTAINER" NEXUS_SECRETS_KEY_FILE)" == /run/nexus-secrets/secrets.json ]] || return 1
  jq_bin="$(docker_jq_binary)" || return 1
  mounts="$(docker inspect --format '{{json .Mounts}}' "$NEXUS_CONTAINER")" || return 1
  printf '%s' "$mounts" | "$jq_bin" -e --arg source "$file" --arg volume "$NEXUS_VOLUME" '
    any(.[]; .Type == "bind" and .Source == $source
      and .Destination == "/run/nexus-secrets/secrets.json" and .RW == false)
    and ([.[] | select(.Destination == "/nexus-data")] as $data
      | ($data | length) == 1 and $data[0].Type == "volume"
        and $data[0].Name == $volume and $data[0].Driver == "local")
  ' >/dev/null 2>&1
}

# A mounted file alone does not prove that Nexus loaded its custom encryption key.
nexus_secrets_health_contract() (
  set +x
  local base="$1" jq_bin="$2" pass escaped task_dir
  pass="$(nexus_admin_password)" || return 1
  [[ -n "$pass" && "$pass" != *$'\n'* && "$pass" != *$'\r'* ]] || return 1
  task_dir="$(mktemp -d)" || return 1
  trap 'rm -rf "$task_dir"' EXIT
  chmod 0700 "$task_dir" || return 1
  escaped="${pass//\\/\\\\}"; escaped="${escaped//\"/\\\"}"
  (umask 077; printf 'user = "admin:%s"\n' "$escaped" > "$task_dir/auth") || return 1
  curl --config "$task_dir/auth" -fsS --max-time 10 -o "$task_dir/status.json" \
    "$base/service/rest/v1/status/check" || return 1
  "$jq_bin" -e 'type == "object"
    and (."Default Secret Encryption Key" | type == "object" and .healthy == true)
    and (."Re-encryption required" | type == "object" and .healthy == true)
  ' "$task_dir/status.json" >/dev/null 2>&1 || {
    err "Nexus의 전용 암호화 키 적용 및 기존 비밀정보 재암호화 완료를 확인할 수 없습니다(status/check)."
    return 1
  }
)
