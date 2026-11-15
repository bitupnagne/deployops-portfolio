#!/usr/bin/env bash
# Control-flow regression only: tar, gzip, jq and Docker are mocked.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
. "$SCRIPT_DIR/lib/release-metadata.sh"
eval "$(sed -n '/^require_image_contract() {/,/^}/p' "$SCRIPT_DIR/verify-offline-bundle.sh")"
task_temp_root="$(cd "${TMPDIR:-/tmp}" && pwd)"
fixture="$(mktemp -d "$task_temp_root/deploy-archive-diagnostics.XXXXXX")"
cleanup_fixture() {
  [[ "$fixture" == "$task_temp_root"/deploy-archive-diagnostics.* ]] || return 1
  rm -rf -- "$fixture"
}
trap cleanup_fixture EXIT
mkdir "$fixture/tmp" "$fixture/common" "$fixture/common/release-evidence"
export TMPDIR="$fixture/tmp"
BUNDLE_ROOT="$fixture"
archive="$fixture/fixture.tar.gz"
: > "$archive"
printf 'source evidence' > "$fixture/common/release-evidence/fixture-source.json"
printf 'platform evidence' > "$fixture/common/release-evidence/fixture-linux-amd64.json"
source_digest="sha256:$(sha256sum "$fixture/common/release-evidence/fixture-source.json" | awk '{print $1}')"
platform_digest="sha256:$(sha256sum "$fixture/common/release-evidence/fixture-linux-amd64.json" | awk '{print $1}')"
config_bytes='fixture config bytes'
layer_bytes='fixture compressed bytes'
plain_bytes='fixture uncompressed layer bytes'
config_sha="$(printf '%s' "$config_bytes" | sha256sum | awk '{print $1}')"
blob_sha="$(printf '%s' "$layer_bytes" | sha256sum | awk '{print $1}')"
diff_sha="$(printf '%s' "$plain_bytes" | sha256sum | awk '{print $1}')"
config_path="blobs/sha256/$config_sha"
layer_path="blobs/sha256/$blob_sha"
fixture_manifest="[{\"Config\":\"$config_path\",\"RepoTags\":[\"fixture:v1\"],\"Layers\":[\"$layer_path\"]}]"
mode=good

tar() {
  local selected member
  if [[ "$1" == -xO* ]]; then
    printf '%s' "$fixture_manifest"
    if [[ "$mode" == manifest-read ]]; then printf 'gzip: stdin: Cannot allocate memory\n' >&2; return 2; fi
    return 0
  fi
  if [[ "$1" == -t ]]; then
    printf 'manifest.json\n%s\n%s\n' "$config_path" "$layer_path"
    return 0
  fi
  if [[ "$1" == -tv ]]; then
    printf '%s\n' \
      '-rw-r--r-- 0/0 1 2026-01-01 00:00 manifest.json' \
      "-rw-r--r-- 0/0 1 2026-01-01 00:00 $config_path" \
      "-rw-r--r-- 0/0 1 2026-01-01 00:00 $layer_path"
    return 0
  fi
  if [[ "$*" != *' -T '* ]]; then
    printf '%s' "$fixture_manifest" > "$IMAGE_ARCHIVE_TEMP/files/manifest.json"
    printf 'manifest.json\n'
    if [[ "$mode" == manifest-extract ]]; then printf 'gzip: stdin: Cannot allocate memory\n' >&2; return 2; fi
    return 0
  fi
  selected="${!#}"
  while IFS= read -r member; do
    if [[ "$member" == "$config_path" ]]; then
      printf '%s' "$config_bytes" > "$IMAGE_ARCHIVE_TEMP/files/${member//\//_}"
      printf '%s\n' "${#config_bytes}" > "$IMAGE_ARCHIVE_TEMP/sizes/${member//\//_}"
      [[ "$mode" != config-digest ]] || printf 'tampered' >> "$IMAGE_ARCHIVE_TEMP/files/${member//\//_}"
    else
      printf '%s\n' "${#layer_bytes}" > "$IMAGE_ARCHIVE_TEMP/sizes/${member//\//_}"
      printf '%s\n' "$blob_sha" > "$IMAGE_ARCHIVE_TEMP/digests/${member//\//_}.raw"
      printf '%s\n' "$diff_sha" > "$IMAGE_ARCHIVE_TEMP/digests/${member//\//_}.diff"
      [[ "$mode" != blob-digest ]] || printf 'wrong-raw-sha\n' > "$IMAGE_ARCHIVE_TEMP/digests/${member//\//_}.raw"
      [[ "$mode" != diff-digest ]] || printf 'wrong-diff-sha\n' > "$IMAGE_ARCHIVE_TEMP/digests/${member//\//_}.diff"
    fi
    printf '%s\n' "$member"
  done < "$selected"
  if [[ "$mode" == layer-extract ]]; then printf 'gzip: stdin: Cannot allocate memory\ntar: Unexpected EOF in archive\n' >&2; return 2; fi
  if [[ "$mode" == decompress-read ]]; then printf '[image-archive][ERROR] layer stream/hash/decompression failed: %s (pipeline=1 raw=0)\ngzip: stdin: Cannot allocate memory\n' "$layer_path" >&2; return 2; fi
  return 0
}

gzip() {
  if [[ "$mode" == decompress-read ]]; then printf 'gzip: layer: Cannot allocate memory\n' >&2; return 1; fi
  if [[ "$mode" == diff-digest ]]; then printf 'wrong expanded bytes'; else printf '%s' "$plain_bytes"; fi
}

identity_jq() {
  local query="$*"
  case "$query" in
    *'-sce '*) printf '%s' "$fixture_manifest" ;;
    *'invalid image manifest'*) printf '%s\n%s\n' "$config_path" "$layer_path" ;;
    *'.Config'*) cat >/dev/null; printf '%s' "$config_path" ;;
    *'(.Layers // [])'*) cat >/dev/null; printf '["%s"]' "$layer_path" ;;
    *'.rootfs.diff_ids'*) printf '["sha256:%s"]' "$diff_sha" ;;
    *'.[0] | select'*) cat >/dev/null; printf 'sha256:%s' "$diff_sha" ;;
    *'.[0]'*) cat >/dev/null; printf '%s' "$layer_path" ;;
    *'length'*) cat >/dev/null; printf '1' ;;
    *'.[]'*) cat >/dev/null; printf '%s\n' "$layer_path" ;;
    *) printf 'unexpected mock jq call\n' >&2; return 97 ;;
  esac
}

contract_jq() {
  local query="$*"
  case "$query" in
    *'.manifests?'*) printf '%s' "$platform_digest" ;;
    *'.config.digest'*) printf 'sha256:%s' "$config_sha" ;;
    *'.layers[]?'*) printf '1' ;;
    *'(.Layers // [])'*)
      cat >/dev/null
      [[ "$mode" != manifest-json ]] || return 4
      if [[ "$mode" == layer-count ]]; then printf '2'; else printf '1'; fi
      ;;
    *) identity_jq "$@" ;;
  esac
}

docker() { return 1; }
fail() { printf '[FAIL] %s\n' "$*" >&2; errors=$((errors + 1)); }
ok() { printf '[ OK ] %s\n' "$*"; }
assert_has() { grep -F -- "$2" "$1" >/dev/null || { printf 'missing diagnostic: %s\n' "$2" >&2; cat "$1" >&2; exit 1; }; }
assert_not() { if grep -F -- "$2" "$1" >/dev/null; then printf 'unexpected diagnostic: %s\n' "$2" >&2; exit 1; fi; }

# The archive-manifest helper preserves native tar status, stdout, and stderr.
mode=manifest-read
status=0
image_archive_manifest "$archive" > "$fixture/read.out" 2> "$fixture/read.err" || status=$?
[[ "$status" == 2 ]]
assert_has "$fixture/read.err" 'Cannot allocate memory'
assert_has "$fixture/read.err" 'archive manifest read failed (exit 2)'

for mode in manifest-extract layer-extract config-digest blob-digest decompress-read diff-digest; do
  status=0
  image_archive_id_for_tag "$archive" fixture:v1 identity_jq > "$fixture/$mode.out" 2> "$fixture/$mode.err" || status=$?
  [[ "$status" == 1 && ! -s "$fixture/$mode.out" ]]
  case "$mode" in
    manifest-extract) diagnostic='archive manifest extraction failed' ;;
    layer-extract) diagnostic='archive config/layer extraction failed' ;;
    config-digest) diagnostic='archive config SHA-256 mismatch' ;;
    blob-digest) diagnostic='archive layer blob SHA-256 mismatch' ;;
    decompress-read) diagnostic='archive config/layer extraction failed' ;;
    diff-digest) diagnostic='archive layer diffID mismatch' ;;
  esac
  assert_has "$fixture/$mode.err" "$diagnostic"
  case "$mode" in
    *extract) assert_has "$fixture/$mode.err" 'Cannot allocate memory'; assert_not "$fixture/$mode.err" 'mismatch' ;;
    decompress-read)
      assert_has "$fixture/$mode.err" 'Cannot allocate memory'
      assert_has "$fixture/$mode.err" 'layer stream/hash/decompression failed'
      assert_not "$fixture/$mode.err" 'mismatch'
      ;;
  esac
done
mode=good
image_archive_id_for_tag "$archive" fixture:v1 identity_jq > "$fixture/good.out" 2> "$fixture/good.err"
[[ "$(cat "$fixture/good.out")" == "sha256:$config_sha" && ! -s "$fixture/good.err" ]]
[[ -z "$(find "$fixture/tmp" -mindepth 1 -print -quit)" ]]

JQ_BIN=contract_jq
# The caller is tested independently of the already-tested identity helper.
image_archive_id_for_tag() {
  if [[ "$mode" == identity-failure ]]; then release_metadata_error 'archive config/layer extraction failed (mock ENOMEM)'; return 1; fi
  printf 'sha256:%s' "$config_sha"
}
for mode in manifest-read manifest-json layer-count good lock-digest identity-failure; do
  errors=0
  locked_id="sha256:$config_sha"
  [[ "$mode" != lock-digest ]] || locked_id="sha256:$(printf 'a%.0s' {1..64})"
  require_image_contract "$archive" FIXTURE fixture:v1 fixture:v1 "$source_digest" "fixture@$source_digest" \
    "$platform_digest" "sha256:$config_sha" "$locked_id" fixture > "$fixture/contract-$mode.out" 2> "$fixture/contract-$mode.err"
  if [[ "$mode" == good ]]; then
    [[ "$errors" == 0 && ! -s "$fixture/contract-$mode.err" ]]
    assert_has "$fixture/contract-$mode.out" 'registry/archive contract'
  else
    [[ "$errors" == 1 ]]
    case "$mode" in
      manifest-read) diagnostic='archive manifest read failed'; assert_has "$fixture/contract-$mode.err" 'Cannot allocate memory'; assert_not "$fixture/contract-$mode.err" 'layer count differs' ;;
      manifest-json) diagnostic='archive manifest tag/layers are invalid'; assert_not "$fixture/contract-$mode.err" 'layer count differs' ;;
      layer-count) diagnostic='OCI descriptor/archive layer count differs' ;;
      lock-digest) diagnostic='archive image ID does not match lock' ;;
      identity-failure) diagnostic='archive tag/config identity verification failed'; assert_has "$fixture/contract-$mode.err" 'mock ENOMEM' ;;
    esac
    assert_has "$fixture/contract-$mode.err" "$diagnostic"
  fi
done
printf '%s\n' 'PASS: mock-only archive diagnostics; native ENOMEM/status preserved; extraction, config/blob/diffID failures rejected; success silent; read/parse/count/lock errors distinguished'
