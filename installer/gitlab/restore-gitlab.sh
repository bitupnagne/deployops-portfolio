#!/usr/bin/env bash
# Restore into a NEW empty target using the original immutable GitLab image.
set -Eeuo pipefail
umask 077
CONTAINER="worksystem-gitlab"; ARTIFACT=""; DATA="/srv/gitlab"; IMAGE=""
HTTP_PORT="8929"; SSH_PORT="0"; EXTERNAL_URL=""; WAIT_ATTEMPTS=120; NETWORK=""
usage(){ cat <<'EOF'
Usage: restore-gitlab.sh --artifact DIR [options]
  --container NAME     NEW container name (default: worksystem-gitlab)
  --data DIR           NEW empty bind-mount root (default: /srv/gitlab)
  --http-port PORT     published HTTP port (default: 8929)
  --ssh-port 0         compatibility option; SSH publishing is disabled
  --external-url URL   optional replacement URL; otherwise preserve backed-up configuration
  --image IMAGE        optional local reference; must match the backed-up immutable image ID
  --wait-attempts N    startup checks every 5 seconds (default: 120)
  --network NAME       optional Docker network (use an isolated network for drills)
  --restore-config     compatibility option: complete config is ALWAYS restored
  --force              compatibility option: does NOT permit existing containers/data
Run as root. Existing containers and populated data directories are never removed or reused.
Use separate target ports and an isolated network for drills; restored jobs/hooks can contact production.
External object storage or files outside /etc/gitlab must be restored separately first.
EOF
}
fail(){ echo "[ERROR] $*" >&2; exit 1; }
while [[ $# -gt 0 ]]; do
  case "$1" in
    --artifact) ARTIFACT="${2:?missing value}"; shift 2;;
    --container) CONTAINER="${2:?missing value}"; shift 2;;
    --data) DATA="${2:?missing value}"; shift 2;;
    --image) IMAGE="${2:?missing value}"; shift 2;;
    --http-port) HTTP_PORT="${2:?missing value}"; shift 2;;
    --ssh-port) SSH_PORT="${2:?missing value}"; shift 2;;
    --external-url) EXTERNAL_URL="${2:?missing value}"; shift 2;;
    --wait-attempts) WAIT_ATTEMPTS="${2:?missing value}"; shift 2;;
    --network) NETWORK="${2:?missing value}"; shift 2;;
    --restore-config|--force) shift;;
    -h|--help) usage; exit 0;;
    *) usage >&2; fail "unknown option: $1";;
  esac
done
[[ -n "$ARTIFACT" ]] || { usage >&2; exit 1; }
for tool in docker realpath; do command -v "$tool" >/dev/null || fail "$tool is required"; done
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/recovery-tools.sh"
GITLAB_RECOVERY_JQ="$(gitlab_recovery_jq)" || exit 1
jq() { "$GITLAB_RECOVERY_JQ" "$@"; }
[[ ! -L "$DATA" ]] || fail "target data root must not be a symlink"
ARTIFACT="$(realpath -m "$ARTIFACT")"; DATA="$(realpath -m "$DATA")"
[[ -f "$ARTIFACT/metadata.json" ]] || fail "metadata.json missing; create a current GitLab backup first"
jq -e '.format == "deployops-gitlab" and .schema == 2' "$ARTIFACT/metadata.json" >/dev/null \
  || fail "unsupported GitLab artifact format"
backup_tar="$(jq -er '.backup_tar | strings' "$ARTIFACT/metadata.json")"
image_id="$(jq -er '.image_id | strings' "$ARTIFACT/metadata.json")"
package="$(jq -er '.package | strings' "$ARTIFACT/metadata.json")"
version="$(jq -er '.version | strings' "$ARTIFACT/metadata.json")"
[[ "$backup_tar" =~ ^[a-zA-Z0-9_.-]+_gitlab_backup\.tar$ && "$backup_tar" != *..* ]] \
  || fail "invalid BACKUP_TAR in metadata"
[[ "$image_id" =~ ^sha256:[0-9a-f]{64}$ && "$package" =~ ^gitlab-(ce|ee)$ \
   && "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.+~-][a-zA-Z0-9.+~-]+)?$ ]] || fail "invalid source image/version metadata"
for file in "$backup_tar" image.tar config/gitlab.rb config/gitlab-secrets.json omnibus-config.rb version-manifest.txt; do
  [[ -f "$ARTIFACT/$file" && ! -L "$ARTIFACT/$file" ]] || fail "required backup file missing: $file"
done
[[ -s "$ARTIFACT/$backup_tar" && -s "$ARTIFACT/image.tar" ]] || fail "empty backup payload"
jq -e 'type == "object" and length > 0' "$ARTIFACT/config/gitlab-secrets.json" >/dev/null || fail "invalid GitLab secrets"
[[ "$(awk '$1 ~ /^gitlab-(ce|ee)$/ {print $1 " " $2; exit}' "$ARTIFACT/version-manifest.txt")" == "$package $version" ]] \
  || fail "source package version does not match metadata"
[[ "$SSH_PORT" == 0 ]] || fail "SSH publishing is disabled; use HTTP Git"
for port in "$HTTP_PORT"; do
  [[ "$port" =~ ^[0-9]{1,5}$ ]] && ((10#$port >= 1 && 10#$port <= 65535)) || fail "invalid port"
done
[[ "$WAIT_ATTEMPTS" =~ ^[0-9]+$ ]] && ((WAIT_ATTEMPTS >= 1 && WAIT_ATTEMPTS <= 720)) || fail "invalid wait attempts"
if [[ -n "$EXTERNAL_URL" ]]; then
  [[ "$EXTERNAL_URL" =~ ^https?://[a-zA-Z0-9._:-]+/?$ ]] || fail "external URL must be an HTTP(S) origin"
fi
docker container inspect "$CONTAINER" >/dev/null 2>&1 && fail "container already exists; choose a NEW target"
if [[ -e "$DATA" ]]; then
  [[ -d "$DATA" && ! -L "$DATA" ]] || fail "target data root must be a directory"
  [[ -z "$(find "$DATA" -mindepth 1 -maxdepth 1 -print -quit)" ]] || fail "target data directory must be EMPTY (even with --force)"
fi
[[ "$DATA/" != "$ARTIFACT/"* && "$ARTIFACT/" != "$DATA/"* ]] || fail "artifact and target directories must be separate"
docker load -i "$ARTIFACT/image.tar" >/dev/null || fail "docker image load failed"
docker image inspect "$image_id" >/dev/null || fail "backed-up immutable image is not available"
if [[ -n "$IMAGE" ]]; then
  [[ "$(docker image inspect -f '{{.Id}}' "$IMAGE")" == "$image_id" ]] \
    || fail "--image differs from the backed-up image; restore the ORIGINAL version/type first"
fi
mkdir -p "$DATA/config" "$DATA/logs" "$DATA/data"
chmod 700 "$DATA"
cp -a "$ARTIFACT/config/." "$DATA/config/" || fail "full config restore failed"
chmod 600 "$DATA/config/gitlab-secrets.json"
# GitLab evaluates Omnibus ENV before gitlab.rb. Preserve that order in a self-contained file.
config_tmp="$(mktemp "$DATA/config/.restore-rb.XXXXXXXX")"
cat "$ARTIFACT/omnibus-config.rb" > "$config_tmp"
printf '\n' >> "$config_tmp"
cat "$ARTIFACT/config/gitlab.rb" >> "$config_tmp"
printf '\n# DeployOps restore transport settings\n' >> "$config_tmp"
if [[ -n "$EXTERNAL_URL" ]]; then printf "external_url '%s'\n" "$EXTERNAL_URL" >> "$config_tmp"; fi
printf "gitlab_rails['gitlab_shell_ssh_port'] = 0\nnginx['listen_port'] = %s\ngitlab_rails['backup_path'] = '/var/opt/gitlab/backups'\n" \
  "$((10#$HTTP_PORT))" >> "$config_tmp"
chmod 600 "$config_tmp"; mv "$config_tmp" "$DATA/config/gitlab.rb"
run_args=(run -d --name "$CONTAINER" --restart no --shm-size 256m
  -e GITLAB_DISABLE_OPENSSH=true
  -p "${HTTP_PORT}:${HTTP_PORT}"
  -v "$DATA/config:/etc/gitlab:Z" -v "$DATA/logs:/var/log/gitlab:Z" -v "$DATA/data:/var/opt/gitlab:Z")
[[ -z "$NETWORK" ]] || run_args+=(--network "$NETWORK")
if [[ -n "$EXTERNAL_URL" ]]; then
  hostname="${EXTERNAL_URL#*://}"; hostname="${hostname%%[:/]*}"
  run_args+=(--hostname "$hostname")
elif [[ -f "$ARTIFACT/container-inspect.json" ]]; then
  hostname="$(jq -r '.[0].Config.Hostname // empty' "$ARTIFACT/container-inspect.json")"
  [[ -z "$hostname" ]] || run_args+=(--hostname "$hostname")
fi
restore_log="$(mktemp "$DATA/restore-diagnostic.XXXXXXXX.log")"
chmod 600 "$restore_log"
docker "${run_args[@]}" "$image_id" >/dev/null || fail "new GitLab container creation failed"
failed_restore(){
  local status=$?
  trap - ERR
  echo "[ERROR] Restore stage=$restore_stage exit=$status; private diagnostic log: $restore_log" >&2
  echo "[ERROR] Restore failed. Target preserved and stopped: $CONTAINER ; $DATA. Original server unchanged." >&2
  docker stop "$CONTAINER" >/dev/null 2>&1 || true
  exit "$status"
}
restore_stage=initial-readiness
restore_step(){
  restore_stage="$1"; shift
  printf '[INFO] Restore stage=%s\n' "$restore_stage"
  printf '\n[STAGE] %s\n' "$restore_stage" >> "$restore_log"
  "$@" >> "$restore_log" 2>&1
}
trap failed_restore ERR
wait_ready(){
  local i
  for ((i=0; i<WAIT_ATTEMPTS; i++)); do
    [[ "$(docker container inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null)" == true ]] || return 1
    # An unconfigured image can report an empty service list as success, and its
    # generated healthcheck may not exist yet. Require configured application services.
    if docker exec "$CONTAINER" test -f /var/opt/gitlab/bootstrapped >/dev/null 2>&1 \
      && docker exec "$CONTAINER" test -s /var/opt/gitlab/gitlab-rails/etc/database.yml >/dev/null 2>&1 \
      && docker exec "$CONTAINER" sh -c '! pgrep -f "/opt/gitlab/embedded/bin/[c]inc-client" >/dev/null' >/dev/null 2>&1 \
      && docker exec "$CONTAINER" gitlab-ctl status puma >/dev/null 2>&1 \
      && docker exec "$CONTAINER" gitlab-ctl status sidekiq >/dev/null 2>&1 \
      && docker exec "$CONTAINER" gitlab-healthcheck --fail --max-time 5 >/dev/null 2>&1; then return 0; fi
    sleep 5
  done
  return 1
}
wait_ready
restore_stage=verify-package
actual_package="$(docker exec "$CONTAINER" cat /opt/gitlab/version-manifest.txt | awk '$1 ~ /^gitlab-(ce|ee)$/ {print $1 " " $2; exit}')"
[[ "$actual_package" == "$package $version" ]] || { echo '[ERROR] running GitLab package version/type mismatch' >&2; false; }
restore_step prepare-backup docker exec "$CONTAINER" mkdir -p /var/opt/gitlab/backups
restore_step copy-backup docker cp "$ARTIFACT/$backup_tar" "$CONTAINER:/var/opt/gitlab/backups/$backup_tar"
restore_step backup-owner docker exec "$CONTAINER" chown git:git "/var/opt/gitlab/backups/$backup_tar"
restore_step backup-mode docker exec "$CONTAINER" chmod 600 "/var/opt/gitlab/backups/$backup_tar"
restore_step stop-puma docker exec "$CONTAINER" gitlab-ctl stop puma
restore_step stop-sidekiq docker exec "$CONTAINER" gitlab-ctl stop sidekiq
backup_id="${backup_tar%_gitlab_backup.tar}"
restore_step native-restore docker exec "$CONTAINER" gitlab-backup restore "BACKUP=$backup_id" force=yes
restore_step restart-target docker restart "$CONTAINER"
restore_stage=restored-readiness
wait_ready
restore_step http-git-policy docker exec "$CONTAINER" gitlab-rails runner "ApplicationSetting.current.update!(enabled_git_access_protocol: 'http')"
restore_step application-check docker exec "$CONTAINER" gitlab-rake gitlab:check SANITIZE=true
restore_step secrets-check docker exec "$CONTAINER" gitlab-rake gitlab:doctor:secrets
restore_step enable-restart docker update --restart always "$CONTAINER"
trap - ERR
echo "GitLab restore completed: container=$CONTAINER data=$DATA package=$package version=$version"
echo 'Next: verify users/projects/CI in UI, then switch DNS/Runner/Deploy after stopping the old service.'
