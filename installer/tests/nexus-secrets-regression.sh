#!/usr/bin/env bash
# Root/Linux filesystem checks; all Docker operations are mocked. No live service is touched.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[[ "$EUID" == 0 ]] || { echo 'Run as root on an isolated Linux test filesystem.' >&2; exit 1; }
task_tmp="$(mktemp -d)"
trap 'rm -rf "$task_tmp"' EXIT
BOM_NEXUS_IMAGE=fixture
. "$SCRIPT_DIR/components/nexus.sh"
. "$SCRIPT_DIR/lib/ui.sh"
err() { printf '%s\n' "$*" >&2; }
info() { printf '%s\n' "$*"; }
fixture_jq="$(command -v jq)"
docker_jq_binary() { printf '%s' "$fixture_jq"; }
NEXUS_VOLUME=fixture-nexus
NEXUS_SECRETS_KEY_FILE="$task_tmp/keys/secrets.json"
docker() {
  case "$1 $2" in
    'container ls') [[ "${FAKE_EXISTING:-n}" != y ]] || printf '%s\n' "$NEXUS_CONTAINER" ;;
    'volume ls') [[ ! -d "$task_tmp/volume" ]] || printf '%s\n' "$NEXUS_VOLUME" ;;
    'volume inspect') printf 'local|%s/volume' "$task_tmp" ;;
    'volume create') mkdir "$task_tmp/volume" ;;
    'inspect --format') cat "$task_tmp/mounts.json" ;;
    *) echo 'Unexpected Docker command in fixture' >&2; return 90 ;;
  esac
}
container_environment_value() { printf '%s' "${FAKE_KEY_ENV:-/run/nexus-secrets/secrets.json}"; }
expect_failure() {
  if "$@" >"$task_tmp/failure.log" 2>&1; then echo "Unexpected success: $1" >&2; exit 1; fi
}
answer_key_allowed NEXUS_SECRETS_KEY_FILE
# An existing target cannot be abandoned by generating a new configured volume/key.
FAKE_EXISTING=y
for mounts in \
  '[{"Type":"volume","Driver":"local","Name":"old-data","Destination":"/nexus-data"}]' \
  '[{"Type":"bind","Source":"/srv/nexus-data","Destination":"/nexus-data"}]' \
  '[]' \
  '[{"Type":"volume","Driver":"nfs","Name":"fixture-nexus","Destination":"/nexus-data"}]' \
  '[{"Type":"volume","Driver":"local","Name":"fixture-nexus","Destination":"/nexus-data"},{"Type":"volume","Driver":"local","Name":"fixture-nexus","Destination":"/nexus-data"}]'; do
  printf '%s' "$mounts" > "$task_tmp/mounts.json"
  expect_failure nexus_secrets_prepare
  [[ ! -e "$NEXUS_SECRETS_KEY_FILE" && ! -d "$task_tmp/volume" ]]
done
# Even an expected mount cannot be treated as fresh if its volume cannot be inspected.
printf '%s' '[{"Type":"volume","Driver":"local","Name":"fixture-nexus","Destination":"/nexus-data"}]' > "$task_tmp/mounts.json"
expect_failure nexus_secrets_prepare
[[ ! -e "$NEXUS_SECRETS_KEY_FILE" && ! -d "$task_tmp/volume" ]]
FAKE_EXISTING=n
# No outputs, including xtrace output, may contain a generated secret.
(set -x; nexus_secrets_prepare) > "$task_tmp/create.log" 2>&1
[[ "$(stat -c '%u:%g:%a' "$NEXUS_SECRETS_KEY_FILE")" == 0:200:440 ]]
[[ "$(stat -c '%u:%g:%a' "${NEXUS_SECRETS_KEY_FILE%/*}")" == 0:200:750 ]]
[[ "$(stat -c '%u:%g:%a' "$task_tmp/volume")" == 200:200:755 ]]
[[ "$(stat -c '%u:%g:%a' "$task_tmp/volume/.deployops-nexus-key.sha256")" == 0:0:400 ]]
"$fixture_jq" -e '.keys[0].key | test("^[A-Za-z0-9+/]{43}=$")' "$NEXUS_SECRETS_KEY_FILE" >/dev/null
key_hash="$(sha256sum "$NEXUS_SECRETS_KEY_FILE")"
nexus_secrets_prepare > "$task_tmp/rerun.log" 2>&1
[[ "$(sha256sum "$NEXUS_SECRETS_KEY_FILE")" == "$key_hash" ]]
"$fixture_jq" -r '.keys[].key' "$NEXUS_SECRETS_KEY_FILE" > "$task_tmp/patterns"
if grep -Fqf "$task_tmp/patterns" "$task_tmp/create.log" "$task_tmp/rerun.log"; then
  echo 'Generated key leaked to output' >&2; exit 1
fi
cp "$NEXUS_SECRETS_KEY_FILE" "$task_tmp/original.json"
"$fixture_jq" -cn --arg file "$NEXUS_SECRETS_KEY_FILE" --arg volume "$NEXUS_VOLUME" \
  '[{Type:"bind",Source:$file,Destination:"/run/nexus-secrets/secrets.json",RW:false},
    {Type:"volume",Driver:"local",Name:$volume,Destination:"/nexus-data",RW:true}]' > "$task_tmp/mounts.json"
FAKE_EXISTING=y nexus_secrets_prepare > "$task_tmp/matching-container.log" 2>&1
[[ "$(sha256sum "$NEXUS_SECRETS_KEY_FILE")" == "$key_hash" ]]
nexus_secrets_runtime_contract
FAKE_KEY_ENV=/wrong expect_failure nexus_secrets_runtime_contract
cp "$task_tmp/mounts.json" "$task_tmp/good-mounts.json"
"$fixture_jq" '.[0].RW=true' "$task_tmp/good-mounts.json" > "$task_tmp/mounts.json"
expect_failure nexus_secrets_runtime_contract
cp "$task_tmp/good-mounts.json" "$task_tmp/mounts.json"
# Authenticated status/check must prove Nexus loaded a custom encryption key.
fixture_password=fixture-health-password
nexus_admin_password() { printf '%s' "$fixture_password"; }
curl() {
  local output='' auth='' url="${*: -1}"
  while [[ $# -gt 0 ]]; do
    case "$1" in
      -o) output="$2"; shift 2 ;;
      --config) auth="$2"; shift 2 ;;
      *) shift ;;
    esac
  done
  case "$url" in
    */status/check)
      [[ "${FAKE_HEALTH_HTTP:-ok}" == ok ]] || return 22
      [[ -f "$auth" && "$(stat -c %a "$auth")" == 600 ]] || return 1
      grep -Fxq 'user = "admin:fixture-health-password"' "$auth" || return 1
      cp "$task_tmp/health.json" "$output" ;;
    */system/eula) printf '{"accepted":true}' > "$output" ;;
    */security/realms/active) printf '["DockerToken"]' > "$output" ;;
    */repositories/docker/hosted/*) printf '{}' > "$output" ;;
    *) return 90 ;;
  esac
}
nexus_runtime_contract_base() { :; }
nexus_repository_contract_json() { :; }
printf '{"Default Secret Encryption Key":{"healthy":true},"Re-encryption required":{"healthy":true}}' > "$task_tmp/health.json"
nexus_secrets_health_contract http://fixture "$fixture_jq"
nexus_runtime_contract 8081 5000
for health in \
  '{"Default Secret Encryption Key":{"healthy":false},"Re-encryption required":{"healthy":true}}' \
  '{"Other check":{"healthy":true},"Re-encryption required":{"healthy":true}}' \
  '{"Default Secret Encryption Key":{"healthy":"true"},"Re-encryption required":{"healthy":true}}' \
  '{"Default Secret Encryption Key":null,"Re-encryption required":{"healthy":true}}' \
  '{"Default Secret Encryption Key":{"healthy":true},"Re-encryption required":{"healthy":false}}' \
  '{"Default Secret Encryption Key":{"healthy":true}}' \
  '{"Default Secret Encryption Key":{"healthy":true},"Re-encryption required":{"healthy":"true"}}' \
  '{"Default Secret Encryption Key":{"healthy":true},"Re-encryption required":null}' \
  '[]' 'not-json'; do
  printf '%s' "$health" > "$task_tmp/health.json"
  expect_failure nexus_secrets_health_contract http://fixture "$fixture_jq"
  expect_failure nexus_runtime_contract 8081 5000
done
printf '{"Default Secret Encryption Key":{"healthy":true},"Re-encryption required":{"healthy":true}}' > "$task_tmp/health.json"
FAKE_HEALTH_HTTP=fail expect_failure nexus_runtime_contract 8081 5000
fixture_password='' expect_failure nexus_secrets_health_contract http://fixture "$fixture_jq"
# Populated volumes cannot silently receive a newly generated or unrelated key.
touch "$task_tmp/volume/existing-data"
rm "$NEXUS_SECRETS_KEY_FILE"
expect_failure nexus_secrets_prepare
[[ ! -e "$NEXUS_SECRETS_KEY_FILE" && -f "$task_tmp/volume/existing-data" ]]
grep -q '재암호화/복구' "$task_tmp/failure.log"
cp "$task_tmp/original.json" "$NEXUS_SECRETS_KEY_FILE"
chown 0:200 "$NEXUS_SECRETS_KEY_FILE"; chmod 0440 "$NEXUS_SECRETS_KEY_FILE"
nexus_secrets_prepare >/dev/null
"$fixture_jq" '.keys[0].key="00000000000000000000000000000000000000000000"' \
  "$task_tmp/original.json" > "$NEXUS_SECRETS_KEY_FILE"
expect_failure nexus_secrets_prepare
cp "$task_tmp/original.json" "$NEXUS_SECRETS_KEY_FILE"
chmod 0644 "$NEXUS_SECRETS_KEY_FILE"
expect_failure nexus_secrets_prepare
chmod 0440 "$NEXUS_SECRETS_KEY_FILE"
rm "$task_tmp/volume/.deployops-nexus-key.sha256"
expect_failure nexus_secrets_prepare
[[ ! -f "$task_tmp/volume/.deployops-nexus-key.sha256" ]]
# Invalid schema/path must fail before starting a container or generating replacement material.
printf '{"active":"missing","keys":[]}' > "$NEXUS_SECRETS_KEY_FILE"
expect_failure nexus_secrets_validate_file
NEXUS_SECRETS_KEY_FILE="$SCRIPT_DIR/bundles/secrets.json" expect_failure nexus_secrets_validate_path
NEXUS_SECRETS_KEY_FILE=relative.json expect_failure nexus_secrets_validate_path
ln -s "$task_tmp/keys" "$task_tmp/key-link"
NEXUS_SECRETS_KEY_FILE="$task_tmp/key-link/secrets.json" expect_failure nexus_secrets_validate_path
# Exercise the installer launch command without any real Docker operations.
NEXUS_SECRETS_KEY_FILE="$task_tmp/keys/secrets.json"
rm "$NEXUS_SECRETS_KEY_FILE" "$task_tmp/volume/existing-data"
docker_available() { :; }
image_matches_archive_id() { :; }
container_exists() { [[ -f "$task_tmp/container-created" ]]; }
ask() { printf '%s' "$3"; }
ask_confirm() { :; }
wait_container_contract() { :; }
nexus_runtime_contract_base() { :; }
nexus_auto_setup() { :; }
success() { :; }
eval "$(declare -f docker | sed '1s/docker/fixture_docker/')"
docker() {
  if [[ "$1" == run ]]; then
    printf '%s\n' "$@" > "$task_tmp/run-args"
    touch "$task_tmp/container-created"
  else fixture_docker "$@"; fi
}
nexus_install > "$task_tmp/install.log" 2>&1
grep -Fxq 'NEXUS_SECRETS_KEY_FILE=/run/nexus-secrets/secrets.json' "$task_tmp/run-args"
grep -Fxq "$NEXUS_SECRETS_KEY_FILE:/run/nexus-secrets/secrets.json:ro,Z" "$task_tmp/run-args"
"$fixture_jq" -r '.keys[].key' "$NEXUS_SECRETS_KEY_FILE" > "$task_tmp/patterns"
if grep -Fqf "$task_tmp/patterns" "$task_tmp/install.log" "$task_tmp/run-args"; then
  echo 'Installer exposed key material' >&2; exit 1
fi
echo 'nexus-secrets-regression: PASS (fresh, existing mounts, rerun, permissions, binding, authenticated health, launch and no secret output)'
