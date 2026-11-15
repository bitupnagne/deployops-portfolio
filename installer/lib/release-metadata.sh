#!/usr/bin/env bash
# Release BOM/LOCK loader. Bundle metadata is data, never executable shell.

release_metadata_error() { printf '[release-metadata][ERROR] %s\n' "$*" >&2; }

# Check raw JSON before jq normalization can discard duplicate properties.
release_json_unique() {
  "$2" -n --stream -e 'reduce inputs as $e ({leaves:{},closed:{},ok:true};
    if ($e|length)==1 then .closed[($e[0][0:-1]|tojson)] = true
    else ($e[0]) as $p | .closed as $closed | .ok = (.ok and (.leaves[($p|tojson)]!=true) and
      all(range(0;($p|length)+1); . as $n | $closed[($p[0:$n]|tojson)]!=true)) |
      .leaves[($p|tojson)] = true end) | .ok' "$1" >/dev/null
}

load_release_env() {
  local file="$1" kind="$2" allowed="" line key value allowed_key
  local seen='|' lineno=0
  case "$kind" in
    bom)
      allowed='BOM_SCHEMA_VERSION BOM_RELEASE BOM_GITLAB_IMAGE BOM_RUNNER_IMAGE BOM_RUNNER_HELPER_IMAGE BOM_RUNNER_BUILD_IMAGE BOM_NEXUS_IMAGE BOM_JAVA_VERSION BOM_JAVA_ARCHIVE BOM_JAVA_SHA256 BOM_JQ_VERSION BOM_JQ_SHA256 BOM_DEPLOYOPS_JAR BOM_DEPLOYOPS_VERSION BOM_DEPLOYOPS_REQUIRED_MIGRATIONS BOM_WORKSYSTEM_JAR BOM_WORKSYSTEM_VERSION'
      ;;
    lock)
      allowed='LOCK_SCHEMA_VERSION LOCK_BOM_RELEASE LOCK_GITLAB_IMAGE LOCK_GITLAB_DIGEST LOCK_GITLAB_PINNED_REF LOCK_GITLAB_PLATFORM_DIGEST LOCK_GITLAB_CONFIG_DIGEST LOCK_GITLAB_ARCHIVE_ID LOCK_RUNNER_IMAGE LOCK_RUNNER_DIGEST LOCK_RUNNER_PINNED_REF LOCK_RUNNER_PLATFORM_DIGEST LOCK_RUNNER_CONFIG_DIGEST LOCK_RUNNER_ARCHIVE_ID LOCK_RUNNER_HELPER_IMAGE LOCK_RUNNER_HELPER_DIGEST LOCK_RUNNER_HELPER_PINNED_REF LOCK_RUNNER_HELPER_PLATFORM_DIGEST LOCK_RUNNER_HELPER_CONFIG_DIGEST LOCK_RUNNER_HELPER_ARCHIVE_ID LOCK_RUNNER_BUILD_IMAGE LOCK_RUNNER_BUILD_DIGEST LOCK_RUNNER_BUILD_PINNED_REF LOCK_RUNNER_BUILD_PLATFORM_DIGEST LOCK_RUNNER_BUILD_CONFIG_DIGEST LOCK_RUNNER_BUILD_ARCHIVE_ID LOCK_NEXUS_IMAGE LOCK_NEXUS_DIGEST LOCK_NEXUS_PINNED_REF LOCK_NEXUS_PLATFORM_DIGEST LOCK_NEXUS_CONFIG_DIGEST LOCK_NEXUS_ARCHIVE_ID LOCK_DEPLOYOPS_JAR LOCK_DEPLOYOPS_VERSION LOCK_DEPLOYOPS_REQUIRED_MIGRATIONS LOCK_DEPLOYOPS_SHA256 LOCK_WORKSYSTEM_JAR LOCK_WORKSYSTEM_VERSION LOCK_WORKSYSTEM_SHA256'
      ;;
    *) release_metadata_error "unknown metadata kind: $kind"; return 1 ;;
  esac

  [[ -r "$file" ]] || { release_metadata_error "metadata file not readable: $file"; return 1; }
  local prefix="${kind^^}" flag included
  flag="${prefix}_WORKSYSTEM_INCLUDED"
  allowed="$allowed $flag"
  for allowed_key in $allowed; do unset "$allowed_key"; done
  while IFS= read -r line || [[ -n "$line" ]]; do
    lineno=$((lineno + 1))
    line="${line%$'\r'}"
    case "$line" in ''|'#'*) continue ;; esac
    if [[ ! "$line" =~ ^([A-Z][A-Z0-9_]*)=([A-Za-z0-9._:/@,+-]+)$ ]]; then
      release_metadata_error "$file:$lineno: strict KEY=VALUE 형식이 아닙니다."
      return 1
    fi
    key="${BASH_REMATCH[1]}"; value="${BASH_REMATCH[2]}"
    case " $allowed " in
      *" $key "*) ;;
      *) release_metadata_error "$file:$lineno: 허용되지 않은 키입니다: $key"; return 1 ;;
    esac
    case "$seen" in
      *"|$key|"*) release_metadata_error "$file:$lineno: 중복 키입니다: $key"; return 1 ;;
    esac
    seen+="$key|"
    printf -v "$key" '%s' "$value"
  done < "$file"

  # Historical integrated media omitted the flag. New media must record it explicitly.
  included="${!flag:-true}"
  [[ "$included" == true || "$included" == false ]] \
    || { release_metadata_error "invalid component inclusion flag: $flag"; return 1; }
  printf -v "$flag" '%s' "$included"
  for allowed_key in $allowed; do
    [[ "$allowed_key" == "$flag" ]] && continue
    if [[ "$allowed_key" == "${prefix}_WORKSYSTEM_"* && "$included" == false ]]; then
      [[ "$seen" != *"|$allowed_key|"* ]] \
        || { release_metadata_error "excluded component metadata must be absent: $allowed_key"; return 1; }
      continue
    fi
    case "$seen" in
      *"|$allowed_key|"*) ;;
      *) release_metadata_error "$file: 필수 키가 없습니다: $allowed_key"; return 1 ;;
    esac
  done
}

load_release_bom() {
  local file="$1" image
  load_release_env "$file" bom || return 1
  [[ "$BOM_SCHEMA_VERSION" == "1" ]] \
    || { release_metadata_error "지원하지 않는 BOM schema: $BOM_SCHEMA_VERSION"; return 1; }
  [[ "$BOM_RELEASE" =~ ^[0-9]{4}\.[0-9]{2}([._-][A-Za-z0-9.-]+)?$ ]] \
    || { release_metadata_error "올바르지 않은 BOM release: $BOM_RELEASE"; return 1; }
  for image in "$BOM_GITLAB_IMAGE" "$BOM_RUNNER_IMAGE" "$BOM_RUNNER_HELPER_IMAGE" \
      "$BOM_RUNNER_BUILD_IMAGE" "$BOM_NEXUS_IMAGE"; do
    [[ "$image" =~ ^[A-Za-z0-9][A-Za-z0-9._/-]*:[A-Za-z0-9][A-Za-z0-9._-]*$ ]] \
      || { release_metadata_error "올바르지 않은 container image tag: $image"; return 1; }
  done
  [[ "$BOM_DEPLOYOPS_JAR" == "common/deploy-system/deploy-ops.jar" ]] \
    || { release_metadata_error "올바르지 않은 DeployOps JAR 경로: $BOM_DEPLOYOPS_JAR"; return 1; }
  [[ "$BOM_DEPLOYOPS_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([._+-][A-Za-z0-9.-]+)?$ ]] \
    || { release_metadata_error "올바르지 않은 DeployOps 버전: $BOM_DEPLOYOPS_VERSION"; return 1; }
  [[ "$BOM_DEPLOYOPS_REQUIRED_MIGRATIONS" =~ ^V[0-9]+__[A-Za-z0-9_]+\.sql(,V[0-9]+__[A-Za-z0-9_]+\.sql)*$ ]] \
    || { release_metadata_error "올바르지 않은 DeployOps migration 목록"; return 1; }
  [[ ",$BOM_DEPLOYOPS_REQUIRED_MIGRATIONS," == *,V22__merge_request_reviewer_gitlab_identity.sql,* ]] \
    || { release_metadata_error "DeployOps BOM에 필수 승인자 신원 migration V22가 없습니다."; return 1; }
  [[ ",$BOM_DEPLOYOPS_REQUIRED_MIGRATIONS," == *,V26__audit_target_description_capacity.sql,* ]] \
    || { release_metadata_error "DeployOps BOM에 필수 감사 설명 용량 migration V26이 없습니다."; return 1; }
  if [[ "$BOM_WORKSYSTEM_INCLUDED" == true ]]; then
  [[ "$BOM_WORKSYSTEM_JAR" == "common/worksystem-server/worksystem.jar" ]] \
    || { release_metadata_error "올바르지 않은 Worksystem JAR 경로: $BOM_WORKSYSTEM_JAR"; return 1; }
  [[ "$BOM_WORKSYSTEM_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([._+-][A-Za-z0-9.-]+)?$ ]] \
    || { release_metadata_error "올바르지 않은 Worksystem 버전: $BOM_WORKSYSTEM_VERSION"; return 1; }
  fi
  [[ "$BOM_JAVA_VERSION" =~ ^21\.[0-9]+\.[0-9]+(\.[0-9]+)?\+[0-9]+$ ]] \
    || { release_metadata_error "지원하지 않는 Java release: $BOM_JAVA_VERSION"; return 1; }
  [[ "$BOM_JAVA_ARCHIVE" =~ ^OpenJDK21U-jdk_x64_linux_hotspot_[A-Za-z0-9._+-]+\.tar\.gz$ ]] \
    || { release_metadata_error "올바르지 않은 Java archive 이름: $BOM_JAVA_ARCHIVE"; return 1; }
  [[ "$BOM_JAVA_SHA256" =~ ^[0-9a-f]{64}$ ]] \
    || { release_metadata_error "올바르지 않은 Java SHA-256"; return 1; }
  [[ "$BOM_JQ_VERSION" =~ ^[0-9]+\.[0-9]+(\.[0-9]+)?$ && "$BOM_JQ_SHA256" =~ ^[0-9a-f]{64}$ ]] \
    || { release_metadata_error "올바르지 않은 jq release metadata"; return 1; }
}

load_release_lock() {
  local file="$1"
  load_release_env "$file" lock || return 1
  [[ -z "${BOM_WORKSYSTEM_INCLUDED:-}" || "$LOCK_WORKSYSTEM_INCLUDED" == "$BOM_WORKSYSTEM_INCLUDED" ]] \
    || { release_metadata_error "Worksystem inclusion lock does not match BOM"; return 1; }
  [[ "$LOCK_SCHEMA_VERSION" == "2" ]] \
    || { release_metadata_error "지원하지 않는 LOCK schema: $LOCK_SCHEMA_VERSION"; return 1; }
}

release_image_repository() {
  local image="$1"
  printf '%s' "${image%:*}"
}

release_pinned_ref() {
  local image="$1" digest="$2"
  printf '%s@%s' "$(release_image_repository "$image")" "$digest"
}

release_image_id_valid() {
  [[ "$1" =~ ^sha256:[0-9a-f]{64}$ ]]
}

# containerd 저장소의 기본 .Id는 index일 수 있으므로 플랫폼 ID를 조회한다.
# 구형 CLI만 기본 inspect를 사용하며, 플랫폼 조회 실패를 기본 ID로 우회하지 않는다.
docker_platform_image_id() {
  local image="$1" inspected os arch id
  local options=()
  if docker image inspect --help 2>/dev/null | grep -q -- '--platform'; then
    options=(--platform linux/amd64)
  fi
  inspected="$(docker image inspect "${options[@]}" --format '{{.Os}}|{{.Architecture}}|{{.Id}}' "$image" 2>/dev/null)" \
    || return 1
  IFS='|' read -r os arch id <<< "$inspected"
  [[ "$os" == linux && "$arch" == amd64 ]] || return 1
  release_image_id_valid "$id" || return 1
  printf '%s' "$id"
}

docker_image_matches_config() {
  local image="$1" config_id="$2" platform_digest="${3:-}" actual
  release_image_id_valid "$config_id" || return 1
  actual="$(docker_platform_image_id "$image")" || return 1
  [[ "$actual" == "$config_id" ]] && return 0
  # 이 digest는 검증된 OCI platform -> config 증적에서만 전달한다.
  release_image_id_valid "$platform_digest" || return 1
  [[ "$actual" == "$platform_digest" ]]
}

image_archive_manifest() {
  local archive="$1" read_status
  case "$archive" in
    *.tar.gz|*.tgz) tar -xOzf "$archive" manifest.json ;;
    *.tar)          tar -xOf  "$archive" manifest.json ;;
    *)              return 1 ;;
  esac || {
    read_status=$?
    release_metadata_error "archive manifest read failed (exit $read_status): $archive"
    return "$read_status"
  }
}

image_archive_member_sha256() {
  local archive="$1" member="$2"
  case "$archive" in
    *.tar.gz|*.tgz) tar -xOzf "$archive" "$member" | sha256sum | awk '{print $1}' ;;
    *.tar)          tar -xOf  "$archive" "$member" | sha256sum | awk '{print $1}' ;;
    *)              return 1 ;;
  esac
}

image_archive_layer_diff_sha256() {
  local archive="$1" member="$2"
  # containerd docker-save는 gzip OCI blob, classic은 비압축 layer.tar를 저장한다.
  # GNU gzip -dcf는 비압축 입력을 그대로 전달하고 손상된 gzip은 실패한다.
  (
    set -o pipefail
    case "$archive" in
      *.tar.gz|*.tgz) tar -xOzf "$archive" "$member" ;;
      *.tar) tar -xOf "$archive" "$member" ;;
      *) return 1 ;;
    esac | gzip -dcf | sha256sum | awk '{print $1}'
  )
}

# docker-save manifest의 tag -> config 객체를 찾아 실제 config bytes의 SHA와 파일명이
# 일치하는지 확인한다. 반환값은 Docker image ID와 같은 sha256:<config digest> 형식이다.
image_archive_id_for_tag() (
  local archive="$1" tag="$2" jq_bin="$3" manifest config expected actual layers_json diff_ids_json
  local layer diff_id layer_sha index layer_count diff_count temp config_file layer_file member blob_hash blob_size
  local path type_line archive_identity archive_identity_after
  export IMAGE_ARCHIVE_HELPER="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/image-archive-member.sh"
  [[ -f "$IMAGE_ARCHIVE_HELPER" ]] || return 1
  temp="$(mktemp -d)" || return 1
  trap 'rm -rf -- "$temp"' EXIT
  chmod 0700 "$temp" || return 1
  mkdir "$temp/files" "$temp/claims" "$temp/digests" "$temp/sizes" || return 1
  export IMAGE_ARCHIVE_TEMP="$temp"
  local tar_options=()
  case "$archive" in *.tar.gz|*.tgz) tar_options=(-z) ;; *.tar) ;; *) return 1 ;; esac
  # The caller must keep this archive immutable during validation; an offline
  # bundle caller authenticates it before this function. Compare filesystem
  # identity/timestamps after separate tar passes to detect replacement or
  # ordinary in-place mutation without copying a multi-GB archive to scratch.
  archive_identity="$(stat -Lc '%d:%i:%s:%y:%z' -- "$archive")" \
    || { release_metadata_error "archive identity read failed: $archive"; return 1; }
  printf '%s\n' manifest.json > "$temp/members"
  tar -xv "${tar_options[@]}" -f "$archive" --no-recursion --quoting-style=literal \
    --to-command='bash "$IMAGE_ARCHIVE_HELPER"' -- manifest.json >"$temp/listed" \
    || { release_metadata_error "archive manifest extraction failed: $archive (tag: $tag)"; return 1; }
  cmp -s "$temp/members" "$temp/listed" || return 1
  [[ -f "$temp/files/manifest.json" && ! -L "$temp/files/manifest.json" ]] || return 1
  release_json_unique "$temp/files/manifest.json" "$jq_bin" || return 1
  manifest="$("$jq_bin" -sce 'if length == 1 and (.[0] | type) == "array" then .[0] else error("one manifest required") end' "$temp/files/manifest.json")" || return 1
  config="$(printf '%s' "$manifest" | "$jq_bin" -er --arg tag "$tag" \
    '[.[] | select((.RepoTags // []) | index($tag)) | .Config] | unique | if length == 1 then .[0] else error("tag mapping must be unique") end' 2>/dev/null)" \
    || return 1
  case "$config" in
    [0-9a-f][0-9a-f]*.json)
      [[ "$config" =~ ^([0-9a-f]{64})\.json$ ]] || return 1
      expected="${BASH_REMATCH[1]}"
      ;;
    blobs/sha256/[0-9a-f]*)
      [[ "$config" =~ ^blobs/sha256/([0-9a-f]{64})$ ]] || return 1
      expected="${BASH_REMATCH[1]}"
      ;;
    *) return 1 ;;
  esac
  layers_json="$(printf '%s' "$manifest" | "$jq_bin" -cer --arg tag "$tag" \
    '[.[] | select((.RepoTags // []) | index($tag)) | (.Layers // [])] | unique | if length == 1 then .[0] else error("tag layers must be unique") end' 2>/dev/null)" \
    || return 1

  # `--to-command` runs only for regular files, so inspect the complete tar
  # header listing first. Align verbose type records with names; this catches
  # unreferenced links, special files, duplicate paths and invalid paths too.
  tar -t "${tar_options[@]}" -f "$archive" --quoting-style=literal > "$temp/all-members" \
    || { release_metadata_error "archive member listing failed: $archive"; return 1; }
  tar -tv "${tar_options[@]}" -f "$archive" --numeric-owner --full-time --quoting-style=literal > "$temp/all-types" \
    || { release_metadata_error "archive member type listing failed: $archive"; return 1; }
  exec 3< "$temp/all-types"
  while IFS= read -r path; do
    IFS= read -r type_line <&3 || { exec 3<&-; release_metadata_error "archive member/type count differs: $archive"; return 1; }
    case "${type_line:0:1}" in
      -)
        [[ "$path" == manifest.json || "$path" == repositories || "$path" == index.json || "$path" == oci-layout || \
          "$path" =~ ^[0-9a-f]{64}\.json$ || "$path" =~ ^[0-9a-f]{64}/layer\.tar$ || \
          "$path" =~ ^blobs/sha256/[0-9a-f]{64}$ ]] || {
            exec 3<&-; release_metadata_error "archive contains a disallowed regular-file path: $archive (member: $path)"; return 1;
          }
        ;;
      d)
        [[ "$path" == blobs/ || "$path" == blobs/sha256/ || "$path" =~ ^[0-9a-f]{64}/$ ]] || {
          exec 3<&-; release_metadata_error "archive contains a disallowed directory path: $archive (member: $path)"; return 1;
        }
        ;;
      *) exec 3<&-; release_metadata_error "archive contains a non-regular member: $archive (member: $path, type: ${type_line:0:1})"; return 1 ;;
    esac
  done < "$temp/all-members"
  if IFS= read -r type_line <&3; then
    exec 3<&-; release_metadata_error "archive member/type count differs: $archive"; return 1
  fi
  exec 3<&-
  LC_ALL=C sort "$temp/all-members" > "$temp/all-members-sorted"
  uniq -d "$temp/all-members-sorted" > "$temp/duplicate-members"
  if [[ -s "$temp/duplicate-members" ]]; then
    release_metadata_error "archive contains duplicate member paths: $archive"; return 1
  fi

  # Docker manifest members remain authoritative for the requested linux/amd64
  # image. OCI archives may also contain opaque blobs and metadata not listed
  # in that legacy manifest; accept only content-addressed blobs and hash each
  # one below. Do not recursively require nested non-selected platform indexes.
  printf '%s\n' manifest.json > "$temp/expected-files"
  while IFS= read -r path; do
    [[ "$path" =~ ^([0-9a-f]{64}\.json|[0-9a-f]{64}/layer\.tar|blobs/sha256/[0-9a-f]{64})$ ]] || {
      release_metadata_error "manifest contains a disallowed payload path: $archive (member: $path)"; return 1;
    }
    printf '%s\n' "$path" >> "$temp/expected-files"
  done < <(printf '%s' "$manifest" | "$jq_bin" -er \
    'if type == "array" and length > 0 and all(.[]; type == "object" and (.Config | type == "string") and ((.Layers // []) | type == "array") and all((.Layers // [])[]; type == "string")) then [.[] | .Config, (.Layers // [])[]] | unique[] else error("invalid image manifest") end')
  if grep -Fxq -- index.json "$temp/all-members" || grep -Fxq -- oci-layout "$temp/all-members"; then
    grep -Fxq -- index.json "$temp/all-members" && grep -Fxq -- oci-layout "$temp/all-members" \
      || { release_metadata_error "OCI index/layout metadata must be present as a pair: $archive"; return 1; }
  fi
  for path in repositories index.json oci-layout; do
    if grep -Fxq -- "$path" "$temp/all-members"; then
      printf '%s\n' "$path" >> "$temp/expected-files"
    fi
  done
  grep -E '^blobs/sha256/[0-9a-f]{64}$' "$temp/all-members" >> "$temp/expected-files" || true
  LC_ALL=C sort -u "$temp/expected-files" > "$temp/expected-files-sorted"
  cp "$temp/expected-files-sorted" "$temp/expected-all"
  while IFS= read -r path; do
    [[ "${path%/}" != "$path" ]] || continue
    path="${path%/}"
    # An explicit directory header is valid only when it is an ancestor of a
    # declared file path. Its presence is optional, as tar writers vary.
    local has_child=0 candidate
    while IFS= read -r candidate; do
      [[ "$candidate" == "$path/"* ]] && { has_child=1; break; }
    done < "$temp/expected-files-sorted"
    (( has_child == 1 )) || { release_metadata_error "archive contains an unrelated directory: $archive (member: $path/)"; return 1; }
    printf '%s/\n' "$path" >> "$temp/expected-all"
  done < "$temp/all-members"
  LC_ALL=C sort -u "$temp/expected-all" > "$temp/expected-all-sorted"
  cmp -s "$temp/expected-all-sorted" "$temp/all-members-sorted" || {
    release_metadata_error "archive member set does not match manifest: $archive"
    diff -u "$temp/expected-all-sorted" "$temp/all-members-sorted" >&2 || true
    return 1
  }

  printf '%s\n' "$config" > "$temp/config-member"
  grep -E '^blobs/sha256/[0-9a-f]{64}$' "$temp/all-members" > "$temp/hash-blobs" || true
  cp "$temp/hash-blobs" "$temp/members"
  printf '%s\n' "$config" >> "$temp/members"
  for path in repositories index.json oci-layout; do
    grep -Fxq -- "$path" "$temp/all-members" && printf '%s\n' "$path" >> "$temp/members"
  done
  : > "$temp/layers"
  while IFS= read -r layer; do
    [[ "$layer" =~ ^([0-9a-f]{64}/layer\.tar|blobs/sha256/[0-9a-f]{64})$ ]] || return 1
    printf '%s\n' "$layer" >> "$temp/members"
    printf '%s\n' "$layer" >> "$temp/layers"
  done < <(printf '%s' "$layers_json" | "$jq_bin" -er '.[]')
  LC_ALL=C sort -u "$temp/members" > "$temp/selected"
  tar -xv "${tar_options[@]}" -f "$archive" --no-recursion --verbatim-files-from --quoting-style=literal \
    --to-command='bash "$IMAGE_ARCHIVE_HELPER"' -T "$temp/selected" >"$temp/listed" \
    || { release_metadata_error "archive config/layer extraction failed: $archive (tag: $tag)"; return 1; }
  LC_ALL=C sort "$temp/listed" > "$temp/listed-sorted"
  cmp -s "$temp/selected" "$temp/listed-sorted" || return 1
  while IFS= read -r member; do
    if grep -Fxq -- "$member" "$temp/layers"; then
      [[ -f "$temp/digests/${member//\//_}.raw" && -f "$temp/digests/${member//\//_}.diff" ]] || return 1
    elif [[ "$member" == "$config" ]]; then
      [[ -f "$temp/files/${member//\//_}" && ! -L "$temp/files/${member//\//_}" ]] || return 1
      if [[ "$member" == blobs/sha256/* ]]; then
        blob_hash="$(sha256sum "$temp/files/${member//\//_}" | awk '{print $1}')" || return 1
        blob_size="$(stat -c %s -- "$temp/files/${member//\//_}")" || return 1
        [[ "$blob_hash" == "${member##*/}" ]] \
          || { release_metadata_error "archive config SHA-256 mismatch: $archive (member: $member)"; return 1; }
        [[ "$blob_size" == "$(<"$temp/sizes/${member//\//_}")" ]] || return 1
      fi
    elif [[ "$member" == blobs/sha256/* ]]; then
      blob_hash="$(<"$temp/digests/${member//\//_}.raw")"
      blob_size="$(<"$temp/sizes/${member//\//_}")"
      [[ "$blob_hash" == "${member##*/}" && "$blob_size" =~ ^[0-9]+$ ]] \
        || { release_metadata_error "archive OCI blob SHA-256 mismatch: $archive (member: $member)"; return 1; }
    else
      [[ -f "$temp/files/${member//\//_}" && ! -L "$temp/files/${member//\//_}" ]] || return 1
    fi
  done < "$temp/selected"
  if grep -Fxq -- index.json "$temp/all-members"; then
    local layout_version descriptor_digest descriptor_size descriptor_path actual_size
    release_json_unique "$temp/files/oci-layout" "$jq_bin" || return 1
    layout_version="$("$jq_bin" -er 'select(type == "object" and .imageLayoutVersion == "1.0.0") | .imageLayoutVersion' "$temp/files/oci-layout")" \
      || { release_metadata_error "invalid OCI layout metadata: $archive"; return 1; }
    release_json_unique "$temp/files/index.json" "$jq_bin" || return 1
    "$jq_bin" -e 'type == "object" and .schemaVersion == 2 and .mediaType == "application/vnd.oci.image.index.v1+json" and (.manifests | type == "array") and all(.manifests[]; type == "object" and (.mediaType | type == "string") and (.digest | type == "string" and test("^sha256:[0-9a-f]{64}$")) and (.size | type == "number" and floor == . and . >= 0 and . <= 9007199254740991))' \
      "$temp/files/index.json" >/dev/null \
      || { release_metadata_error "invalid OCI index metadata: $archive"; return 1; }
    while IFS=$'\t' read -r descriptor_digest descriptor_size; do
      [[ "$descriptor_digest" =~ ^sha256:([0-9a-f]{64})$ ]] || return 1
      descriptor_path="blobs/sha256/${BASH_REMATCH[1]}"
      grep -Fxq -- "$descriptor_path" "$temp/all-members" \
        || { release_metadata_error "OCI index descriptor target missing: $archive (member: $descriptor_path)"; return 1; }
      actual_size="$(<"$temp/sizes/${descriptor_path//\//_}")"
      [[ "$descriptor_size" == "$actual_size" ]] \
        || { release_metadata_error "OCI index descriptor size mismatch: $archive (member: $descriptor_path)"; return 1; }
    done < <("$jq_bin" -r '.manifests[] | [.digest, (.size | tostring)] | @tsv' "$temp/files/index.json")
    [[ -n "$layout_version" ]] || return 1
  fi
  config_file="$temp/files/${config//\//_}"
  release_json_unique "$config_file" "$jq_bin" || return 1
  actual="$(sha256sum "$config_file" | awk '{print $1}')" \
    || { release_metadata_error "archive config read failed: $archive (member: $config)"; return 1; }
  [[ "$actual" == "$expected" ]] \
    || { release_metadata_error "archive config SHA-256 mismatch: $archive (member: $config)"; return 1; }
  diff_ids_json="$("$jq_bin" -cer 'select(.rootfs.type == "layers") | .rootfs.diff_ids | select(type == "array")' "$config_file")" || return 1
  layer_count="$(printf '%s' "$layers_json" | "$jq_bin" -er 'length')" || return 1
  diff_count="$(printf '%s' "$diff_ids_json" | "$jq_bin" -er 'length')" || return 1
  [[ "$layer_count" == "$diff_count" ]] || return 1
  for (( index=0; index<layer_count; index++ )); do
    layer="$(printf '%s' "$layers_json" | "$jq_bin" -er ".[${index}]")" || return 1
    diff_id="$(printf '%s' "$diff_ids_json" | "$jq_bin" -er ".[${index}] | select(test(\"^sha256:[0-9a-f]{64}$\"))")" \
      || return 1
    [[ "$layer" =~ ^([0-9a-f]{64}/layer\.tar|blobs/sha256/[0-9a-f]{64})$ ]] || return 1
    layer_file="$temp/digests/${layer//\//_}"
    layer_sha="$(<"$layer_file.raw")"
    if [[ "$layer" == blobs/sha256/* ]]; then
      [[ "$layer_sha" == "${layer##*/}" ]] \
        || { release_metadata_error "archive layer blob SHA-256 mismatch: $archive (member: $layer)"; return 1; }
    fi
    layer_sha="$(<"$layer_file.diff")"
    [[ "sha256:$layer_sha" == "$diff_id" ]] \
      || { release_metadata_error "archive layer diffID mismatch: $archive (member: $layer)"; return 1; }
  done
  archive_identity_after="$(stat -Lc '%d:%i:%s:%y:%z' -- "$archive")" \
    || { release_metadata_error "archive identity recheck failed: $archive"; return 1; }
  [[ "$archive_identity_after" == "$archive_identity" ]] \
    || { release_metadata_error "archive changed during validation: $archive"; return 1; }
  printf 'sha256:%s' "$expected"
)
