#!/usr/bin/env bash
# Offline bundle preflight. Read-only: does not install packages or change services.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUNDLE_ROOT="${BUNDLE_ROOT:-$SCRIPT_DIR/bundles}"
. "$SCRIPT_DIR/lib/release-metadata.sh"
. "$SCRIPT_DIR/lib/rpm-repository.sh"
. "$SCRIPT_DIR/lib/mariadb-packages.sh"
. "$SCRIPT_DIR/components/target-prerequisites.sh"
. "$SCRIPT_DIR/lib/java-ci-payload.sh"
require_java_ci=0
errors=0
verify_manifest=0
bom_loaded=0
lock_loaded=0
trusted_public_key="${OFFLINE_MANIFEST_PUBLIC_KEY:-}"
# 기본 설치는 파일·버전·아키텍처만 검사한다. 서명은 명시적 호환 옵션이다.
require_signature=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-manifest) verify_manifest=0 ;;
    --verify-manifest) verify_manifest=1 ;;
    --trusted-public-key)
      [[ $# -ge 2 ]] || { printf '%s requires a path\n' "$1" >&2; exit 2; }
      trusted_public_key="$2"; require_signature=1; verify_manifest=1; shift
      ;;
    --require-signature) require_signature=1; verify_manifest=1 ;;
    --require-java-ci) require_java_ci=1 ;;
    --allow-unsigned)
      require_signature=0; verify_manifest=1
      ;;
    -h|--help)
      printf 'Usage: %s [--verify-manifest] [--trusted-public-key PATH] [--require-signature|--allow-unsigned] [--require-java-ci]\n' "$0"
      printf 'By default only installation prerequisites, versions and architecture are checked.\n'
      printf '%s\n' '--verify-manifest enables optional SHA inventory verification; --require-signature requires an external key.'
      exit 0
      ;;
    *) printf 'Unknown option: %s\n' "$1" >&2; exit 2 ;;
  esac
  shift
done

if [[ "$verify_manifest" == 0 && "$require_signature" =~ ^(1|y|yes|true)$ ]]; then
  printf '%s\n' '--skip-manifest cannot be combined with signature enforcement' >&2
  exit 2
fi

export OFFLINE_VERIFY_AUTHENTICITY="$verify_manifest"

fail() { printf '[FAIL] %s\n' "$*" >&2; errors=$((errors + 1)); }
ok() { printf '[ OK ] %s\n' "$*"; }

require_file() {
  local path="$1" label="$2"
  [[ -f "$path" ]] && ok "$label" || fail "$label: $path"
}

require_any_file() {
  local label="$1"; shift
  local path
  for path in "$@"; do
    [[ -f "$path" ]] && { ok "$label: $path"; return 0; }
  done
  fail "$label: none of the expected files exists"
}

resolve_jq() {
  local bundled actual
  if command -v jq >/dev/null 2>&1; then
    command -v jq
    return 0
  fi
  bundled="$BUNDLE_ROOT/common/jq/jq-linux-amd64"
  [[ -f "$bundled" && "${BOM_JQ_SHA256:-}" =~ ^[0-9a-f]{64}$ ]] || return 1
  if [[ "$verify_manifest" == 1 ]]; then
    actual="$(sha256sum "$bundled" | awk '{print $1}')" || return 1
    [[ "$actual" == "$BOM_JQ_SHA256" ]] || return 1
  fi
  [[ -x "$bundled" ]] || return 1
  printf '%s' "$bundled"
}

require_image_readiness() {
  local archive="$1" tag="$2" label="$3" manifest config os_arch
  manifest="$(image_archive_manifest "$archive")" || { fail "$label archive cannot be read"; return 0; }
  config="$(printf '%s' "$manifest" | "$JQ_BIN" -er --arg t "$tag" '[.[] | select((.RepoTags // []) | index($t)) | .Config] | unique | if length == 1 then .[0] else error("image tag missing or ambiguous") end')" \
    || { fail "$label image tag is missing or ambiguous: $tag"; return 0; }
  [[ "$config" =~ ^([0-9a-f]{64}\.json|blobs/sha256/[0-9a-f]{64})$ ]] \
    || { fail "$label archive config path is invalid"; return 0; }
  os_arch="$(tar -xOaf "$archive" "$config" | "$JQ_BIN" -er '[.os,.architecture]|join("/")')" \
    || { fail "$label image configuration cannot be read"; return 0; }
  [[ "$os_arch" == linux/amd64 ]] && ok "$label image $tag ($os_arch)" || fail "$label unsupported image platform: $os_arch"
}

require_image_contract() {
  local archive="$1" label="$2" expected_image="$3" locked_image="$4"
  local digest="$5" pinned_ref="$6" platform_digest="$7" config_digest="$8"
  local locked_archive_id="$9" evidence_name="${10}"
  local repo pinned_repo actual_archive_id local_id source_file platform_file source_sha platform_sha descriptor_digest evidence_config evidence_layers archive_layers archive_manifest
  [[ -f "$archive" ]] || { fail "$label archive: $archive"; return 0; }
  if [[ "$verify_manifest" == 0 ]]; then
    require_image_readiness "$archive" "$expected_image" "$label"; return 0
  fi
  [[ "$locked_image" == "$expected_image" ]] \
    || fail "$label lock image does not match BOM: $locked_image != $expected_image"
  [[ "$digest" =~ ^sha256:[0-9a-f]{64}$ ]] \
    || { fail "$label source digest is missing or invalid: $digest"; return 0; }
  [[ "$pinned_ref" == *@"$digest" ]] \
    || fail "$label pinned ref is not bound to source digest: $pinned_ref"
  repo="$(release_image_repository "$expected_image")"
  pinned_repo="${pinned_ref%@*}"
  case "$pinned_repo" in
    "$repo"|"docker.io/$repo"|"docker.io/library/$repo") ;;
    *) fail "$label pinned ref repository does not match BOM: $pinned_repo" ;;
  esac
  [[ "$platform_digest" =~ ^sha256:[0-9a-f]{64}$ && "$config_digest" =~ ^sha256:[0-9a-f]{64}$ ]] \
    || { fail "$label OCI platform/config digest is missing or invalid"; return 0; }
  source_file="$BUNDLE_ROOT/common/release-evidence/${evidence_name}-source.json"
  platform_file="$BUNDLE_ROOT/common/release-evidence/${evidence_name}-linux-amd64.json"
  [[ -f "$source_file" && -f "$platform_file" ]] \
    || { fail "$label OCI source/platform evidence is missing"; return 0; }
  source_sha="sha256:$(sha256sum "$source_file" | awk '{print $1}')"
  platform_sha="sha256:$(sha256sum "$platform_file" | awk '{print $1}')"
  [[ "$source_sha" == "$digest" ]] \
    || fail "$label source OCI evidence bytes do not match registry digest"
  [[ "$platform_sha" == "$platform_digest" ]] \
    || fail "$label platform OCI evidence bytes do not match descriptor digest"
  descriptor_digest="$("$JQ_BIN" -er --arg source "$digest" '
    if (.manifests? | type) == "array" then
      [.manifests[] | select(.platform.os == "linux" and .platform.architecture == "amd64") | .digest]
      | unique | if length == 1 then .[0] else error("linux/amd64 descriptor must be unique") end
    else $source end
  ' "$source_file" 2>/dev/null)" || descriptor_digest=""
  [[ "$descriptor_digest" == "$platform_digest" ]] \
    || fail "$label source OCI manifest does not select the locked linux/amd64 descriptor"
  evidence_config="$("$JQ_BIN" -er '.config.digest | select(test("^sha256:[0-9a-f]{64}$"))' \
    "$platform_file" 2>/dev/null)" || evidence_config=""
  [[ "$evidence_config" == "$config_digest" ]] \
    || fail "$label platform OCI manifest does not bind the locked config digest"
  evidence_layers="$("$JQ_BIN" -er '[.layers[]?.digest | select(test("^sha256:[0-9a-f]{64}$"))] | length' \
    "$platform_file" 2>/dev/null)" || evidence_layers=""
  if ! archive_manifest="$(image_archive_manifest "$archive")"; then
    fail "$label archive manifest read failed: $archive (see tar/gzip diagnostics above)"
  elif ! archive_layers="$(printf '%s' "$archive_manifest" | "$JQ_BIN" -er --arg tag "$expected_image" \
    '[.[] | select((.RepoTags // []) | index($tag)) | (.Layers // [])] | unique | if length == 1 then .[0] | length else error("tag layers must be unique") end' 2>/dev/null)"; then
    fail "$label archive manifest tag/layers are invalid: $expected_image"
  else
    [[ -n "$evidence_layers" && "$evidence_layers" == "$archive_layers" ]] \
      || fail "$label OCI descriptor/archive layer count differs"
  fi
  release_image_id_valid "$locked_archive_id" \
    || { fail "$label archive image ID is missing or invalid: $locked_archive_id"; return 0; }
  actual_archive_id="$(image_archive_id_for_tag "$archive" "$expected_image" "$JQ_BIN")" \
    || { fail "$label archive tag/config identity verification failed: $expected_image (see diagnostics above)"; return 0; }
  [[ "$actual_archive_id" == "$locked_archive_id" ]] \
    && ok "$label registry/archive contract: $pinned_ref -> $actual_archive_id" \
    || fail "$label archive image ID does not match lock: $actual_archive_id != $locked_archive_id"
  [[ "$actual_archive_id" == "$config_digest" ]] \
    || fail "$label docker-save config is not the OCI platform manifest config: $actual_archive_id != $config_digest"
  if command -v docker >/dev/null 2>&1 \
      && local_id="$(docker image inspect --format '{{.Id}}' "$expected_image" 2>/dev/null)"; then
    if [[ "$local_id" == "$locked_archive_id" ]]; then
      ok "$label loaded image ID matches archive lock"
    else
      printf '[WARN] %s local image drift: %s != %s; installer will reload the verified archive\n' \
        "$label" "$local_id" "$locked_archive_id"
    fi
  fi
}

verify_manifest_signature() {
  local manifest="$BUNDLE_ROOT/MANIFEST.sha256"
  local signature="$BUNDLE_ROOT/MANIFEST.sha256.sig"
  local bundled_key="$BUNDLE_ROOT/MANIFEST-PUBLIC-KEY.pem" key="$trusted_public_key" trust="external"
  [[ "$verify_manifest" == 1 ]] || return 0
  if [[ -z "$key" && -f "$signature" && -f "$bundled_key" ]]; then
    key="$bundled_key"; trust="co-travel"
  fi
  if [[ "$require_signature" =~ ^(1|y|yes|true)$ && -z "$trusted_public_key" ]]; then
    fail "signature enforcement requires an out-of-band trusted public key (--trusted-public-key)"
    return 0
  fi
  if [[ -n "$trusted_public_key" ]]; then
    local resolved_key resolved_key_path resolved_parent resolved_root
    [[ "$trusted_public_key" == /* ]] \
      || { fail "trusted public key path must be absolute"; return 0; }
    resolved_key="$(readlink -f "$trusted_public_key" 2>/dev/null || true)"
    resolved_root="$(readlink -f "$BUNDLE_ROOT" 2>/dev/null || true)"
    resolved_parent="$(readlink -f "$(dirname "$trusted_public_key")" 2>/dev/null || true)"
    resolved_key_path="$resolved_parent/$(basename "$trusted_public_key")"
    [[ -n "$resolved_key" && -n "$resolved_root" && -n "$resolved_parent" \
      && "$resolved_key" != "$resolved_root" && "$resolved_key" != "$resolved_root"/* \
      && "$resolved_key_path" != "$resolved_root" && "$resolved_key_path" != "$resolved_root"/* ]] \
      || { fail "trusted public key must be pinned outside the bundle root"; return 0; }
  fi
  if [[ -z "$key" ]]; then
    [[ ! -f "$signature" ]] \
      || fail "MANIFEST.sha256.sig exists but no public key is available"
    return 0
  fi
  [[ -f "$manifest" ]] || { fail "signature target is missing: MANIFEST.sha256"; return 0; }
  [[ -f "$signature" ]] || { fail "detached manifest signature is missing: $signature"; return 0; }
  [[ -r "$key" ]] || { fail "manifest public key is not readable: $key"; return 0; }
  command -v openssl >/dev/null 2>&1 \
    || { fail "openssl is required for detached manifest signature verification"; return 0; }
  if openssl dgst -sha256 -verify "$key" -signature "$signature" "$manifest" >/dev/null 2>&1; then
    ok "detached MANIFEST.sha256 signature verified ($trust key)"
    if [[ "$trust" == "co-travel" ]]; then
      printf '[WARN] bundled public key proves consistency only; pin its fingerprint out-of-band for authenticity\n'
    fi
  else
    fail "detached MANIFEST.sha256 signature verification failed"
  fi
}

manifest_paths_are_safe() {
  local manifest="$1"
  # %와 ~도 실제 파일명으로만 처리한다. 셸 확장이나 URL decoding은 금지한다.
  # GNU grep ERE의 \\r은 CR이 아니다. 파일은 변경하지 않고 줄 끝 CR만 정규화한다.
  if sed 's/\r$//' "$manifest" \
      | LC_ALL=C grep -Ev '^[0-9a-f]{64} [ *](\./)?[A-Za-z0-9._/+@%~:-]+$' >/dev/null; then
    return 1
  fi
  if sed 's/\r$//' "$manifest" | sed -n 's/^[0-9a-f]\{64\} [ *]//p' \
      | grep -E '(^|/)\.\.(/|$)|^/' >/dev/null; then
    return 1
  fi
  return 0
}

verify_manifest_integrity() {
  local manifest_safe manifest_paths actual_paths
  if [[ "$verify_manifest" == 0 ]]; then
    printf '[ OK ] 기본 설치 모드: 서명·공개키·SHA 목록 인증을 요구하지 않습니다.\n'
  elif [[ -f "$BUNDLE_ROOT/MANIFEST.sha256" ]]; then
    manifest_safe=1
    # GNU sha256sum emits two spaces in text mode and " *" in binary mode.
    # Git for Windows defaults to the latter, so accept both canonical forms.
    if ! manifest_paths_are_safe "$BUNDLE_ROOT/MANIFEST.sha256"; then
      fail "MANIFEST.sha256 contains an invalid or unsafe path"
      manifest_safe=0
    fi
    if (( manifest_safe )); then
      sed 's/\r$//' "$BUNDLE_ROOT/MANIFEST.sha256" | grep -E '[[:space:]][ *](\./)?RELEASE-BOM\.env$' >/dev/null \
        || fail "MANIFEST.sha256 does not include RELEASE-BOM.env"
      sed 's/\r$//' "$BUNDLE_ROOT/MANIFEST.sha256" | grep -E '[[:space:]][ *](\./)?RELEASE-LOCK\.env$' >/dev/null \
        || fail "MANIFEST.sha256 does not include RELEASE-LOCK.env"
      if find "$BUNDLE_ROOT" -type l -print -quit | grep -q .; then
        fail "bundle must not contain symbolic links"
      fi
      manifest_paths="$(sed 's/\r$//' "$BUNDLE_ROOT/MANIFEST.sha256" \
        | sed -n 's/^[0-9a-f]\{64\} [ *]//p' | sed 's#^\./##; s#^#./#' | LC_ALL=C sort)"
      # prepare-offline-bundle.ps1와 동일하게 저장소 관리 문서/수집기만 payload 목록에서 제외한다.
      actual_paths="$(cd "$BUNDLE_ROOT" && find . -type f \
        ! -path '*/.work/*' \
        ! -name MANIFEST.sha256 ! -name MANIFEST.sha256.sig ! -name .gitignore ! -name .gitkeep \
        ! -name README.md ! -name MANIFEST.md ! -name fetch-bundles.sh \
        -print | LC_ALL=C sort)"
      [[ "$manifest_paths" == "$actual_paths" ]] \
        || fail "MANIFEST.sha256 file list does not exactly match bundle contents"
      # Bundles are often copied through Windows media; tolerate CRLF without
      # weakening the digest check itself.
      (cd "$BUNDLE_ROOT" && sed 's/\r$//' MANIFEST.sha256 | sha256sum -c --quiet) \
        && ok "bundle SHA-256 manifest verified" \
        || fail "bundle SHA-256 manifest verification failed"
    fi
  else
    fail "MANIFEST.sha256 not found; integrity verification is mandatory"
  fi
}

printf 'Offline bundle preflight\n  root: %s\n' "$BUNDLE_ROOT"
[[ -d "$BUNDLE_ROOT" ]] || fail "bundle root does not exist"

# Authenticate the producer manifest and verify every payload before parsing
# bundle metadata or executing the bundled jq binary.  On failure, stop here:
# accumulating errors and continuing would let an untrusted executable run as
# the installer caller (normally root).
verify_manifest_signature
verify_manifest_integrity
if (( errors > 0 )); then
  printf '\nPreflight failed before bundle executable use: %d issue(s)\n' "$errors" >&2
  exit 1
fi

BOM_FILE="$BUNDLE_ROOT/RELEASE-BOM.env"
require_file "$BOM_FILE" "Release BOM"
if [[ -r "$BOM_FILE" ]]; then
  if load_release_bom "$BOM_FILE"; then
    bom_loaded=1
  else
    fail "Release BOM strict parsing failed"
  fi
fi
if (( bom_loaded )); then
  for image in "$BOM_GITLAB_IMAGE" "$BOM_RUNNER_IMAGE" "$BOM_RUNNER_HELPER_IMAGE" "$BOM_NEXUS_IMAGE"; do
    [[ "$image" != *:latest ]] || fail "Release BOM must not use latest: $image"
  done
fi

JQ_BIN="$(resolve_jq 2>/dev/null || true)"
[[ -n "$JQ_BIN" ]] || fail "jq is required to verify docker-save archive identity"
java_ci_root="$BUNDLE_ROOT/common/java-ci"
if [[ -e "$java_ci_root" || -L "$java_ci_root" ]]; then
  [[ -n "$JQ_BIN" ]] && java_ci_install_readiness "$java_ci_root" "$JQ_BIN" \
    && ok 'Java CI payload bytes/OCI/inputs/seed contract' || fail 'Java CI payload verification failed'
elif [[ "$require_java_ci" == 1 ]]; then
  fail 'Required Java CI payload is missing (bootstrap-only bundle)'
else
  printf '[WARN] Java CI payload absent: bootstrap verification does not attest Java CI readiness\n'
fi

LOCK_FILE="$BUNDLE_ROOT/RELEASE-LOCK.env"
require_file "$LOCK_FILE" "Release source-digest lock"
if (( bom_loaded )) && [[ -r "$LOCK_FILE" ]]; then
  if load_release_lock "$LOCK_FILE"; then
    lock_loaded=1
  else
    fail "Release lock strict parsing failed"
  fi
fi
if (( bom_loaded && lock_loaded )); then
  [[ "${LOCK_BOM_RELEASE:-}" == "$BOM_RELEASE" ]] \
    || fail "Release lock BOM version does not match: ${LOCK_BOM_RELEASE:-<missing>} != $BOM_RELEASE"
  lock_pairs=(
    "GITLAB|$BUNDLE_ROOT/common/gitlab/gitlab.tar.gz|$BOM_GITLAB_IMAGE|${LOCK_GITLAB_IMAGE:-}|${LOCK_GITLAB_DIGEST:-}|${LOCK_GITLAB_PINNED_REF:-}|${LOCK_GITLAB_PLATFORM_DIGEST:-}|${LOCK_GITLAB_CONFIG_DIGEST:-}|${LOCK_GITLAB_ARCHIVE_ID:-}|gitlab"
    "RUNNER|$BUNDLE_ROOT/common/gitlab-runner/gitlab-runner.tar.gz|$BOM_RUNNER_IMAGE|${LOCK_RUNNER_IMAGE:-}|${LOCK_RUNNER_DIGEST:-}|${LOCK_RUNNER_PINNED_REF:-}|${LOCK_RUNNER_PLATFORM_DIGEST:-}|${LOCK_RUNNER_CONFIG_DIGEST:-}|${LOCK_RUNNER_ARCHIVE_ID:-}|runner"
    "RUNNER_HELPER|$BUNDLE_ROOT/common/gitlab-runner/gitlab-runner.tar.gz|$BOM_RUNNER_HELPER_IMAGE|${LOCK_RUNNER_HELPER_IMAGE:-}|${LOCK_RUNNER_HELPER_DIGEST:-}|${LOCK_RUNNER_HELPER_PINNED_REF:-}|${LOCK_RUNNER_HELPER_PLATFORM_DIGEST:-}|${LOCK_RUNNER_HELPER_CONFIG_DIGEST:-}|${LOCK_RUNNER_HELPER_ARCHIVE_ID:-}|runner-helper"
    "RUNNER_BUILD|$BUNDLE_ROOT/common/gitlab-runner/gitlab-runner.tar.gz|$BOM_RUNNER_BUILD_IMAGE|${LOCK_RUNNER_BUILD_IMAGE:-}|${LOCK_RUNNER_BUILD_DIGEST:-}|${LOCK_RUNNER_BUILD_PINNED_REF:-}|${LOCK_RUNNER_BUILD_PLATFORM_DIGEST:-}|${LOCK_RUNNER_BUILD_CONFIG_DIGEST:-}|${LOCK_RUNNER_BUILD_ARCHIVE_ID:-}|runner-build"
    "NEXUS|$BUNDLE_ROOT/common/nexus/nexus.tar.gz|$BOM_NEXUS_IMAGE|${LOCK_NEXUS_IMAGE:-}|${LOCK_NEXUS_DIGEST:-}|${LOCK_NEXUS_PINNED_REF:-}|${LOCK_NEXUS_PLATFORM_DIGEST:-}|${LOCK_NEXUS_CONFIG_DIGEST:-}|${LOCK_NEXUS_ARCHIVE_ID:-}|nexus"
  )
  for pair in "${lock_pairs[@]}"; do
    IFS='|' read -r label archive expected_image locked_image digest pinned_ref platform_digest config_digest archive_id evidence_name <<< "$pair"
    [[ -n "$JQ_BIN" ]] && require_image_contract "$archive" "$label" "$expected_image" \
      "$locked_image" "$digest" "$pinned_ref" "$platform_digest" "$config_digest" "$archive_id" "$evidence_name"
  done
fi

require_file "$BUNDLE_ROOT/common/deploy-system/deploy-ops.jar" "DeployOps JAR"
worksystem_jar="$BUNDLE_ROOT/${BOM_WORKSYSTEM_JAR:-common/worksystem-server/worksystem.jar}"
if [[ "${BOM_WORKSYSTEM_INCLUDED:-true}" == true ]]; then
  require_file "$worksystem_jar" "Worksystem server JAR"
elif [[ -e "$BUNDLE_ROOT/common/worksystem-server" || -L "$BUNDLE_ROOT/common/worksystem-server" ]]; then
  fail "Excluded Worksystem payload must be absent: common/worksystem-server"
fi
java_archive="$BUNDLE_ROOT/common/java/${BOM_JAVA_ARCHIVE:-missing}"
require_file "$java_archive" "Temurin Java ${BOM_JAVA_VERSION:-unknown} archive"
if [[ "$verify_manifest" == 1 && -f "$java_archive" && "${BOM_JAVA_SHA256:-}" =~ ^[0-9a-f]{64}$ ]]; then
  java_sha="$(sha256sum "$java_archive" | awk '{print $1}')"
  [[ "$java_sha" == "$BOM_JAVA_SHA256" ]] \
    && ok "Temurin Java archive SHA-256 matches BOM" \
    || fail "Temurin Java archive SHA-256 does not match BOM"
fi
java_candidates="$(find "$BUNDLE_ROOT/common/java" -maxdepth 1 -type f \
  -name 'OpenJDK21U-jdk_x64_linux_hotspot_*.tar.gz' -printf '%f\n' 2>/dev/null || true)"
[[ "$java_candidates" == "${BOM_JAVA_ARCHIVE:-}" ]] \
  || fail "Java bundle must contain exactly the BOM archive; found: ${java_candidates//$'\n'/,}"
require_any_file "GitLab image archive" "$BUNDLE_ROOT/common/gitlab/gitlab.tar.gz"
require_any_file "Nexus image archive" "$BUNDLE_ROOT/common/nexus/nexus.tar.gz"
require_any_file "Runner image archive" "$BUNDLE_ROOT/common/gitlab-runner/gitlab-runner.tar.gz"
require_file "$BUNDLE_ROOT/common/jq/jq-linux-amd64" "jq binary for daemon.json merge"
if [[ "$verify_manifest" == 1 ]]; then
jq_bundle_sha="$(sha256sum "$BUNDLE_ROOT/common/jq/jq-linux-amd64" 2>/dev/null | awk '{print $1}' || true)"
[[ "$jq_bundle_sha" == "${BOM_JQ_SHA256:-<missing>}" ]] \
  && ok "jq ${BOM_JQ_VERSION:-unknown} SHA-256 matches BOM" \
  || fail "jq binary SHA-256 does not match BOM"

fi

deploy_jar="$BUNDLE_ROOT/${BOM_DEPLOYOPS_JAR:-common/deploy-system/deploy-ops.jar}"
if (( bom_loaded && lock_loaded )); then
  [[ "$LOCK_DEPLOYOPS_JAR" == "$BOM_DEPLOYOPS_JAR" ]] \
    || fail "DeployOps JAR path lock does not match BOM"
  [[ "$LOCK_DEPLOYOPS_VERSION" == "$BOM_DEPLOYOPS_VERSION" ]] \
    || fail "DeployOps version lock does not match BOM: $LOCK_DEPLOYOPS_VERSION != $BOM_DEPLOYOPS_VERSION"
  [[ "$LOCK_DEPLOYOPS_REQUIRED_MIGRATIONS" == "$BOM_DEPLOYOPS_REQUIRED_MIGRATIONS" ]] \
    || fail "DeployOps migration lock does not match BOM"
  if [[ "$verify_manifest" == 0 ]]; then :
  elif [[ "$LOCK_DEPLOYOPS_SHA256" =~ ^[0-9a-f]{64}$ && -f "$deploy_jar" ]]; then
    deploy_jar_sha="$(sha256sum "$deploy_jar" | awk '{print $1}')"
    [[ "$deploy_jar_sha" == "$LOCK_DEPLOYOPS_SHA256" ]] \
      && ok "DeployOps JAR SHA-256 matches release lock" \
      || fail "DeployOps JAR SHA-256 does not match release lock"
  else
    fail "DeployOps JAR SHA-256 is missing or pending in release lock"
  fi
  if [[ "$BOM_WORKSYSTEM_INCLUDED" == true ]]; then
  [[ "$LOCK_WORKSYSTEM_JAR" == "$BOM_WORKSYSTEM_JAR" ]] \
    || fail "Worksystem JAR path lock does not match BOM"
  [[ "$LOCK_WORKSYSTEM_VERSION" == "$BOM_WORKSYSTEM_VERSION" ]] \
    || fail "Worksystem version lock does not match BOM: $LOCK_WORKSYSTEM_VERSION != $BOM_WORKSYSTEM_VERSION"
  if [[ "$verify_manifest" == 0 ]]; then :
  elif [[ "$LOCK_WORKSYSTEM_SHA256" =~ ^[0-9a-f]{64}$ && -f "$worksystem_jar" ]]; then
    worksystem_jar_sha="$(sha256sum "$worksystem_jar" | awk '{print $1}')"
    [[ "$worksystem_jar_sha" == "$LOCK_WORKSYSTEM_SHA256" ]] \
      && ok "Worksystem JAR SHA-256 matches release lock" \
      || fail "Worksystem JAR SHA-256 does not match release lock"
  else
    fail "Worksystem JAR SHA-256 is missing or pending in release lock"
  fi
  fi
fi
archive_python="$(command -v python3 || true)"
if [[ -z "$archive_python" && -x /usr/libexec/platform-python ]]; then
  archive_python=/usr/libexec/platform-python
fi
if command -v unzip >/dev/null 2>&1; then
  archive_tool="unzip"
elif command -v jar >/dev/null 2>&1; then
  archive_tool="jar"
elif [[ -n "$archive_python" ]]; then
  archive_tool="python"
else
  archive_tool=""
fi
if [[ -n "$archive_tool" ]]; then
  # `... | grep -q` 로 쓰면 grep 이 첫 매치에서 즉시 끝나며 unzip 이 SIGPIPE 를 받고,
  # set -o pipefail 탓에 <내용이 있는데도> 파이프라인이 실패로 잡힌다.
  # 반입을 막는 게이트가 거짓 경보를 내면 아무도 이 검사를 믿지 않게 된다 —
  # 목록을 먼저 받아 두고 파이프 없이 대조한다.
  if [[ "$archive_tool" == "unzip" ]]; then
    jar_listing="$(unzip -Z1 "$deploy_jar" 2>/dev/null || true)"
    jar_manifest="$(unzip -p "$deploy_jar" META-INF/MANIFEST.MF 2>/dev/null | tr -d '\r' || true)"
    if [[ "${BOM_WORKSYSTEM_INCLUDED:-true}" == true ]]; then
      worksystem_manifest="$(unzip -p "$worksystem_jar" META-INF/MANIFEST.MF 2>/dev/null | tr -d '\r' || true)"
    fi
  elif [[ "$archive_tool" == "jar" ]]; then
    jar_listing="$(jar tf "$deploy_jar" 2>/dev/null || true)"
    jar_tmp="$(mktemp -d)"
    (cd "$jar_tmp" && jar xf "$deploy_jar" META-INF/MANIFEST.MF >/dev/null 2>&1) || true
    jar_manifest="$(tr -d '\r' < "$jar_tmp/META-INF/MANIFEST.MF" 2>/dev/null || true)"
    rm -rf "$jar_tmp"
    if [[ "${BOM_WORKSYSTEM_INCLUDED:-true}" == true ]]; then
      jar_tmp="$(mktemp -d)"
      (cd "$jar_tmp" && jar xf "$worksystem_jar" META-INF/MANIFEST.MF >/dev/null 2>&1) || true
      worksystem_manifest="$(tr -d '\r' < "$jar_tmp/META-INF/MANIFEST.MF" 2>/dev/null || true)"
      rm -rf "$jar_tmp"
    fi
  else
    jar_listing="$("$archive_python" - "$deploy_jar" <<'PY' 2>/dev/null || true
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as jar:
    print("\n".join(jar.namelist()))
PY
)"
    jar_manifest="$("$archive_python" - "$deploy_jar" <<'PY' 2>/dev/null | tr -d '\r' || true
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as jar:
    sys.stdout.write(jar.read("META-INF/MANIFEST.MF").decode("utf-8"))
PY
)"
    if [[ "${BOM_WORKSYSTEM_INCLUDED:-true}" == true ]]; then
    worksystem_manifest="$("$archive_python" - "$worksystem_jar" <<'PY' 2>/dev/null | tr -d '\r' || true
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as jar:
    sys.stdout.write(jar.read("META-INF/MANIFEST.MF").decode("utf-8"))
PY
)"
    fi
  fi
  jar_version="$(printf '%s\n' "$jar_manifest" | sed -n 's/^Implementation-Version:[[:space:]]*//p' | head -1)"
  [[ "$jar_version" == "${BOM_DEPLOYOPS_VERSION:-}" ]] \
    && ok "DeployOps JAR version: $jar_version" \
    || fail "DeployOps JAR version does not match BOM: ${jar_version:-<missing>}"
  IFS=',' read -r -a required_migrations <<< "${BOM_DEPLOYOPS_REQUIRED_MIGRATIONS:-}"
  for agent_migration in V55__runner_agent_server_role.sql V56__nexus_agent_broker_binding.sql; do
    case ",${BOM_DEPLOYOPS_REQUIRED_MIGRATIONS:-}," in
      *",$agent_migration,"*) ;;
      *) fail "DeployOps BOM is missing required server agent migration: $agent_migration" ;;
    esac
  done
  for migration in "${required_migrations[@]}"; do
    case $'\n'"$jar_listing"$'\n' in
      *$'\n'"BOOT-INF/classes/db/migration/$migration"$'\n'*) ok "DeployOps JAR contains $migration" ;;
      *) fail "DeployOps JAR does not contain required migration: $migration" ;;
    esac
  done
  if [[ "${BOM_WORKSYSTEM_INCLUDED:-true}" == true ]]; then
  worksystem_version="$(printf '%s\n' "$worksystem_manifest" | sed -n 's/^Implementation-Version:[[:space:]]*//p' | head -1)"
  [[ "$worksystem_version" == "${BOM_WORKSYSTEM_VERSION:-}" ]] \
    && ok "Worksystem JAR version: $worksystem_version" \
    || fail "Worksystem JAR version does not match BOM: ${worksystem_version:-<missing>}"
  fi
else
  fail 'unzip/jar/python3 is required to inspect application version and DB migrations'
fi

verify_os_bundle() {
  local os="$1" asset=""
  case "$os" in
    rocky8)
      if [[ "$verify_manifest" == 1 ]]; then
        verify_rpm_signing_keys "$BUNDLE_ROOT/common/rpm-keys" || fail "pinned RPM signing keys missing or changed"
      fi
      [[ -d "$BUNDLE_ROOT/rocky8" ]] || { fail "Rocky 8 bundle directory is missing"; return; }
      asset="$(find "$BUNDLE_ROOT/rocky8" -type f \( -name '*.rpm' -o -name '*.tar.gz' \) -print -quit)"
      [[ -n "$asset" ]] && ok "Rocky 8 bundle assets present" || fail "Rocky 8 bundle directory is empty"
      verify_package_lock "$BUNDLE_ROOT/rocky8/docker" rpm \
        docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
      local mariadb_names mariadb_packages=()
      if mariadb_names="$(mariadb_rpm_package_names "$BUNDLE_ROOT/rocky8/mariadb")"; then
        mapfile -t mariadb_packages <<< "$mariadb_names"
        verify_package_lock "$BUNDLE_ROOT/rocky8/mariadb" rpm "${mariadb_packages[@]}"
      else
        fail 'MariaDB lock must select one complete, consistent vendor or OS package family'
      fi
      verify_package_lock "$BUNDLE_ROOT/rocky8/nginx" rpm nginx
      verify_package_lock "$BUNDLE_ROOT/rocky8/target-prerequisites" rpm \
        sudo policycoreutils policycoreutils-python-utils \
        iproute procps-ng shadow-utils util-linux tar gzip curl
      if [[ "$verify_manifest" == 1 ]]; then
        target_prerequisites_verify_rpm_signatures "$BUNDLE_ROOT/rocky8/target-prerequisites" "$BUNDLE_ROOT/common/rpm-keys" \
          || fail 'Target prerequisite Rocky publisher signature verification failed'
      fi
      ;;
    ubuntu)
      [[ -d "$BUNDLE_ROOT/ubuntu" ]] || { fail "Ubuntu bundle directory is missing"; return; }
      asset="$(find "$BUNDLE_ROOT/ubuntu" -type f \( -name '*.deb' -o -name '*.tar.gz' \) -print -quit)"
      [[ -n "$asset" ]] && ok "Ubuntu bundle assets present" || fail "Ubuntu bundle directory is empty"
      verify_package_lock "$BUNDLE_ROOT/ubuntu/docker" deb \
        docker.io containerd docker-buildx docker-compose-v2
      verify_package_lock "$BUNDLE_ROOT/ubuntu/mariadb" deb mariadb-server
      verify_package_lock "$BUNDLE_ROOT/ubuntu/nginx" deb nginx
      ;;
    *) fail "unsupported BUNDLE_OS selector: $os" ;;
  esac
}

verify_package_lock() {
  local dir="$1" kind="$2"; shift 2
  local lock="$dir/PACKAGES.lock" line name version arch sha filename actual locked_paths actual_paths req
  local payload_name payload_version payload_arch
  [[ "$kind" != rpm ]] || verify_rpm_repository "$dir" || fail "RPM repository metadata lock verification failed: $dir"
  [[ -f "$lock" ]] || { fail "$kind package version lock is missing: $lock"; return 0; }
  awk -F'|' '!seen_name[$1]++ && !seen_file[$5]++ { next } { exit 1 }' "$lock" \
    || { fail "$kind package lock contains a duplicate package or filename: $lock"; return 0; }
  locked_paths=""
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    # apt의 epoch 표기(%3a)는 파일명의 리터럴 문자다. URL decode/escape 해석을 하지 않는다.
    if [[ ! "$line" =~ ^([A-Za-z0-9._+-]+)\|([A-Za-z0-9.+:~_-]+)\|([A-Za-z0-9_+-]+)\|([0-9a-f]{64})\|([A-Za-z0-9._+%~:-]+\.(rpm|deb))$ ]]; then
      fail "unsafe package lock entry: $lock"; return 0
    fi
    name="${BASH_REMATCH[1]}"; version="${BASH_REMATCH[2]}"; arch="${BASH_REMATCH[3]}"
    if [[ "$dir" == */target-prerequisites && "$arch" != x86_64 && "$arch" != noarch ]]; then
      fail "target prerequisite architecture is not x86_64/noarch: $name ($arch)"
    fi
    sha="${BASH_REMATCH[4]}"; filename="${BASH_REMATCH[5]}"
    [[ "$filename" == *."$kind" ]] || { fail "package lock extension mismatch: $filename"; continue; }
    [[ -f "$dir/$filename" ]] || { fail "package lock payload missing: $dir/$filename"; continue; }
    if [[ "$verify_manifest" == 1 ]]; then
      actual="$(sha256sum "$dir/$filename" | awk '{print $1}')"
      [[ "$actual" == "$sha" ]] || fail "package SHA-256 mismatch: $dir/$filename"
    fi
    if [[ "$kind" == rpm ]]; then
      if ! command -v rpm >/dev/null 2>&1; then
        fail "rpm metadata verifier is required for Rocky bundle: $filename"; continue
      fi
      IFS='|' read -r payload_name payload_version payload_arch \
        < <(rpm -qp --qf '%{NAME}|%{EPOCHNUM}:%{VERSION}-%{RELEASE}|%{ARCH}\n' "$dir/$filename" 2>/dev/null) \
        || { fail "cannot read rpm payload metadata: $filename"; continue; }
    else
      if ! command -v dpkg-deb >/dev/null 2>&1; then
        fail "dpkg-deb metadata verifier is required for Ubuntu bundle: $filename"; continue
      fi
      payload_name="$(dpkg-deb -f "$dir/$filename" Package 2>/dev/null)" \
        || { fail "cannot read deb package name: $filename"; continue; }
      payload_version="$(dpkg-deb -f "$dir/$filename" Version 2>/dev/null)" \
        || { fail "cannot read deb package version: $filename"; continue; }
      payload_arch="$(dpkg-deb -f "$dir/$filename" Architecture 2>/dev/null)" \
        || { fail "cannot read deb package architecture: $filename"; continue; }
    fi
    [[ "$payload_name" == "$name" && "$payload_version" == "$version" && "$payload_arch" == "$arch" ]] \
      || fail "package metadata differs from lock: $filename ($payload_name|$payload_version|$payload_arch != $name|$version|$arch)"
    locked_paths+="$filename"$'\n'
  done < "$lock"
  for req in "$@"; do
    awk -F'|' -v required="$req" '$1 == required { found=1 } END { exit !found }' "$lock" \
      || fail "required package is not locked: $req ($dir)"
  done
  actual_paths="$(find "$dir" -maxdepth 1 -type f -name "*.$kind" -printf '%f\n' | LC_ALL=C sort)"
  locked_paths="$(printf '%s' "$locked_paths" | sed '/^$/d' | LC_ALL=C sort)"
  [[ "$actual_paths" == "$locked_paths" ]] \
    && ok "$kind package version/architecture inventory checked: $dir" \
    || fail "$kind package lock file list does not match payload: $dir"
}
if [[ -n "${BUNDLE_OS:-}" ]]; then
  verify_os_bundle "$BUNDLE_OS"
elif [[ -d "$BUNDLE_ROOT/rocky8" ]]; then
  verify_os_bundle rocky8
elif [[ -d "$BUNDLE_ROOT/ubuntu" ]]; then
  verify_os_bundle ubuntu
else
  fail "neither Rocky 8 nor Ubuntu OS package bundle is present"
fi

while IFS= read -r script; do
  bash -n "$script" || fail "shell syntax: $script"
done < <(find "$SCRIPT_DIR" -type f -name '*.sh' -not -path '*/.work/*' -print)
ok "shell syntax checks completed"

if (( errors > 0 )); then
  printf '\nPreflight failed: %d issue(s)\n' "$errors" >&2
  exit 1
fi
printf '\nPreflight passed. This bundle is ready for the selected OS installation rehearsal.\n'
