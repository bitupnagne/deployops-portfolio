#!/usr/bin/env bash
# Signed local-build inputs and archives; no publisher authentication is inferred.
java_ci_error() { printf '[java-ci][ERROR] %s\n' "$*" >&2; }

java_ci_path_safe() {
  [[ "$1" =~ ^[A-Za-z0-9_./%+@~-]+$ && "$1" != /* && "$1" != */ && "$1" != *//* ]] || return 1
  [[ "/$1/" != */../* && "/$1/" != */./* ]]
}

java_ci_root_safe() {
  local cursor="$1"
  [[ "$cursor" == /* && "$cursor" != *'/../'* && "$cursor" != *'/./'* ]] || return 1
  while [[ "$cursor" != / ]]; do
    [[ ! -L "$cursor" ]] || return 1
    cursor="$(dirname -- "$cursor")"
  done
}

java_ci_cache_artifact() {
  local relative="$1" name artifact version part
  local -a parts=()
  IFS=/ read -r -a parts <<< "$relative"
  (( ${#parts[@]} >= 3 )) || return 1
  for part in "${parts[@]}"; do [[ "$part" != .* ]] || return 1; done
  name="${parts[-1]}"
  case "$name" in settings.xml|settings-security.xml|resolver-status.properties|_remote.repositories|*.lastUpdated|*.part|*.tmp|*.lock) return 1 ;; esac
  [[ "$name" =~ ^maven-metadata[^/]*\.xml(\.(sha1|sha256|sha512|md5))?$ ]] && return 0
  (( ${#parts[@]} >= 4 )) || return 1
  artifact="${parts[-3]}"; version="${parts[-2]}"
  [[ "$name" == "$artifact-$version"* && "$name" =~ \.(jar|pom|war|aar|zip|tar\.gz|tar\.bz2|tar\.xz|exe|dll|so)(\.(asc|sha1|sha256|sha512|md5))?$ ]]
}

# Canonical JSON rejects duplicate properties and ambiguous whitespace/encoding.
java_ci_json() (
  local file="$1" jq_bin="$2" temp
  [[ -f "$file" && ! -L "$file" ]] || return 1
  temp="$(mktemp)" || return 1
  trap 'rm -f -- "$temp"' EXIT
  "$jq_bin" -cS . "$file" > "$temp" && cmp -s "$file" "$temp"
)

java_ci_json_unique() {
  release_json_unique "$1" "$2"
}

java_ci_inventory() (
  local root="$1" json="$2" jq_bin="$3" excluded="$4" row path size sha actual count=0
  local -A seen=()
  java_ci_root_safe "$root" || return 1
  [[ -d "$root" && ! -L "$root" ]] || return 1
  [[ -z "$(find "$root" ! -type f ! -type d -print -quit)" ]] || return 1
  "$jq_bin" -e '.files | type == "array" and length > 0 and all(.[];
    keys == ["path","sha256","size"] and (.path|type)=="string" and
    (.sha256|type)=="string" and (.sha256|test("^[0-9a-f]{64}$")) and
    (.size|type)=="number" and .size >= 0 and .size == (.size|floor))' "$json" >/dev/null || return 1
  while IFS=$'\t' read -r path size sha; do
    java_ci_path_safe "$path" && [[ "$path" != "$excluded" && ! -v seen[$path] ]] || return 1
    seen[$path]=1
    [[ -f "$root/$path" && ! -L "$root/$path" ]] || return 1
    [[ "$(stat -c %s "$root/$path")" == "$size" && "$(sha256sum "$root/$path" | cut -d' ' -f1)" == "$sha" ]] || return 1
  done < <("$jq_bin" -r '.files[]|[.path,.size,.sha256]|@tsv' "$json")
  while IFS= read -r -d '' actual; do
    actual="${actual#"$root/"}"
    [[ "$actual" == "$excluded" ]] && continue
    [[ -v seen[$actual] ]] || return 1
    count=$((count + 1))
  done < <(find "$root" -type f -print0)
  [[ "$count" == "${#seen[@]}" ]]
)

java_ci_verify() (
  set -o pipefail
  local root="$1" jq_bin="$2" metadata="$1/CI-PAYLOAD.json" role archive tag manifest index pin config input directory lock expected actual
  java_ci_json "$metadata" "$jq_bin" || { java_ci_error 'CI-PAYLOAD.json must be canonical jq -cS JSON'; return 1; }
  "$jq_bin" -e 'keys == ["files","images","inputs","origin","schemaVersion","seed"] and
    .schemaVersion == 1 and .origin == "local-build-candidate-not-publisher-authenticated" and
    ((.images|keys)==["builder","runtime"] or (.images|keys)==["builder","runtime","utility"]) and (.inputs|keys)==(.images|keys) and
    (.seed|keys)==["directory","lockSha256"] and
    .seed.directory == "maven-seed" and (.seed.lockSha256|test("^[0-9a-f]{64}$")) and
    all(.images[]; keys==["archive","archiveSha256","configDigest","index","indexSha256","input","manifest","manifestSha256","tag"] and
      (.archiveSha256|test("^[0-9a-f]{64}$")) and (.manifestSha256|test("^[0-9a-f]{64}$")) and
      (.indexSha256|test("^[0-9a-f]{64}$")) and
      (.configDigest|test("^sha256:[0-9a-f]{64}$")) and
      (.tag|test("^[A-Za-z0-9][A-Za-z0-9._:/-]*:[A-Za-z0-9][A-Za-z0-9._-]*$")) and (.tag|endswith(":latest")|not)) and
    all(.inputs[]; keys==["directory","lockSha256"] and (.lockSha256|test("^[0-9a-f]{64}$")))' "$metadata" >/dev/null || return 1
  java_ci_inventory "$root" "$metadata" "$jq_bin" CI-PAYLOAD.json || { java_ci_error 'Payload inventory/hash mismatch'; return 1; }
  for role in $("$jq_bin" -r '.images|keys[]' "$metadata"); do
    directory="$("$jq_bin" -r --arg r "$role" '.inputs[$r].directory' "$metadata")"
    [[ "$directory" == "inputs/$role" ]] || return 1
    lock="$root/$directory/INPUTS.lock.json"
    pin="$("$jq_bin" -r --arg r "$role" '.inputs[$r].lockSha256' "$metadata")"
    [[ "$(sha256sum "$lock" | cut -d' ' -f1)" == "$pin" ]] || return 1
    java_ci_json "$lock" "$jq_bin" && "$jq_bin" -e 'keys==["files","schemaVersion"] and .schemaVersion==1' "$lock" >/dev/null || return 1
    java_ci_inventory "$root/$directory" "$lock" "$jq_bin" INPUTS.lock.json || return 1
    [[ -f "$root/$directory/Dockerfile" ]] || return 1
    archive="$("$jq_bin" -r --arg r "$role" '.images[$r].archive' "$metadata")"
    manifest="$("$jq_bin" -r --arg r "$role" '.images[$r].manifest' "$metadata")"
    index="$("$jq_bin" -r --arg r "$role" '.images[$r].index' "$metadata")"
    tag="$("$jq_bin" -r --arg r "$role" '.images[$r].tag' "$metadata")"
    config="$("$jq_bin" -r --arg r "$role" '.images[$r].configDigest' "$metadata")"
    input="$("$jq_bin" -r --arg r "$role" '.images[$r].input' "$metadata")"
    [[ "$input" == "$role" ]] && java_ci_path_safe "$archive" && java_ci_path_safe "$manifest" || return 1
    [[ ( "$archive" == "$role.tar.gz" || "$archive" == "$role.tar" ) && "$manifest" == "evidence/$role-manifest.json" && "$index" == "evidence/$role-index.json" ]] || return 1
    expected="$("$jq_bin" -r --arg r "$role" '.images[$r].archiveSha256' "$metadata")"
    [[ "$(sha256sum "$root/$archive" | cut -d' ' -f1)" == "$expected" ]] || return 1
    expected="$("$jq_bin" -r --arg r "$role" '.images[$r].manifestSha256' "$metadata")"
    [[ "$(sha256sum "$root/$manifest" | cut -d' ' -f1)" == "$expected" ]] || return 1
    pin="$("$jq_bin" -r --arg r "$role" '.images[$r].indexSha256' "$metadata")"
    [[ "$(sha256sum "$root/$index" | cut -d' ' -f1)" == "$pin" ]] || return 1
    java_ci_json_unique "$root/$index" "$jq_bin" || return 1
    "$jq_bin" -e --arg p "sha256:$expected" '.schemaVersion==2 and
      [.manifests[]|select(.platform.os=="linux" and .platform.architecture=="amd64")|.digest] == [$p]' "$root/$index" >/dev/null || return 1
    actual="$(image_archive_member_sha256 "$root/$archive" "blobs/sha256/$pin")" || return 1
    [[ "$actual" == "$pin" ]] || return 1
    java_ci_json_unique "$root/$manifest" "$jq_bin" || return 1
    "$jq_bin" -e --arg c "$config" '.schemaVersion==2 and .config.digest==$c and
      (.layers|type)=="array" and all(.layers[]; (.digest|test("^sha256:[0-9a-f]{64}$")))' "$root/$manifest" >/dev/null || return 1
    actual="$(image_archive_id_for_tag "$root/$archive" "$tag" "$jq_bin")" || return 1
    [[ "$actual" == "$config" ]] || return 1
    # Real OCI blob identity, not an invented manifest inferred from a config ID.
    expected="$("$jq_bin" -r '.layers[].digest|ltrimstr("sha256:")|"blobs/sha256/"+.' "$root/$manifest")"
    actual="$(image_archive_manifest "$root/$archive" | "$jq_bin" -r --arg t "$tag" '.[]|select((.RepoTags//[])|index($t))|.Layers[]')" || return 1
    [[ "$actual" == "$expected" ]] || { java_ci_error "$role requires OCI-blob docker-save archive linked to actual manifest"; return 1; }
    # The captured platform manifest itself must exist byte-for-byte in the archive.
    actual="$(image_archive_member_sha256 "$root/$archive" "blobs/sha256/$(sha256sum "$root/$manifest" | cut -d' ' -f1)")" || return 1
    [[ "$actual" == "$(sha256sum "$root/$manifest" | cut -d' ' -f1)" ]] || return 1
    local tar_options=()
    [[ "$archive" != *.gz ]] || tar_options=(-z)
    actual="$(tar -xO "${tar_options[@]}" -f "$root/$archive" "blobs/sha256/${config#sha256:}" | "$jq_bin" -er '[.os,.architecture]|join("/")')" || return 1
    [[ "$actual" == linux/amd64 ]] || return 1
  done
  lock="$root/maven-seed/seed.lock.json"
  pin="$("$jq_bin" -r '.seed.lockSha256' "$metadata")"
  [[ "$(sha256sum "$lock" | cut -d' ' -f1)" == "$pin" ]] || return 1
  java_ci_json_unique "$lock" "$jq_bin" || return 1
  # Existing seed uses pretty JSON; its exact bytes are independently pinned.
  "$jq_bin" -e '.schema==1 and (.projects|type)=="array" and (.projects|length)>0 and (.identity|type)=="object" and
    all(.projects[]; (.name|type)=="string" and (.pom_sha256|type)=="string" and
      (.files|type)=="array" and (.files|length)>0 and all(.files[];type=="string")) and
    (.files|all(.[];.path|startswith("repository/") or startswith("sources/"))) and
    (.files|any(.[];.path|startswith("repository/")))' "$lock" >/dev/null || return 1
  java_ci_inventory "$root/maven-seed" "$lock" "$jq_bin" seed.lock.json || return 1
  while IFS= read -r actual; do
    java_ci_cache_artifact "${actual#repository/}" || { java_ci_error 'Seed repository contains non-artifact/credential path'; return 1; }
  done < <("$jq_bin" -r '.files[].path|select(startswith("repository/"))' "$lock")
  local -A names=() sources=()
  local name pom path
  while IFS=$'\t' read -r name pom; do
    [[ "$name" =~ ^[A-Za-z0-9_-]+$ && ! -v names[$name] && "$pom" =~ ^[0-9a-f]{64}$ ]] || return 1
    names[$name]=1
    [[ "$(sha256sum "$root/maven-seed/sources/$name/pom.xml" | cut -d' ' -f1)" == "$pom" ]] || return 1
    while IFS= read -r path; do
      java_ci_path_safe "$path" && [[ ! -v sources[$name/$path] ]] || return 1
      sources[$name/$path]=1
    done < <("$jq_bin" -r --arg n "$name" '.projects[]|select(.name==$n)|.files[]' "$lock")
  done < <("$jq_bin" -r '.projects[]|[.name,.pom_sha256]|@tsv' "$lock")
  local count=0
  while IFS= read -r path; do
    path="${path#sources/}"
    [[ -v sources[$path] ]] || return 1
    count=$((count+1))
  done < <("$jq_bin" -r '.files[].path|select(startswith("sources/"))' "$lock")
  [[ "$count" == "${#sources[@]}" ]] || return 1
  printf '[java-ci] Verified signed-candidate bytes, OCI archive identity and seed/input inventory; build completeness not attested.\n'
)

# 설치 기본 모드에는 반입 서명/해시 인증 없이 필수 파일과 형식만 확인한다.
java_ci_install_readiness() {
  local root="$1" jq_bin="$2" role archive relative seed_paths
  if [[ "${OFFLINE_VERIFY_AUTHENTICITY:-0}" == 1 ]]; then
    java_ci_verify "$root" "$jq_bin"; return $?
  fi
  [[ -f "$root/CI-PAYLOAD.json" && ! -L "$root/CI-PAYLOAD.json" ]] || return 1
  "$jq_bin" -e '(.images|keys) == ["builder","runtime"] or (.images|keys) == ["builder","runtime","utility"]' "$root/CI-PAYLOAD.json" >/dev/null || return 1
  for role in $("$jq_bin" -r '.images|keys[]' "$root/CI-PAYLOAD.json"); do
    archive="$("$jq_bin" -er --arg r "$role" '.images[$r].archive' "$root/CI-PAYLOAD.json")" || return 1
    java_ci_path_safe "$archive" && [[ -s "$root/$archive" && ! -L "$root/$archive" ]] || return 1
    [[ -d "$root/inputs/$role" && ! -L "$root/inputs/$role" ]] || return 1
  done
  [[ -f "$root/maven-seed/seed.lock.json" ]] || return 1
  "$jq_bin" -e '(.files | type) == "array" and (.files | length) > 0 and all(.files[]; (.path | type) == "string")' "$root/maven-seed/seed.lock.json" >/dev/null || return 1
  seed_paths="$("$jq_bin" -er '.files[].path' "$root/maven-seed/seed.lock.json")" || return 1
  while IFS= read -r relative; do
    java_ci_path_safe "$relative" && [[ -f "$root/maven-seed/$relative" && ! -L "$root/maven-seed/$relative" ]] || return 1
  done <<< "$seed_paths"
}

java_ci_loaded_contract() {
  local root="$1" jq_bin="$2" role tag config manifest platform roles
  [[ -f "$root/CI-PAYLOAD.json" && ! -L "$root/CI-PAYLOAD.json" ]] || return 1
  java_ci_json "$root/CI-PAYLOAD.json" "$jq_bin" || return 1
  roles="$("$jq_bin" -er '.images|keys|join(" ")' "$root/CI-PAYLOAD.json")" || return 1
  [[ "$roles" == 'builder runtime' || "$roles" == 'builder runtime utility' ]] || return 1
  for role in $roles; do
    tag="$("$jq_bin" -r --arg r "$role" '.images[$r].tag' "$root/CI-PAYLOAD.json")" || return 1
    config="$("$jq_bin" -r --arg r "$role" '.images[$r].configDigest' "$root/CI-PAYLOAD.json")" || return 1
    platform="sha256:$("$jq_bin" -r --arg r "$role" '.images[$r].manifestSha256' "$root/CI-PAYLOAD.json")" || return 1
    docker_image_matches_config "$tag" "$config" "$platform" || return 1
  done
}

java_ci_preload() {
  local root="$1" jq_bin="$2" role archive
  java_ci_install_readiness "$root" "$jq_bin" || return 1
  if ! java_ci_loaded_contract "$root" "$jq_bin"; then
    for role in $("$jq_bin" -r '.images|keys[]' "$root/CI-PAYLOAD.json"); do
      archive="$("$jq_bin" -r --arg r "$role" '.images[$r].archive' "$root/CI-PAYLOAD.json")" || return 1
      if [[ "$archive" == *.gz ]]; then
        (set -o pipefail; gzip -dc "$root/$archive" | docker load) || return 1
      else
        docker load -i "$root/$archive" || return 1
      fi
    done
  fi
  java_ci_loaded_contract "$root" "$jq_bin"
}
