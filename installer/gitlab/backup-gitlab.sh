#!/usr/bin/env bash
# Export the running Omnibus GitLab version, complete configuration and native data backup.
set -Eeuo pipefail
umask 077
CONTAINER="worksystem-gitlab"; OUTPUT=""; FORCE=0
BACKUP_PATH="/var/opt/gitlab/backups"
usage(){ cat <<'EOF'
Usage: backup-gitlab.sh --output DIR [--container NAME] [--backup-path DIR] [--force]
Run as root. --backup-path must match a non-default GitLab backup_path, if configured.
Includes the running image, complete /etc/gitlab (keys/TLS/SSH), Omnibus ENV and native backup.
External object storage and files referenced outside /etc/gitlab need separate backup.
--force only replaces an existing backup artifact; it never changes the running container.
EOF
}
fail(){ echo "[ERROR] $*" >&2; exit 1; }
while [[ $# -gt 0 ]]; do
  case "$1" in
    --container) CONTAINER="${2:?missing value}"; shift 2;;
    --output) OUTPUT="${2:?missing value}"; shift 2;;
    --backup-path) BACKUP_PATH="${2:?missing value}"; shift 2;;
    --force) FORCE=1; shift;;
    -h|--help) usage; exit 0;;
    *) usage >&2; fail "unknown option: $1";;
  esac
done
[[ -n "$OUTPUT" ]] || { usage >&2; exit 1; }
for tool in docker realpath; do command -v "$tool" >/dev/null || fail "$tool is required"; done
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/recovery-tools.sh"
GITLAB_RECOVERY_JQ="$(gitlab_recovery_jq)" || exit 1
jq() { "$GITLAB_RECOVERY_JQ" "$@"; }
[[ "$BACKUP_PATH" = /* && "$BACKUP_PATH" != *$'\n'* ]] || fail "backup path must be absolute"
[[ ! -L "$OUTPUT" ]] || fail "output must not be a symlink"
OUTPUT="$(realpath -m "$OUTPUT")"
if [[ -e "$OUTPUT" ]]; then
  [[ "$FORCE" == 1 && -d "$OUTPUT" && -f "$OUTPUT/metadata.json" ]] \
    || fail "output exists; --force only replaces a previous GitLab artifact"
  jq -e '.format == "deployops-gitlab" and .schema == 2' "$OUTPUT/metadata.json" >/dev/null \
    || fail "output is not a supported GitLab artifact"
fi
mkdir -p "$(dirname "$OUTPUT")"
tmp="$(mktemp -d "$(dirname "$OUTPUT")/.gitlab-backup.XXXXXXXX")"
trap 'rm -rf -- "$tmp"' EXIT
docker container inspect "$CONTAINER" > "$tmp/container-inspect.json" || fail "GitLab container not found"
jq -e '.[0].State.Running == true' "$tmp/container-inspect.json" >/dev/null || fail "GitLab must be running"
image_id="$(jq -er '.[0].Image' "$tmp/container-inspect.json")"
[[ "$image_id" =~ ^sha256:[0-9a-f]{64}$ ]] || fail "invalid running image ID"
docker image inspect "$image_id" > "$tmp/image-inspect.json" || fail "running image not available"
docker exec "$CONTAINER" cat /opt/gitlab/version-manifest.txt > "$tmp/version-manifest.txt" \
  || fail "cannot read GitLab package version"
package="$(awk '$1 ~ /^gitlab-(ce|ee)$/ {print $1; exit}' "$tmp/version-manifest.txt")"
version="$(awk '$1 ~ /^gitlab-(ce|ee)$/ {print $2; exit}' "$tmp/version-manifest.txt")"
[[ "$package" =~ ^gitlab-(ce|ee)$ && "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.+~-][a-zA-Z0-9.+~-]+)?$ ]] \
  || fail "GitLab package version/type cannot be identified"
mkdir "$tmp/config"
docker cp -a "$CONTAINER:/etc/gitlab/." "$tmp/config/" >/dev/null || fail "complete GitLab config copy failed"
for name in gitlab.rb gitlab-secrets.json; do
  [[ -f "$tmp/config/$name" && ! -L "$tmp/config/$name" ]] \
    || fail "required configuration missing: $name"
done
jq -e 'type == "object" and length > 0' "$tmp/config/gitlab-secrets.json" >/dev/null \
  || fail "GitLab secrets JSON is invalid"
# Omnibus ENV configuration is NOT persisted in gitlab.rb by GitLab.
jq -r '([.[0].Config.Env[]? | select(startswith("GITLAB_OMNIBUS_CONFIG=")) | ltrimstr("GITLAB_OMNIBUS_CONFIG=")][0] // "")' \
  "$tmp/container-inspect.json" > "$tmp/omnibus-config.rb"
backup_id="deployops_$(date -u +%Y%m%dT%H%M%SZ)_$$"
backup_name="${backup_id}_gitlab_backup.tar"
echo "Creating GitLab native backup in $CONTAINER..."
docker exec "$CONTAINER" gitlab-backup create STRATEGY=copy "BACKUP=$backup_id" >/dev/null \
  || fail "gitlab-backup create failed"
docker cp "$CONTAINER:${BACKUP_PATH%/}/$backup_name" "$tmp/$backup_name" >/dev/null \
  || fail "exact native backup not found; check --backup-path"
[[ -s "$tmp/$backup_name" ]] || fail "native backup is empty"
# Save the actual running image, not a mutable tag that may have moved since startup.
docker save -o "$tmp/image.tar" "$image_id" || fail "running GitLab image export failed"
jq -n --arg container "$CONTAINER" --arg image_id "$image_id" \
  --arg image "$(jq -r '.[0].Config.Image' "$tmp/container-inspect.json")" \
  --arg package "$package" --arg version "$version" --arg backup_tar "$backup_name" \
  --arg backup_path "$BACKUP_PATH" --arg created_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  '{format:"deployops-gitlab",schema:2,container:$container,image:$image,image_id:$image_id,
    package:$package,version:$version,backup_tar:$backup_tar,backup_path:$backup_path,created_at:$created_at}' \
  > "$tmp/metadata.json"
chmod 700 "$tmp" "$tmp/config"
chmod 600 "$tmp"/*.* "$tmp/config/gitlab.rb" "$tmp/config/gitlab-secrets.json"
if [[ -d "$OUTPUT" ]]; then
  # Keep the previous complete artifact until the replacement is published successfully.
  previous="${OUTPUT}.previous.$(date -u +%Y%m%dT%H%M%SZ).$$"
  [[ ! -e "$previous" ]] || fail "previous backup path already exists"
  mv -- "$OUTPUT" "$previous"
  if ! mv -- "$tmp" "$OUTPUT"; then
    mv -- "$previous" "$OUTPUT"
    fail "could not publish replacement backup; previous artifact retained"
  fi
  echo "Previous artifact retained: $previous"
else
  mv -- "$tmp" "$OUTPUT"
fi
echo "GitLab artifact created: $OUTPUT ($package $version)"
echo "Keep this entire directory on separate backup storage; it contains private keys."
