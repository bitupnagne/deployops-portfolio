#!/usr/bin/env bash
# Explicit operations only; never called by install.sh or auto-accepts Nexus EULA.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INSTALLER="$(cd "$HERE/.." && pwd)"
. "$INSTALLER/lib/release-metadata.sh"
. "$INSTALLER/lib/java-ci-payload.sh"
die() { printf 'offline-ci-bootstrap: %s\n' "$*" >&2; exit 1; }
usage() {
  printf '%s\n' 'Usage: bootstrap-offline-ci.sh --operation prepare-images|seed-dry-run|seed-apply|publish-images [--trusted-public-key PATH] --state-dir NEW_PRIVATE_DIRECTORY --post-eula-confirmed [--nexus-url ORIGIN --maven-auth-file PATH] [--registry ORIGIN --registry-auth-file PATH --policy-auth-file PATH --docker-repository NAME --builder-repository PATH --runtime-repository PATH --tag TAG] [--utility-network NETWORK] [--allow-http] [--omit-snapshots]'
}
operation='' trusted='' state='' nexus='' maven_auth='' registry='' registry_auth='' policy_auth=''
docker_repo='' builder_repo='' runtime_repo='' tag='' network='bridge' eula=0 http=0 omit=0
while (( $# )); do
  case "$1" in
    --help|-h) usage; exit 0 ;;
    --post-eula-confirmed) eula=1; shift ;;
    --allow-http) http=1; shift ;;
    --omit-snapshots) omit=1; shift ;;
    --operation|--trusted-public-key|--state-dir|--nexus-url|--maven-auth-file|--registry|--registry-auth-file|--policy-auth-file|--docker-repository|--builder-repository|--runtime-repository|--tag|--utility-network)
      (( $# >= 2 )) || die 'Option needs a value'
      case "$1" in
        --operation) operation="$2" ;; --trusted-public-key) trusted="$2" ;; --state-dir) state="$2" ;;
        --nexus-url) nexus="$2" ;; --maven-auth-file) maven_auth="$2" ;; --registry) registry="$2" ;;
        --registry-auth-file) registry_auth="$2" ;; --policy-auth-file) policy_auth="$2" ;; --docker-repository) docker_repo="$2" ;;
        --builder-repository) builder_repo="$2" ;; --runtime-repository) runtime_repo="$2" ;; --tag) tag="$2" ;; --utility-network) network="$2" ;;
      esac
      shift 2 ;;
    *) die 'Unknown option' ;;
  esac
done
[[ "$operation" =~ ^(prepare-images|seed-dry-run|seed-apply|publish-images)$ ]] || die 'Select one explicit operation'
[[ "$eula" == 1 ]] || die 'Administrator must review/accept Nexus EULA in UI before opting in'
[[ "$(id -u)" == 0 ]] || die 'Root is required for private credential mounts and local Docker'
[[ "$network" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]] || die 'Invalid utility network'
[[ -z "$trusted" || ( -f "$trusted" && ! -L "$trusted" ) ]] || die 'Trusted public key path is invalid'
java_ci_root_safe "$state" || die 'Unsafe state path'
[[ ! -e "$state" && ! -L "$state" ]] || die 'State directory exists; preserve old attempt and choose a fresh path'
parent="$(dirname -- "$state")"
[[ -d "$parent" && "$(stat -c %u "$parent")" == 0 && "$(stat -c %a "$parent")" =~ ^(700|750|755)$ ]] || die 'State parent must be root-owned private or read-only to other users'
safe_auth() {
  java_ci_root_safe "$1" && [[ -f "$1" && ! -L "$1" && "$(stat -c %a "$1")" == 600 && "$(stat -c %u "$1")" == 0 ]] || die 'Credential files must be regular root-owned POSIX 0600 files without symlink ancestors'
}
if [[ "$operation" == seed-* ]]; then
  [[ -n "$nexus" ]] || die 'Nexus origin required'
  safe_auth "$maven_auth"
fi
if [[ "$operation" == publish-images ]]; then
  [[ "$registry" =~ ^[a-zA-Z0-9.-]+(:[0-9]+)?$ && "$docker_repo" =~ ^[A-Za-z0-9_.-]+$ && "$tag" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ && "$tag" != latest ]] || die 'Fixed registry/repository/tag required'
  for repo in "$builder_repo" "$runtime_repo"; do [[ "$repo" =~ ^[a-z0-9]+([._-][a-z0-9]+)*(/[a-z0-9]+([._-][a-z0-9]+)*)*$ ]] || die 'Invalid image repository path'; done
  [[ "$builder_repo" != "$runtime_repo" && -n "$nexus" ]] || die 'Distinct builder/runtime paths and Nexus origin required'
  safe_auth "$registry_auth"; safe_auth "$policy_auth"
fi
umask 077
mkdir -- "$state" || die 'Another attempt owns this state path'
printf '%s\n' "$operation" > "$state/operation"
finished=0 private_created=0
finish() {
  local status="$?" cleanup=0
  if [[ "$private_created" == 1 ]]; then
    rm -f -- "$state/docker-config/config.json" || cleanup=1
    rmdir -- "$state/docker-config" || cleanup=1
  fi
  if [[ "$finished" != 1 || "$cleanup" != 0 ]]; then status=1; fi
  printf '{"exitCode":%s,"credentialCleanupExitCode":%s,"ciBuildTested":false}\n' "$status" "$cleanup" > "$state/terminal.json"
  exit "$status"
}
trap finish EXIT
verify_args=(--require-java-ci)
[[ -z "$trusted" ]] || verify_args+=(--require-signature --trusted-public-key "$trusted")
export OFFLINE_VERIFY_AUTHENTICITY=0
[[ -z "$trusted" ]] || export OFFLINE_VERIFY_AUTHENTICITY=1
BUNDLE_ROOT="$INSTALLER/bundles" bash "$INSTALLER/verify-offline-bundle.sh" "${verify_args[@]}" > "$state/verification.log" 2>&1

payload="$INSTALLER/bundles/common/java-ci"
jq_bin="$INSTALLER/bundles/common/jq/jq-linux-amd64"
[[ -x "$jq_bin" ]] || jq_bin="$(command -v jq)"
if [[ "$operation" == prepare-images ]]; then
  java_ci_preload "$payload" "$jq_bin" > "$state/prepare.log" 2>&1
  finished=1; exit 0
fi
java_ci_loaded_contract "$payload" "$jq_bin" || die 'Run explicit prepare-images first; no automatic pull/load'
utility="$("$jq_bin" -er '.images.utility.configDigest' "$payload/CI-PAYLOAD.json")"
seed_pin="$("$jq_bin" -er '.seed.lockSha256' "$payload/CI-PAYLOAD.json")"
common=(--rm --pull=never --user 0:0 --read-only --cap-drop=ALL --security-opt no-new-privileges:true)
http_args=(); [[ "$http" == 0 ]] || http_args=(--allow-http)
manifest_args=(); [[ "$http" == 0 ]] || manifest_args=(--insecure)
helper() {
  local auth="$1"; shift
  docker run "${common[@]}" --network "$network" --mount "type=bind,src=$HERE,dst=/bootstrap,readonly" --mount "type=bind,src=$auth,dst=/auth.json,readonly" --entrypoint python "$utility" -I -B /bootstrap/ci_bootstrap_registry.py "$@"
}
if [[ "$operation" == seed-* ]]; then
  args=(--seed /seed --lock-sha256 "$seed_pin" --nexus-url "$nexus" --auth-file /auth.json "${http_args[@]}")
  [[ "$omit" == 0 ]] || args+=(--omit-snapshots)
  [[ "$operation" != seed-apply ]] || args+=(--apply)
  docker run "${common[@]}" --network "$network" --mount "type=bind,src=$payload/maven-seed,dst=/seed,readonly" --mount "type=bind,src=$maven_auth,dst=/auth.json,readonly" "$utility" "${args[@]}" > "$state/seed-result.json" 2> "$state/seed-error.log"
  finished=1; exit 0
fi
helper "$policy_auth" policy --nexus-url "$nexus" --repository "$docker_repo" --registry "$registry" --auth-file /auth.json "${http_args[@]}" > "$state/policy.log" 2>&1
# Credential helper is offline and stdout goes directly to Docker password-stdin.
network_saved="$network"; network=none
user="$(helper "$registry_auth" credential --auth-file /auth.json --field username)"
mkdir -- "$state/docker-config"; private_created=1
helper "$registry_auth" credential --auth-file /auth.json --field password | docker --config "$state/docker-config" login --username "$user" --password-stdin "$registry" > "$state/login.log" 2>&1
[[ "$(stat -c %a "$state/docker-config/config.json")" == 600 && "$(stat -c %u "$state/docker-config/config.json")" == 0 && ! -L "$state/docker-config/config.json" ]] || die 'Private Docker login file permissions mismatch'
network="$network_saved"
for role in builder runtime; do
  repo="$builder_repo"; [[ "$role" != runtime ]] || repo="$runtime_repo"
  target="$registry/$repo:$tag"
  config="$("$jq_bin" -er --arg r "$role" '.images[$r].configDigest' "$payload/CI-PAYLOAD.json")"
  if docker --config "$state/docker-config" manifest inspect "${manifest_args[@]}" --verbose "$target" > "$state/$role.manifest.json" 2> "$state/$role.absence.log"; then
    action=same-image-skip
  else
    grep -Eq 'manifest unknown|no such manifest' "$state/$role.absence.log" || die 'Target query failed ambiguously; no blind push'
    # Enforced ALLOW_ONCE closes the check/push race; latest override is disabled.
    helper "$policy_auth" policy --nexus-url "$nexus" --repository "$docker_repo" --registry "$registry" --auth-file /auth.json "${http_args[@]}" > "$state/$role.policy.log" 2>&1
    docker tag "$config" "$target"
    docker --config "$state/docker-config" push "$target" > "$state/$role.push.log" 2>&1
    docker --config "$state/docker-config" manifest inspect "${manifest_args[@]}" --verbose "$target" > "$state/$role.manifest.json" 2> "$state/$role.manifest-error.log"
    action=published
  fi
  reference="$(docker run "${common[@]}" --network none --mount "type=bind,src=$HERE,dst=/bootstrap,readonly" --mount "type=bind,src=$state/$role.manifest.json,dst=/manifest.json,readonly" --entrypoint python "$utility" -I -B /bootstrap/ci_bootstrap_registry.py manifest --file /manifest.json --repository "$registry/$repo" --config "$config")"
  docker --config "$state/docker-config" pull "$reference" > "$state/$role.pull.log" 2>&1
  [[ "$(docker image inspect "$reference" --format '{{.Id}} {{.Os}}/{{.Architecture}}')" == "$config linux/amd64" ]] || die 'Immutable reference pull config/platform mismatch'
  "$jq_bin" -cn --arg role "$role" --arg ref "$reference" --arg action "$action" '{role:$role,reference:$ref,action:$action,immutablePullVerified:true}' > "$state/$role.result.json"
done
finished=1
printf 'offline-ci-bootstrap: complete; read protected state result files and register immutable references in Deploy UI\n'
