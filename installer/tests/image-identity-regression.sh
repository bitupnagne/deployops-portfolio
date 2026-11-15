#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
. "$SCRIPT_DIR/lib/release-metadata.sh"
. "$SCRIPT_DIR/components/common.sh"
config="sha256:$(printf 'a%.0s' {1..64})"
platform="sha256:$(printf 'b%.0s' {1..64})"
index="sha256:$(printf 'c%.0s' {1..64})"
wrong="sha256:$(printf 'd%.0s' {1..64})"
mode=containerd
platform_id="$platform"
container_image="$index"
LOCK_GITLAB_CONFIG_DIGEST="$config"
LOCK_GITLAB_PLATFORM_DIGEST="$platform"
docker() {
  if [[ "$*" == 'image inspect --help' ]]; then
    [[ "$mode" != classic ]] && printf '%s\n' '--platform string' || printf '%s\n' '--format string'
    return 0
  fi
  if [[ "$1 $2" == 'image inspect' ]]; then
    local reference="${!#}"
    [[ "$reference" != "$wrong" ]] || return 1
    if [[ "$mode" == classic ]]; then
      [[ "$*" != *--platform* ]] || return 1
      printf 'linux|amd64|%s\n' "$config"
    else
      [[ "$*" == *'--platform linux/amd64'* ]] || { printf 'linux|amd64|%s\n' "$index"; return 0; }
      [[ "$mode" != unavailable ]] || return 1
      [[ "$mode" != arm ]] || { printf 'linux|arm64|%s\n' "$platform_id"; return 0; }
      printf 'linux|amd64|%s\n' "$platform_id"
    fi
    return 0
  fi
  if [[ "$1" == inspect ]]; then printf '%s\n' "$container_image"; return 0; fi
  return 1
}
reject() { if "$@"; then printf 'Unexpected identity acceptance: %s\n' "$*" >&2; exit 1; fi; }
image_matches_archive_id fixture:current "$config"
container_matches_archive_id fixture-container "$config"
platform_id="$wrong"
reject image_matches_archive_id fixture:current "$config"
reject container_matches_archive_id fixture-container "$config"
platform_id="$index"
reject image_matches_archive_id fixture:current "$config"
platform_id="$platform"
mode=unavailable
reject image_matches_archive_id fixture:current "$config"
mode=arm
reject image_matches_archive_id fixture:current "$config"
mode=containerd
container_image="$wrong"
reject container_matches_archive_id fixture-container "$config"
unset LOCK_GITLAB_CONFIG_DIGEST LOCK_GITLAB_PLATFORM_DIGEST
reject image_matches_archive_id fixture:current "$config"
mode=classic
container_image="$config"
image_matches_archive_id fixture:current "$config"
container_matches_archive_id fixture-container "$config"
reject image_matches_archive_id fixture:current "$wrong"
fixture="$(mktemp -d)"
trap 'rm -rf "$fixture"' EXIT
mkdir -p "$fixture/blobs/sha256"
printf 'actual layer bytes' > "$fixture/layer"
diff="$(sha256sum "$fixture/layer" | awk '{print $1}')"
gzip -n -c "$fixture/layer" > "$fixture/compressed"
blob="$(sha256sum "$fixture/compressed" | awk '{print $1}')"
cp "$fixture/compressed" "$fixture/blobs/sha256/$blob"
printf '{"rootfs":{"type":"layers","diff_ids":["sha256:%s"]}}' "$diff" > "$fixture/config"
config_sha="$(sha256sum "$fixture/config" | awk '{print $1}')"
cp "$fixture/config" "$fixture/blobs/sha256/$config_sha"
printf '[{"Config":"blobs/sha256/%s","RepoTags":["fixture:compressed"],"Layers":["blobs/sha256/%s"]}]' "$config_sha" "$blob" > "$fixture/manifest.json"
tar -cf "$fixture/good.tar" -C "$fixture" manifest.json blobs
[[ "$(image_archive_id_for_tag "$fixture/good.tar" fixture:compressed "$(command -v jq)")" == "sha256:$config_sha" ]]
# Docker's classic save format carries plain layer.tar entries; both its raw
# bytes and diffID must remain valid without materializing a second copy.
classic_diff="$(sha256sum "$fixture/layer" | awk '{print $1}')"
printf '{"rootfs":{"type":"layers","diff_ids":["sha256:%s"]}}' "$classic_diff" > "$fixture/classic-config.json"
classic_config_sha="$(sha256sum "$fixture/classic-config.json" | awk '{print $1}')"
mkdir -p "$fixture/classic/$classic_config_sha"
cp "$fixture/classic-config.json" "$fixture/classic/$classic_config_sha.json"
cp "$fixture/layer" "$fixture/classic/$classic_config_sha/layer.tar"
printf '[{"Config":"%s.json","RepoTags":["fixture:classic"],"Layers":["%s/layer.tar"]}]' \
  "$classic_config_sha" "$classic_config_sha" > "$fixture/classic/manifest.json"
tar -cf "$fixture/classic.tar" -C "$fixture/classic" manifest.json "$classic_config_sha.json" "$classic_config_sha"
[[ "$(image_archive_id_for_tag "$fixture/classic.tar" fixture:classic "$(command -v jq)")" == "sha256:$classic_config_sha" ]]
printf 'tampered' >> "$fixture/blobs/sha256/$blob"
tar -cf "$fixture/bad.tar" -C "$fixture" manifest.json blobs
reject image_archive_id_for_tag "$fixture/bad.tar" fixture:compressed "$(command -v jq)"
printf 'different valid gzip layer' | gzip -n -c > "$fixture/other"
other_blob="$(sha256sum "$fixture/other" | awk '{print $1}')"
rm -f "$fixture/blobs/sha256/$blob"
cp "$fixture/other" "$fixture/blobs/sha256/$other_blob"
printf '[{"Config":"blobs/sha256/%s","RepoTags":["fixture:compressed"],"Layers":["blobs/sha256/%s"]}]' "$config_sha" "$other_blob" > "$fixture/manifest.json"
tar -cf "$fixture/wrong-diff.tar" -C "$fixture" manifest.json blobs
reject image_archive_id_for_tag "$fixture/wrong-diff.tar" fixture:compressed "$(command -v jq)"
# A correctly named OCI blob with a truncated gzip member must fail the
# streaming decompressor, even though its raw blob digest matches its path.
gzip -n -c "$fixture/layer" > "$fixture/truncated-gzip"
truncate -s -2 "$fixture/truncated-gzip"
truncated_blob="$(sha256sum "$fixture/truncated-gzip" | awk '{print $1}')"
rm -f "$fixture/blobs/sha256/$other_blob"
cp "$fixture/truncated-gzip" "$fixture/blobs/sha256/$truncated_blob"
printf '{"rootfs":{"type":"layers","diff_ids":["sha256:%s"]}}' "$diff" > "$fixture/config"
config_sha="$(sha256sum "$fixture/config" | awk '{print $1}')"
cp "$fixture/config" "$fixture/blobs/sha256/$config_sha"
printf '[{"Config":"blobs/sha256/%s","RepoTags":["fixture:truncated"],"Layers":["blobs/sha256/%s"]}]' \
  "$config_sha" "$truncated_blob" > "$fixture/manifest.json"
tar -cf "$fixture/truncated-gzip.tar" -C "$fixture" manifest.json blobs
if image_archive_id_for_tag "$fixture/truncated-gzip.tar" fixture:truncated "$(command -v jq)" >"$fixture/truncated.out" 2>"$fixture/truncated.err"; then
  printf 'Unexpected acceptance of truncated gzip layer\n' >&2
  exit 1
fi
grep -F 'archive config/layer extraction failed' "$fixture/truncated.err" >/dev/null
grep -F 'gzip:' "$fixture/truncated.err" >/dev/null
cp "$fixture/good.tar" "$fixture/duplicate.tar"
tar -rf "$fixture/duplicate.tar" -C "$fixture" manifest.json
reject image_archive_id_for_tag "$fixture/duplicate.tar" fixture:compressed "$(command -v jq)"
printf 'unreferenced payload' > "$fixture/unreferenced-extra"
cp "$fixture/good.tar" "$fixture/unreferenced-extra.tar"
tar -rf "$fixture/unreferenced-extra.tar" -C "$fixture" unreferenced-extra
reject image_archive_id_for_tag "$fixture/unreferenced-extra.tar" fixture:compressed "$(command -v jq)"
mkdir -p "$fixture/unreferenced-blob/blobs/sha256"
printf 'valid-path but undeclared' > "$fixture/unreferenced-blob/content"
unreferenced_blob_sha="$(sha256sum "$fixture/unreferenced-blob/content" | awk '{print $1}')"
cp "$fixture/unreferenced-blob/content" "$fixture/unreferenced-blob/blobs/sha256/$unreferenced_blob_sha"
cp "$fixture/good.tar" "$fixture/unreferenced-blob.tar"
tar -rf "$fixture/unreferenced-blob.tar" -C "$fixture/unreferenced-blob" "blobs/sha256/$unreferenced_blob_sha"
[[ "$(image_archive_id_for_tag "$fixture/unreferenced-blob.tar" fixture:compressed "$(command -v jq)")" == "sha256:$config_sha" ]]
printf 'altered after digest name was chosen' > "$fixture/unreferenced-blob/blobs/sha256/$unreferenced_blob_sha"
cp "$fixture/good.tar" "$fixture/wrong-orphan-digest.tar"
tar -rf "$fixture/wrong-orphan-digest.tar" -C "$fixture/unreferenced-blob" "blobs/sha256/$unreferenced_blob_sha"
reject image_archive_id_for_tag "$fixture/wrong-orphan-digest.tar" fixture:compressed "$(command -v jq)"
cp "$fixture/unreferenced-extra.tar" "$fixture/duplicate-extra.tar"
tar -rf "$fixture/duplicate-extra.tar" -C "$fixture" unreferenced-extra
reject image_archive_id_for_tag "$fixture/duplicate-extra.tar" fixture:compressed "$(command -v jq)"
mkdir "$fixture/duplicate-metadata-build"
tar -xf "$fixture/good.tar" -C "$fixture/duplicate-metadata-build"
printf '{"fixture":{"v1":"abc"}}' > "$fixture/duplicate-metadata-build/repositories"
printf '{"schemaVersion":2,"manifests":[]}' > "$fixture/duplicate-metadata-build/index.json"
tar -cf "$fixture/duplicate-metadata.tar" -C "$fixture/duplicate-metadata-build" manifest.json blobs repositories index.json
tar -rf "$fixture/duplicate-metadata.tar" -C "$fixture/duplicate-metadata-build" repositories index.json
reject image_archive_id_for_tag "$fixture/duplicate-metadata.tar" fixture:compressed "$(command -v jq)"
ln -s outside-config "$fixture/unreferenced-symlink"
cp "$fixture/good.tar" "$fixture/unreferenced-symlink.tar"
tar -rf "$fixture/unreferenced-symlink.tar" -C "$fixture" unreferenced-symlink
reject image_archive_id_for_tag "$fixture/unreferenced-symlink.tar" fixture:compressed "$(command -v jq)"
mkdir "$fixture/hardlink-build"
tar -xf "$fixture/good.tar" -C "$fixture/hardlink-build"
ln "$fixture/hardlink-build/blobs/sha256/$config_sha" "$fixture/hardlink-build/unreferenced-hardlink"
tar -cf "$fixture/unreferenced-hardlink.tar" -C "$fixture/hardlink-build" manifest.json blobs unreferenced-hardlink
reject image_archive_id_for_tag "$fixture/unreferenced-hardlink.tar" fixture:compressed "$(command -v jq)"
mkfifo "$fixture/unreferenced-fifo"
cp "$fixture/good.tar" "$fixture/unreferenced-fifo.tar"
tar -rf "$fixture/unreferenced-fifo.tar" -C "$fixture" unreferenced-fifo
reject image_archive_id_for_tag "$fixture/unreferenced-fifo.tar" fixture:compressed "$(command -v jq)"
cp "$fixture/good.tar" "$fixture/traversal.tar"
tar --transform='s|unreferenced-extra|../escape|' -rf "$fixture/traversal.tar" -C "$fixture" unreferenced-extra
reject image_archive_id_for_tag "$fixture/traversal.tar" fixture:compressed "$(command -v jq)"
mkdir "$fixture/link-build" "$fixture/scratch"
tar -xf "$fixture/good.tar" -C "$fixture/link-build"
cp "$fixture/link-build/blobs/sha256/$config_sha" "$fixture/outside-config"
rm "$fixture/link-build/blobs/sha256/$config_sha"
ln -s "$fixture/outside-config" "$fixture/link-build/blobs/sha256/$config_sha"
tar -cf "$fixture/symlink.tar" -C "$fixture/link-build" manifest.json blobs
reject image_archive_id_for_tag "$fixture/symlink.tar" fixture:compressed "$(command -v jq)"
cp "$fixture/good.tar" "$fixture/duplicate-symlink.tar"
tar -rf "$fixture/duplicate-symlink.tar" -C "$fixture/link-build" "blobs/sha256/$config_sha"
reject image_archive_id_for_tag "$fixture/duplicate-symlink.tar" fixture:compressed "$(command -v jq)"
rm "$fixture/link-build/blobs/sha256/$config_sha"
cp "$fixture/outside-config" "$fixture/link-build/original"
ln "$fixture/link-build/original" "$fixture/link-build/blobs/sha256/$config_sha"
tar -cf "$fixture/hardlink.tar" -C "$fixture/link-build" original manifest.json blobs
reject image_archive_id_for_tag "$fixture/hardlink.tar" fixture:compressed "$(command -v jq)"
rm "$fixture/link-build/blobs/sha256/$config_sha"
mkfifo "$fixture/link-build/blobs/sha256/$config_sha"
tar -cf "$fixture/fifo.tar" -C "$fixture/link-build" manifest.json blobs
TMPDIR="$fixture/scratch" reject image_archive_id_for_tag "$fixture/fifo.tar" fixture:compressed "$(command -v jq)"
[[ -z "$(find "$fixture/scratch" -mindepth 1 -print -quit)" ]]
[[ "$(sha256sum "$fixture/outside-config" | awk '{print $1}')" == "$config_sha" ]]
tar() {
  [[ "$*" != *--to-command* ]] || printf 'pass\n' >> "$fixture/tar-passes"
  command tar "$@"
  local status=$?
  if [[ "$*" == *' -T '* ]]; then
    du -sb "$TMPDIR" | awk '{print $1}' > "$fixture/temp-scratch-bytes"
    find "$TMPDIR" -type f | wc -l > "$fixture/temp-scratch-files"
    find "$IMAGE_ARCHIVE_TEMP/files" -type f -printf '%s\n' | sort -nr > "$fixture/temp-file-sizes"
    find "$IMAGE_ARCHIVE_TEMP/files" -type f -printf '%f\n' > "$fixture/temp-file-names"
  fi
  return "$status"
}
export TMPDIR="$fixture/scratch"
dd if=/dev/urandom of="$fixture/large-layer" bs=1048576 count=16 status=none
large_diff="$(sha256sum "$fixture/large-layer" | awk '{print $1}')"
gzip -n -c "$fixture/large-layer" > "$fixture/large-blob"
large_blob="$(sha256sum "$fixture/large-blob" | awk '{print $1}')"
mkdir -p "$fixture/large/blobs/sha256"
cp "$fixture/large-blob" "$fixture/large/blobs/sha256/$large_blob"
printf '{"rootfs":{"type":"layers","diff_ids":["sha256:%s"]}}' "$large_diff" > "$fixture/large/config"
large_config_sha="$(sha256sum "$fixture/large/config" | awk '{print $1}')"
cp "$fixture/large/config" "$fixture/large/blobs/sha256/$large_config_sha"
printf '[{"Config":"blobs/sha256/%s","RepoTags":["fixture:large"],"Layers":["blobs/sha256/%s"]}]' \
  "$large_config_sha" "$large_blob" > "$fixture/large/manifest.json"
printf '{"fixture":{"large":"%s"}}' "${large_blob:0:12}" > "$fixture/large/repositories"
printf '{"schemaVersion":2,"mediaType":"application/vnd.oci.image.index.v1+json","manifests":[]}' > "$fixture/large/oci-nested-index"
nested_index_sha="$(sha256sum "$fixture/large/oci-nested-index" | awk '{print $1}')"
cp "$fixture/large/oci-nested-index" "$fixture/large/blobs/sha256/$nested_index_sha"
nested_index_size="$(wc -c < "$fixture/large/oci-nested-index" | tr -d ' ')"
printf '{"schemaVersion":2,"mediaType":"application/vnd.oci.image.index.v1+json","manifests":[{"mediaType":"application/vnd.oci.image.index.v1+json","digest":"sha256:%s","size":%s}]}' \
  "$nested_index_sha" "$nested_index_size" > "$fixture/large/index.json"
printf '{"imageLayoutVersion":"1.0.0"}' > "$fixture/large/oci-layout"
tar -cf "$fixture/large.tar" -C "$fixture/large" manifest.json blobs repositories index.json oci-layout
: > "$fixture/tar-passes"
[[ "$(image_archive_id_for_tag "$fixture/large.tar" fixture:large "$(command -v jq)")" == "sha256:$large_config_sha" ]]
[[ "$(wc -l < "$fixture/tar-passes")" -eq 2 ]]
mkdir "$fixture/oci-missing-target" "$fixture/oci-wrong-size" "$fixture/oci-missing-pair" "$fixture/oci-bad-layout"
cp -a "$fixture/large/." "$fixture/oci-missing-target/"
cp -a "$fixture/large/." "$fixture/oci-wrong-size/"
cp -a "$fixture/large/." "$fixture/oci-missing-pair/"
cp -a "$fixture/large/." "$fixture/oci-bad-layout/"
missing_digest="$(printf 'e%.0s' {1..64})"
printf '{"schemaVersion":2,"mediaType":"application/vnd.oci.image.index.v1+json","manifests":[{"mediaType":"application/vnd.oci.image.index.v1+json","digest":"sha256:%s","size":1}]}' \
  "$missing_digest" > "$fixture/oci-missing-target/index.json"
descriptor_size="$nested_index_size"
wrong_size=$((descriptor_size + 1))
printf '{"schemaVersion":2,"mediaType":"application/vnd.oci.image.index.v1+json","manifests":[{"mediaType":"application/vnd.oci.image.index.v1+json","digest":"sha256:%s","size":%s}]}' \
  "$nested_index_sha" "$wrong_size" > "$fixture/oci-wrong-size/index.json"
rm "$fixture/oci-missing-pair/oci-layout"
printf '{"imageLayoutVersion":"1.1.0"}' > "$fixture/oci-bad-layout/oci-layout"
for oci_case in missing-target wrong-size missing-pair bad-layout; do
  tar -cf "$fixture/oci-$oci_case.tar" -C "$fixture/oci-$oci_case" manifest.json blobs repositories index.json $( [[ "$oci_case" != missing-pair ]] && printf oci-layout )
  reject image_archive_id_for_tag "$fixture/oci-$oci_case.tar" fixture:large "$(command -v jq)"
done
temp_max_bytes="$(head -n1 "$fixture/temp-file-sizes")"
[[ "$temp_max_bytes" -lt 2048 ]]
temp_total_bytes="$(cat "$fixture/temp-scratch-bytes")"
temp_scratch_files="$(cat "$fixture/temp-scratch-files")"
[[ "$temp_total_bytes" -lt 131072 ]]
[[ "$(wc -l < "$fixture/temp-file-names")" -eq 5 ]]
grep -Fxq 'manifest.json' "$fixture/temp-file-names"
grep -Fxq "blobs_sha256_$large_config_sha" "$fixture/temp-file-names"
! grep -Fxq "blobs_sha256_$large_blob" "$fixture/temp-file-names"
grep -Fxq 'repositories' "$fixture/temp-file-names"
grep -Fxq 'index.json' "$fixture/temp-file-names"
grep -Fxq 'oci-layout' "$fixture/temp-file-names"
mkdir -p "$fixture/large-classic/$large_config_sha"
cp "$fixture/large/config" "$fixture/large-classic/$large_config_sha.json"
cp "$fixture/large-layer" "$fixture/large-classic/$large_config_sha/layer.tar"
printf '[{"Config":"%s.json","RepoTags":["fixture:large-classic"],"Layers":["%s/layer.tar"]}]' \
  "$large_config_sha" "$large_config_sha" > "$fixture/large-classic/manifest.json"
tar -cf "$fixture/large-classic.tar" -C "$fixture/large-classic" manifest.json "$large_config_sha.json" "$large_config_sha"
: > "$fixture/tar-passes"
[[ "$(image_archive_id_for_tag "$fixture/large-classic.tar" fixture:large-classic "$(command -v jq)")" == "sha256:$large_config_sha" ]]
classic_temp_total_bytes="$(cat "$fixture/temp-scratch-bytes")"
[[ "$classic_temp_total_bytes" -lt 131072 ]]
! grep -Fxq "${large_config_sha}_layer.tar" "$fixture/temp-file-names"
[[ "$(wc -l < "$fixture/tar-passes")" -eq 2 ]]
[[ -z "$(find "$TMPDIR" -mindepth 1 -print -quit)" ]]
unset -f tar
printf '%s\n' 'PASS: classic/config and containerd/platform identities, drift, wrong architecture, missing platform, immutable container reference'
printf '%s\n' 'PASS: compressed OCI layer blob SHA and uncompressed config diffID binding; tampering rejected'
printf '%s\n' 'PASS: duplicate archive member, symlink, hardlink, FIFO rejected; outside file intact and failure temporary files removed'
printf 'PASS: 16 MiB compressed/classic layers are streamed; whole temp scratch peaks at %s/%s bytes (%s files), max member %s bytes\n' \
  "$temp_total_bytes" "$classic_temp_total_bytes" "$temp_scratch_files" "$temp_max_bytes"
