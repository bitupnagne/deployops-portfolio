#!/usr/bin/env bash
# GNU tar --to-command callback: archive paths never become filesystem paths.
set -euo pipefail
[[ "${TAR_FILETYPE:-}" == f ]] || exit 1
[[ "${TAR_FILENAME:-}" =~ ^(manifest\.json|repositories|index\.json|oci-layout|[0-9a-f]{64}\.json|[0-9a-f]{64}/layer\.tar|blobs/sha256/[0-9a-f]{64})$ ]] || exit 1
case "$TAR_FILENAME" in
  repositories|index.json|oci-layout)
    metadata_size="${TAR_SIZE:-}"
    [[ "$metadata_size" =~ ^[0-9]+$ && "${#metadata_size}" -le 7 ]] || {
      printf '[image-archive][ERROR] archive metadata exceeds 1 MiB: %s (size: %s)\n' "$TAR_FILENAME" "${TAR_SIZE:-unknown}" >&2
      exit 1
    }
    (( 10#$metadata_size <= 1048576 )) || {
      printf '[image-archive][ERROR] archive metadata exceeds 1 MiB: %s (size: %s)\n' "$TAR_FILENAME" "$metadata_size" >&2
      exit 1
    }
    ;;
esac
grep -Fxq -- "$TAR_FILENAME" "$IMAGE_ARCHIVE_TEMP/members" || exit 1
member_key="${TAR_FILENAME//\//_}"
# A repeated archive member must fail even if its bytes happen to be identical.
mkdir -- "$IMAGE_ARCHIVE_TEMP/claims/$member_key" || exit 1
if [[ "$TAR_FILENAME" == blobs/sha256/* ]] && grep -Fxq -- "$TAR_FILENAME" "$IMAGE_ARCHIVE_TEMP/hash-blobs"; then
  blob_size="${TAR_SIZE:-}"
  [[ "$blob_size" =~ ^[0-9]+$ && "${#blob_size}" -le 20 ]] || {
    printf '[image-archive][ERROR] invalid OCI blob size: %s\n' "$TAR_FILENAME" >&2
    exit 1
  }
  # Keep the exact decimal size as text; shell arithmetic could overflow.
  printf '%s\n' "$blob_size" > "$IMAGE_ARCHIVE_TEMP/sizes/$member_key" || exit 1
fi
if [[ -f "$IMAGE_ARCHIVE_TEMP/layers" ]] && grep -Fxq -- "$TAR_FILENAME" "$IMAGE_ARCHIVE_TEMP/layers"; then
  # Keep Docker save layers off disk. The FIFO gives the original archive bytes
  # to sha256sum while gzip streams the uncompressed layer to its diffID hash.
  # gzip -dcf deliberately accepts classic uncompressed layer.tar members too.
  fifo="$IMAGE_ARCHIVE_TEMP/digests/$member_key.raw.pipe"
  raw_file="$IMAGE_ARCHIVE_TEMP/digests/$member_key.raw"
  diff_file="$IMAGE_ARCHIVE_TEMP/digests/$member_key.diff"
  mkfifo -- "$fifo" || exit 1
  cleanup_layer_stream() {
    local status=$?
    trap - EXIT HUP INT TERM
    if [[ -n "${raw_pid:-}" ]] && kill -0 "$raw_pid" 2>/dev/null; then
      kill "$raw_pid" 2>/dev/null || true
      wait "$raw_pid" 2>/dev/null || true
    fi
    rm -f -- "$fifo"
    exit "$status"
  }
  trap cleanup_layer_stream EXIT
  trap 'exit 129' HUP
  trap 'exit 130' INT
  trap 'exit 143' TERM
  sha256sum < "$fifo" > "$raw_file" &
  raw_pid=$!
  set +e
  tee -- "$fifo" | gzip -dcf | sha256sum > "$diff_file"
  pipeline_status=$?
  wait "$raw_pid"
  raw_status=$?
  set -e
  rm -f -- "$fifo"
  if (( pipeline_status != 0 || raw_status != 0 )); then
    printf '[image-archive][ERROR] layer stream/hash/decompression failed: %s (pipeline=%s raw=%s)\n' \
      "$TAR_FILENAME" "$pipeline_status" "$raw_status" >&2
    exit 1
  fi
  awk '{print $1}' "$raw_file" > "$raw_file.digest" || exit 1
  awk '{print $1}' "$diff_file" > "$diff_file.digest" || exit 1
  mv -- "$raw_file.digest" "$raw_file" || exit 1
  mv -- "$diff_file.digest" "$diff_file" || exit 1
  exit 0
fi
if [[ "$TAR_FILENAME" == blobs/sha256/* ]] \
    && grep -Fxq -- "$TAR_FILENAME" "$IMAGE_ARCHIVE_TEMP/hash-blobs" \
    && ! grep -Fxq -- "$TAR_FILENAME" "$IMAGE_ARCHIVE_TEMP/config-member"; then
  sha256sum | awk '{print $1}' > "$IMAGE_ARCHIVE_TEMP/digests/$member_key.raw" || exit 1
  exit 0
fi
set -o noclobber
cat > "$IMAGE_ARCHIVE_TEMP/files/$member_key"
