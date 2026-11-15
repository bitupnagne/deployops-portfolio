#!/usr/bin/env bash
# Pure/helper regressions for P1 drift contracts. No package, service or network changes.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

. "$SCRIPT_DIR/lib/release-metadata.sh"
load_release_bom "$SCRIPT_DIR/bundles/RELEASE-BOM.env"
[[ "$BOM_GITLAB_IMAGE" == gitlab/gitlab-ce:19.3.0-ce.0 ]]
[[ "$BOM_RUNNER_IMAGE" == gitlab/gitlab-runner:v19.3.0 ]]
[[ "$BOM_RUNNER_HELPER_IMAGE" == *:x86_64-v19.3.0 ]]
[[ "$BOM_RUNNER_BUILD_IMAGE" == docker:29.7.2 ]]
[[ "$BOM_NEXUS_IMAGE" == sonatype/nexus3:3.96.0 ]]
[[ "$BOM_JAVA_VERSION" == 21.0.12.1+1 ]]
[[ "$BOM_JAVA_ARCHIVE" == OpenJDK21U-jdk_x64_linux_hotspot_21.0.12.1_1.tar.gz ]]
[[ "$BOM_JAVA_SHA256" == ce79869e1307ed8ee1e2baa86a412b1eb5b75d10a01006d788a6f968bcfaee94 ]]
[[ "$BOM_JQ_VERSION" == 1.7.1 ]]
[[ "$BOM_JQ_SHA256" == 5942c9b0934e510ee61eb3e30273f1b3fe2590df93933a93d7c58b81d19c8ff5 ]]
[[ "$BOM_WORKSYSTEM_JAR" == common/worksystem-server/worksystem.jar ]]
[[ "$BOM_WORKSYSTEM_VERSION" == 0.1.0-SNAPSHOT ]]
grep -q 'NGINX_VERSION="${NGINX_VERSION:-1.30.3}"' "$SCRIPT_DIR/bundles/fetch-bundles.sh"
for migration in V11__approval_policy_integrity.sql V12__deployment_plan_snapshot.sql \
    V13__deployment_automation.sql V14__deployment_provenance_and_project_guard.sql \
    V15__project_manager_role.sql V16__project_container_ports.sql \
    V17__merge_request_release_evidence.sql V18__merge_request_repository_identity.sql \
    V19__nginx_static_policy.sql V20__nexus_cleanup_approval.sql \
    V21__container_running_health_mode.sql V22__merge_request_reviewer_gitlab_identity.sql \
    V23__ci_variable_manage_permission.sql V24__audit_unknown_outcome.sql \
    V25__deploy_failure_reason.sql V26__audit_target_description_capacity.sql; do
  [[ ",$BOM_DEPLOYOPS_REQUIRED_MIGRATIONS," == *",$migration,"* ]]
done

# 서명/lock을 새로 만들더라도 보안 필수 DB 계보를 BOM에서 지워 낮출 수 없다.
sed 's/,V22__merge_request_reviewer_gitlab_identity.sql//' "$SCRIPT_DIR/bundles/RELEASE-BOM.env" > "$tmpdir/bom-without-v22.env"
if load_release_bom "$tmpdir/bom-without-v22.env" > "$tmpdir/bom-without-v22.log" 2>&1; then
  echo 'BOM without reviewer identity migration V22 was accepted' >&2; exit 1
fi
grep -q 'V22' "$tmpdir/bom-without-v22.log"
load_release_bom "$SCRIPT_DIR/bundles/RELEASE-BOM.env"

sed 's/,V26__audit_target_description_capacity.sql//' "$SCRIPT_DIR/bundles/RELEASE-BOM.env" > "$tmpdir/bom-without-v26.env"
if load_release_bom "$tmpdir/bom-without-v26.env" > "$tmpdir/bom-without-v26.log" 2>&1; then
  echo 'BOM without audit target capacity migration V26 was accepted' >&2; exit 1
fi
grep -q 'V26' "$tmpdir/bom-without-v26.log"
load_release_bom "$SCRIPT_DIR/bundles/RELEASE-BOM.env"

. "$SCRIPT_DIR/lib/ui.sh"
init_ui
. "$SCRIPT_DIR/components/common.sh"
. "$SCRIPT_DIR/components/docker.sh"
. "$SCRIPT_DIR/components/gitlab.sh"
. "$SCRIPT_DIR/components/gitlab-runner.sh"
. "$SCRIPT_DIR/components/nexus.sh"
answer_key_allowed GITLAB_RUNNER_CLONE_URL || { echo "Runner clone URL answer key is not allowlisted" >&2; exit 1; }
! answer_key_allowed GITLAB_RUNNER_CLONE_URL_SECRET || { echo "unexpected Runner answer key allowed" >&2; exit 1; }

cat > "$tmpdir/pat-good.json" <<'EOF'
{"active":true,"revoked":false,"scopes":["create_runner","manage_runner","read_api"]}
EOF
gitlab_pat_scopes_json_valid "$tmpdir/pat-good.json" "$(command -v jq)" create_runner manage_runner read_api
cat > "$tmpdir/pat-bad.json" <<'EOF'
{"active":true,"revoked":false,"scopes":["create_runner"]}
EOF
if gitlab_pat_scopes_json_valid "$tmpdir/pat-bad.json" "$(command -v jq)" create_runner manage_runner read_api; then
  echo "PAT contract accepted a token without manage_runner/read_api" >&2; exit 1
fi

cat > "$tmpdir/runner-good.json" <<'EOF'
{"runner_type":"project_type","run_untagged":false,"locked":true,"access_level":"ref_protected","tag_list":["offline","docker"]}
EOF
runner_detail_contract_json "$tmpdir/runner-good.json" "$(command -v jq)" \
  project_type docker,offline false true ref_protected
sed 's/"locked":true/"locked":false/' "$tmpdir/runner-good.json" > "$tmpdir/runner-drift.json"
if runner_detail_contract_json "$tmpdir/runner-drift.json" "$(command -v jq)" \
    project_type docker,offline false true ref_protected; then
  echo "Runner detail contract accepted locked drift" >&2; exit 1
fi

expected_omnibus="$(gitlab_expected_omnibus_config http://gitlab:8929 8929 2222 2 5 n)"
[[ "$expected_omnibus" == *"gitlab_rails['signup_enabled'] = false;"* ]]
gitlab_omnibus_config_matches "$expected_omnibus" http://gitlab:8929 8929 2222 2 5 n
if gitlab_omnibus_config_matches "$expected_omnibus external_url 'http://attacker:9999';" \
    http://gitlab:8929 8929 2222 2 5 n; then
  echo "GitLab Omnibus config contract accepted a duplicate trailing override" >&2; exit 1
fi

# A persisted pre-created Runner policy must be re-queryable with VERIFY_API_TOKEN after restart.
RUNNER_CONFIG="$tmpdir/runner-config"
prepare_runner_config
[[ -f "$RUNNER_CONFIG/config.toml" ]]
[[ "$(stat -c '%a' "$RUNNER_CONFIG")" == 750 ]]
[[ "$(stat -c '%a' "$RUNNER_CONFIG/config.toml")" == 600 ]]
printf '# registered runner fixture\n' > "$RUNNER_CONFIG/config.toml"
prepare_runner_config
grep -q '^# registered runner fixture$' "$RUNNER_CONFIG/config.toml"
cat > "$RUNNER_CONFIG/policy.json" <<'EOF'
{"id":17,"url":"http://gitlab:8929","runner_type":"project_type","tags":"docker,offline","run_untagged":false,"locked":true,"access_level":"ref_protected"}
EOF
cat > "$RUNNER_CONFIG/config.toml" <<'EOF'
[[runners]]
  name = "offline-runner"
  url = "http://gitlab:8929"
  token = "redacted-fixture-token"
  executor = "docker"
  [runners.docker]
    image = "docker:29.7.2"
    helper_image = "registry.gitlab.com/gitlab-org/gitlab-runner/gitlab-runner-helper:x86_64-v19.3.0"
    pull_policy = ["never"]
    allowed_pull_policies = ["never"]
EOF
gitlab_runner_registered_config_contract
cat > "$RUNNER_CONFIG/config.toml" <<'EOF'
[[runners]]
  url = "http://gitlab:8929"
  executor = "docker"
  pull_policy = ["never"]
  allowed_pull_policies = ["never"]
  [runners.docker]
    image = "docker:29.7.2"
    helper_image = "registry.gitlab.com/gitlab-org/gitlab-runner/gitlab-runner-helper:x86_64-v19.3.0"
EOF
if gitlab_runner_registered_config_contract; then
  echo "Runner config contract accepted pull policies outside [runners.docker]" >&2; exit 1
fi
cat > "$RUNNER_CONFIG/config.toml" <<'EOF'
[[runners]]
  url = "http://gitlab:8929"
  executor = "docker"
  [runners.docker]
    image = "docker:29.7.2"
    helper_image = "registry.gitlab.com/gitlab-org/gitlab-runner/gitlab-runner-helper:x86_64-v19.3.0"
    pull_policy = ["never"]
    allowed_pull_policies = ["never"]
  [runners.docker.volume_driver_ops]
    "size" = "1G"
EOF
gitlab_runner_registered_config_contract
cat > "$RUNNER_CONFIG/config.toml" <<'EOF'
[[runners]]
  name = "offline-runner"
  url = "http://gitlab:8929"
  token = "redacted-fixture-token"
  executor = "shell"
  clone_url = "http://host.docker.internal:8929"
  [runners.docker]
    image = "docker:29.7.2"
    helper_image = "registry.gitlab.com/gitlab-org/gitlab-runner/gitlab-runner-helper:x86_64-v19.3.0"
    pull_policy = ["never"]
    allowed_pull_policies = ["never"]
EOF
if gitlab_runner_registered_config_contract; then
  echo "Runner config contract accepted a non-Docker executor" >&2; exit 1
fi
GITLAB_RUNNER_CLONE_URL=http://host.docker.internal:8929
cat > "$RUNNER_CONFIG/config.toml" <<'EOF'
[[runners]]
  name = "offline-runner"
  url = "http://gitlab:8929"
  token = "redacted-fixture-token"
  clone_url = "http://host.docker.internal:8929"
  executor = "docker"
  [runners.docker]
    image = "docker:29.7.2"
    helper_image = "registry.gitlab.com/gitlab-org/gitlab-runner/gitlab-runner-helper:x86_64-v19.3.0"
    pull_policy = ["never"]
    allowed_pull_policies = ["never"]
EOF
gitlab_runner_registered_config_contract
cat > "$RUNNER_CONFIG/config.toml" <<'EOF'
[[runners]]
  name = "offline-runner"
  url = "http://gitlab:8929"
  token = "redacted-fixture-token"
  executor = "docker"
  [runners.docker]
    image = "docker:29.7.2"
    helper_image = "registry.gitlab.com/gitlab-org/gitlab-runner/gitlab-runner-helper:x86_64-v19.3.0"
    clone_url = "http://host.docker.internal:8929"
    pull_policy = ["never"]
    allowed_pull_policies = ["never"]
EOF
if gitlab_runner_registered_config_contract; then
  echo "Runner config contract accepted clone_url outside the [[runners]] table" >&2; exit 1
fi
RUNNER_BUILD_IMAGE=docker:29.7.2
LOCK_RUNNER_BUILD_ARCHIVE_ID=sha256:fixture-build-image
image_present() { [[ "$1" == "$RUNNER_BUILD_IMAGE" ]]; }
image_matches_archive_id() { [[ "$1" == "$RUNNER_BUILD_IMAGE" && "$2" == "$LOCK_RUNNER_BUILD_ARCHIVE_ID" ]]; }
runner_build_image_contract "$RUNNER_BUILD_IMAGE"
if runner_build_image_contract docker:latest; then
  echo "Runner image contract accepted a custom unverified job image" >&2; exit 1
fi
sed 's/image = "docker:29.7.2"/image = "docker:latest"/' \
  "$RUNNER_CONFIG/config.toml" > "$tmpdir/runner-image-drift.toml"
cp "$tmpdir/runner-image-drift.toml" "$RUNNER_CONFIG/config.toml"
if gitlab_runner_registered_config_contract; then
  echo "Runner config contract accepted an unverified job image" >&2; exit 1
fi
sed 's/allowed_pull_policies = \["never"\]/allowed_pull_policies = ["always"]/' \
  "$RUNNER_CONFIG/config.toml" > "$tmpdir/runner-pull-drift.toml"
cp "$tmpdir/runner-pull-drift.toml" "$RUNNER_CONFIG/config.toml"
if gitlab_runner_registered_config_contract; then
  echo "Runner config contract accepted pull-policy drift" >&2; exit 1
fi
sed 's/clone_url = "http:\/\/host.docker.internal:8929"/clone_url = "http:\/\/localhost:8929"/' \
  "$tmpdir/runner-pull-drift.toml" > "$RUNNER_CONFIG/config.toml"
if gitlab_runner_registered_config_contract; then
  echo "Runner config contract accepted clone URL drift" >&2; exit 1
fi
unset GITLAB_RUNNER_CLONE_URL
cat > "$RUNNER_CONFIG/config.toml" <<'EOF'
[[runners]]
  url = "http://gitlab:8929"
  executor = "docker"
  [runners.docker]
    image = "docker:29.7.2"
    helper_image = "registry.gitlab.com/gitlab-org/gitlab-runner/gitlab-runner-helper:x86_64-v19.3.0"
    pull_policy = ["never"]
    allowed_pull_policies = ["never"]
EOF
gitlab_runner_registered_config_contract
cat >> "$RUNNER_CONFIG/config.toml" <<'EOF'
[[runners]]
  url = "http://gitlab:8929"
  executor = "docker"
  [runners.docker]
    image = "docker:29.7.2"
    helper_image = "registry.gitlab.com/gitlab-org/gitlab-runner/gitlab-runner-helper:x86_64-v19.3.0"
    pull_policy = ["never"]
EOF
if gitlab_runner_registered_config_contract; then
  echo "Runner config contract accepted a second incomplete runner" >&2; exit 1
fi
cat > "$tmpdir/runner-server-detail.json" <<'EOF'
{"runner_type":"project_type","run_untagged":false,"locked":true,"access_level":"ref_protected","tag_list":["offline","docker"]}
EOF
mkdir -p "$tmpdir/p1-fakebin"
cat > "$tmpdir/p1-fakebin/curl" <<EOF
#!/usr/bin/env bash
while [[ \$# -gt 0 ]]; do
  if [[ \$1 == --output ]]; then cp "$tmpdir/runner-server-detail.json" "\$2"; exit 0; fi
  shift
done
exit 2
EOF
chmod +x "$tmpdir/p1-fakebin/curl"
unset GITLAB_RUNNER_API_TOKEN GITLAB_RUNNER_URL GITLAB_RUNNER_TYPE GITLAB_RUNNER_TAG_LIST \
  GITLAB_RUNNER_RUN_UNTAGGED GITLAB_RUNNER_LOCKED GITLAB_RUNNER_PROTECTED
GITLAB_RUNNER_VERIFY_API_TOKEN=glpat-verify-fixture \
  PATH="$tmpdir/p1-fakebin:$PATH" gitlab_runner_server_policy_contract

# Exercise the full GitLab runtime contract, including every docker inspect/mount/port input.
FAKE_CONTRACT_DRIFT=""
image_matches_archive_id() {
  [[ "$FAKE_CONTRACT_DRIFT" != image ]] && [[ "$2" == sha256:* ]]
}
container_matches_archive_id() {
  [[ "$FAKE_CONTRACT_DRIFT" != container-image ]] && [[ "$2" == sha256:* ]]
}
container_runtime_healthy() { [[ "$FAKE_CONTRACT_DRIFT" != health ]]; }
container_port_binding_matches() {
  local container="$1" port="$2" host="$3"
  [[ "$FAKE_CONTRACT_DRIFT" != "port-$port" ]] || return 1
  if [[ "$container" == "$GITLAB_CONTAINER" ]]; then
    [[ "$port|$host" == "8929/tcp|8929" || "$port|$host" == "22/tcp|2222" ]]
  else
    [[ "$port|$host" == "8081/tcp|8081" || "$port|$host" == "5000/tcp|5000" ]]
  fi
}
container_mount_source_matches() {
  local destination="$2" source="$3"
  [[ "$FAKE_CONTRACT_DRIFT" != "mount-$destination" ]] || return 1
  case "$destination" in
    /etc/gitlab) [[ "$source" == "$GITLAB_DATA/config" ]] ;;
    /var/log/gitlab) [[ "$source" == "$GITLAB_DATA/logs" ]] ;;
    /var/opt/gitlab) [[ "$source" == "$GITLAB_DATA/data" ]] ;;
    *) return 1 ;;
  esac
}
container_inspect_value_matches() {
  local template="$2" expected="$3"
  case "$template" in
    *Config.Hostname*) [[ "$FAKE_CONTRACT_DRIFT" != hostname && "$expected" == gitlab ]] ;;
    *HostConfig.ShmSize*) [[ "$FAKE_CONTRACT_DRIFT" != shm && "$expected" == 268435456 ]] ;;
    *RestartPolicy.Name*) [[ "$FAKE_CONTRACT_DRIFT" != restart && "$expected" == always ]] ;;
    *) return 1 ;;
  esac
}
container_environment_value() {
  local value
  if [[ "$2" == GITLAB_DISABLE_OPENSSH ]]; then
    [[ "$FAKE_CONTRACT_DRIFT" == ssh-env ]] || printf true
    return 0
  fi
  value="$(gitlab_expected_omnibus_config http://gitlab:8929 8929 2222 2 5 n)"
  [[ "$FAKE_CONTRACT_DRIFT" != env ]] || value+=" external_url 'http://wrong:9999';"
  printf '%s' "$value"
}
curl() {
  local url="${*: -1}"
  [[ "$url" != */-/health || "$FAKE_CONTRACT_DRIFT" != http ]]
}
docker() {
  if [[ "$1" == port && "$3" == 22/tcp ]]; then
    [[ "$FAKE_CONTRACT_DRIFT" != port-22/tcp ]] || printf "0.0.0.0:2222"
    return 0
  fi
  [[ "$1" == exec && "$2" == "$GITLAB_CONTAINER" ]] || return 1
  if [[ "$3" == test && "${*: -1}" == /opt/gitlab/service/sshd ]]; then [[ "$FAKE_CONTRACT_DRIFT" != ssh-service ]]; return; fi
  case "$3" in
    gitlab-psql) [[ "$FAKE_CONTRACT_DRIFT" == git-access ]] || printf http;;
    test|sh|gitlab-ctl) return 0;;
    curl) [[ "${*: -1}" == */-/health && "$FAKE_CONTRACT_DRIFT" != http ]];;
    *) return 1;;
  esac
}
LOCK_GITLAB_ARCHIVE_ID="sha256:$(printf '1%.0s' {1..64})"
GITLAB_EXTERNAL_URL=http://gitlab:8929 GITLAB_DATA="$tmpdir/gitlab-data" \
  GITLAB_PUMA_WORKERS=2 GITLAB_SIDEKIQ_CONCURRENCY=5 GITLAB_PROMETHEUS=n \
  gitlab_runtime_contract 8929 2222
for FAKE_CONTRACT_DRIFT in image container-image health port-8929/tcp port-22/tcp \
    mount-/etc/gitlab mount-/var/log/gitlab mount-/var/opt/gitlab hostname shm restart env ssh-env ssh-service git-access http; do
  if GITLAB_EXTERNAL_URL=http://gitlab:8929 GITLAB_DATA="$tmpdir/gitlab-data" \
      GITLAB_PUMA_WORKERS=2 GITLAB_SIDEKIQ_CONCURRENCY=5 GITLAB_PROMETHEUS=n \
      gitlab_runtime_contract 8929 2222; then
    echo "GitLab runtime contract accepted drift: $FAKE_CONTRACT_DRIFT" >&2; exit 1
  fi
done
FAKE_CONTRACT_DRIFT=""

cat > "$tmpdir/nexus-good.json" <<'EOF'
{"name":"docker-hosted","format":"docker","type":"hosted","online":true,"storage":{"writePolicy":"ALLOW_ONCE","strictContentTypeValidation":true,"latestPolicy":false},"docker":{"httpPort":5000,"v1Enabled":false,"forceBasicAuth":false}}
EOF
nexus_repository_contract_json "$tmpdir/nexus-good.json" "$(command -v jq)" docker-hosted 5000
sed 's/"writePolicy":"ALLOW_ONCE"/"writePolicy":"DENY"/' "$tmpdir/nexus-good.json" > "$tmpdir/nexus-drift.json"
if nexus_repository_contract_json "$tmpdir/nexus-drift.json" "$(command -v jq)" docker-hosted 5000; then
  echo "Nexus contract accepted writePolicy drift" >&2; exit 1
fi

sed 's/"writePolicy":"ALLOW_ONCE"/"writePolicy":"ALLOW"/' "$tmpdir/nexus-good.json" > "$tmpdir/nexus-mutable.json"
if nexus_repository_contract_json "$tmpdir/nexus-mutable.json" "$(command -v jq)" docker-hosted 5000; then
  echo "Nexus contract accepted mutable ALLOW writePolicy" >&2; exit 1
fi
# Exercise Nexus status/full contract and ensure fresh setup never accepts EULA on the operator's behalf.
# Filesystem/key/mount validation has its own root/Linux regression suite.
nexus_secrets_runtime_contract() { :; }
container_environment_value() { nexus_expected_heap "${NEXUS_HEAP:-1g}"; }
FAKE_NEXUS_EULA=true
FAKE_NEXUS_REALM=true
FAKE_NEXUS_REPO=true
FAKE_NEXUS_INITIAL_SECRET=""
FAKE_NEXUS_EULA_POST_ATTEMPT=false
FAKE_NEXUS_PASSWORD_CHANGE=false
FAKE_NEXUS_EULA_RESPONSE_MODE=normal
emit_curl_json() {
  local output="$1" json="$2"
  if [[ -n "$output" && "$output" != /dev/null ]]; then printf '%s' "$json" > "$output"; else printf '%s' "$json"; fi
}
curl() {
  local output="" method=GET explicit_method=0 url="${*: -1}"
  local args=("$@") i
  for ((i=0; i<${#args[@]}; i++)); do
    case "${args[$i]}" in
      -o|--output) output="${args[$((i+1))]}" ;;
      -X|--request) method="${args[$((i+1))]}"; explicit_method=1 ;;
      -d|--data|--data-raw|--data-binary|--data-urlencode)
        [[ "$explicit_method" == 1 ]] || method=POST
        ;;
    esac
  done
  [[ "$FAKE_CONTRACT_DRIFT" != http ]] || return 1
  case "$url" in
    */service/rest/v1/status) return 0 ;;
    */service/rest/v1/status/check)
      emit_curl_json "$output" '{"Default Secret Encryption Key":{"healthy":true},"Re-encryption required":{"healthy":true}}' ;;
    */service/rest/v1/system/eula)
      if [[ "$method" == POST ]]; then FAKE_NEXUS_EULA_POST_ATTEMPT=true; FAKE_NEXUS_EULA=true; return 0; fi
      [[ "$FAKE_NEXUS_EULA_RESPONSE_MODE" != unavailable ]] || return 1
      if [[ "$FAKE_NEXUS_EULA_RESPONSE_MODE" == malformed ]]; then
        emit_curl_json "$output" '{"accepted":"false","disclaimer":"fixture"}'
        return 0
      fi
      if [[ "$FAKE_NEXUS_EULA" == true ]]; then emit_curl_json "$output" '{"accepted":true,"disclaimer":"fixture"}'
      else emit_curl_json "$output" '{"accepted":false,"disclaimer":"fixture"}'; fi
      ;;
    */service/rest/v1/security/realms/active)
      if [[ "$method" == PUT ]]; then FAKE_NEXUS_REALM=true; return 0; fi
      if [[ "$FAKE_NEXUS_REALM" == true ]]; then emit_curl_json "$output" '["NexusAuthenticatingRealm","DockerToken"]'
      else emit_curl_json "$output" '["NexusAuthenticatingRealm"]'; fi
      ;;
    */service/rest/v1/repositories/docker/hosted/docker-hosted)
      if [[ "$method" == PUT ]]; then FAKE_NEXUS_REPO=true; return 0; fi
      if [[ "$FAKE_CONTRACT_DRIFT" == repo ]]; then
        emit_curl_json "$output" '{"name":"docker-hosted","format":"docker","type":"hosted","online":true,"storage":{"writePolicy":"DENY","strictContentTypeValidation":true,"latestPolicy":false},"docker":{"httpPort":5000,"v1Enabled":false,"forceBasicAuth":false}}'
      else
        emit_curl_json "$output" '{"name":"docker-hosted","format":"docker","type":"hosted","online":true,"storage":{"writePolicy":"ALLOW_ONCE","strictContentTypeValidation":true,"latestPolicy":false},"docker":{"httpPort":5000,"v1Enabled":false,"forceBasicAuth":false}}'
      fi
      ;;
    */service/rest/v1/repositories/docker/hosted)
      [[ "$method" == POST ]] || return 1
      FAKE_NEXUS_REPO=true
      ;;
    */service/rest/v1/repositories)
      if [[ "$FAKE_NEXUS_REPO" == true ]]; then emit_curl_json "$output" '[{"name":"docker-hosted"}]'
      else emit_curl_json "$output" '[]'; fi
      ;;
    */security/users/admin/change-password) FAKE_NEXUS_PASSWORD_CHANGE=true; return 0 ;;
    *) return 1 ;;
  esac
}
docker() {
  if [[ "${1:-}" == exec && "${*: -2}" == "cat /nexus-data/admin.password" ]]; then
    printf '%s' "$FAKE_NEXUS_INITIAL_SECRET"; return 0
  fi
  return 1
}
container_exists() { return 0; }
LOCK_NEXUS_ARCHIVE_ID="sha256:$(printf '2%.0s' {1..64})"
NEXUS_ADMIN_PASSWORD=fixture-secret nexus_runtime_contract 8081 5000
for FAKE_CONTRACT_DRIFT in repo; do
  if NEXUS_ADMIN_PASSWORD=fixture-secret nexus_runtime_contract 8081 5000; then
    echo "Nexus runtime contract accepted drift: $FAKE_CONTRACT_DRIFT" >&2; exit 1
  fi
done
FAKE_CONTRACT_DRIFT=""; FAKE_NEXUS_EULA=false
if NEXUS_ADMIN_PASSWORD=fixture-secret nexus_runtime_contract 8081 5000; then
  echo "Nexus runtime contract accepted an unaccepted EULA" >&2; exit 1
fi
[[ "$(NEXUS_ADMIN_PASSWORD=fixture-secret nexus_status)" == eula-pending ]] || { echo "Nexus EULA pending status was not preserved" >&2; exit 1; }
FAKE_NEXUS_EULA=true; FAKE_NEXUS_REALM=false
if NEXUS_ADMIN_PASSWORD=fixture-secret nexus_runtime_contract 8081 5000; then
  echo "Nexus runtime contract accepted a missing DockerToken realm" >&2; exit 1
fi
FAKE_NEXUS_REALM=true
unset NEXUS_ADMIN_PASSWORD
[[ "$(nexus_status)" == unverified ]] || { echo "Nexus missing secret was not reported as unverified" >&2; exit 1; }
FAKE_NEXUS_EULA=false; FAKE_NEXUS_EULA_POST_ATTEMPT=false; FAKE_NEXUS_PASSWORD_CHANGE=false
FAKE_NEXUS_REALM=false; FAKE_NEXUS_REPO=false; FAKE_NEXUS_INITIAL_SECRET=initial-secret
pending_result=0
NEXUS_ADMIN_PASSWORD=new-secret NEXUS_DOCKER_REPO_NAME=docker-hosted nexus_auto_setup 8081 5000 >/dev/null 2>&1 || pending_result=$?
[[ "$pending_result" == "$NEXUS_EULA_PENDING_RC" ]] || { echo "Nexus pending response lost its distinct result" >&2; exit 1; }
[[ "$FAKE_NEXUS_EULA" == false && "$FAKE_NEXUS_EULA_POST_ATTEMPT" == false \
   && "$FAKE_NEXUS_PASSWORD_CHANGE" == false \
   && "$FAKE_NEXUS_REALM" == false && "$FAKE_NEXUS_REPO" == false ]] \
  || { echo "Nexus setup changed EULA/password/realm/repository state before operator acceptance" >&2; exit 1; }

for FAKE_NEXUS_EULA_RESPONSE_MODE in malformed unavailable; do
  FAKE_NEXUS_EULA_POST_ATTEMPT=false; FAKE_NEXUS_PASSWORD_CHANGE=false
  FAKE_NEXUS_REALM=false; FAKE_NEXUS_REPO=false
  if NEXUS_ADMIN_PASSWORD=new-secret NEXUS_DOCKER_REPO_NAME=docker-hosted nexus_auto_setup 8081 5000 >/dev/null 2>&1; then
    echo "Nexus setup unexpectedly accepted EULA response mode: $FAKE_NEXUS_EULA_RESPONSE_MODE" >&2; exit 1
  fi
  [[ "$FAKE_NEXUS_EULA_POST_ATTEMPT" == false && "$FAKE_NEXUS_PASSWORD_CHANGE" == false \
     && "$FAKE_NEXUS_REALM" == false && "$FAKE_NEXUS_REPO" == false ]] \
    || { echo "Nexus setup mutated state after invalid EULA response: $FAKE_NEXUS_EULA_RESPONSE_MODE" >&2; exit 1; }
done
FAKE_NEXUS_EULA_RESPONSE_MODE=normal

FAKE_NEXUS_EULA=true; FAKE_NEXUS_EULA_POST_ATTEMPT=false; FAKE_NEXUS_PASSWORD_CHANGE=false
FAKE_NEXUS_REALM=false; FAKE_NEXUS_REPO=false
NEXUS_ADMIN_PASSWORD=new-secret NEXUS_DOCKER_REPO_NAME=docker-hosted nexus_auto_setup 8081 5000 >/dev/null
[[ "$FAKE_NEXUS_EULA_POST_ATTEMPT" == false && "$FAKE_NEXUS_PASSWORD_CHANGE" == true \
   && "$FAKE_NEXUS_REALM" == true && "$FAKE_NEXUS_REPO" == true ]] \
  || { echo "Nexus accepted-EULA setup did not configure services without posting EULA" >&2; exit 1; }

# Exercise Docker service/API, package version, daemon JSON and live registry contracts.
docker_root="$tmpdir/docker-runtime"
mkdir -p "$docker_root/rocky8/docker"
for package in docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin; do
  printf '%s|0:1.0-1|x86_64|%064d|%s.rpm\n' "$package" 0 "$package" >> "$docker_root/rocky8/docker/PACKAGES.lock"
done
printf '%s\n' '{"log-driver":"journald","insecure-registries":["registry.fixture:5000"]}' > "$tmpdir/daemon.json"
systemctl() { [[ "$FAKE_DOCKER_DRIFT" != service ]]; }
rpm() {
  local package="${*: -1}"
  if [[ "$FAKE_DOCKER_DRIFT" == package && "$package" == docker-ce ]]; then printf '0:9.9-1'; else printf '0:1.0-1'; fi
}
docker() {
  [[ "$FAKE_DOCKER_DRIFT" != api ]] || return 1
  if [[ " $* " == *" --format "* ]]; then
    if [[ "$FAKE_DOCKER_DRIFT" == registry ]]; then
      printf '%s\n' '{"registry.fixture:5000":{"Secure":true}}'
    else
      printf '%s\n' '{"registry.fixture:5000":{"Secure":false}}'
    fi
  fi
}
BUNDLE_ROOT="$docker_root" BUNDLE_OS=rocky8 DOCKER_DAEMON_CONFIG="$tmpdir/daemon.json" \
  DOCKER_SET_INSECURE_REGISTRY=y DOCKER_INSECURE_REGISTRY=registry.fixture:5000 \
  FAKE_DOCKER_DRIFT="" docker_runtime_contract
for FAKE_DOCKER_DRIFT in service api package registry; do
  if BUNDLE_ROOT="$docker_root" BUNDLE_OS=rocky8 DOCKER_DAEMON_CONFIG="$tmpdir/daemon.json" \
      DOCKER_SET_INSECURE_REGISTRY=y DOCKER_INSECURE_REGISTRY=registry.fixture:5000 \
      docker_runtime_contract; then
    echo "Docker runtime contract accepted drift: $FAKE_DOCKER_DRIFT" >&2; exit 1
  fi
done
printf '%s\n' '{"log-driver":"journald"}' > "$tmpdir/daemon-drift.json"
if BUNDLE_ROOT="$docker_root" BUNDLE_OS=rocky8 DOCKER_DAEMON_CONFIG="$tmpdir/daemon-drift.json" \
    DOCKER_SET_INSECURE_REGISTRY=y DOCKER_INSECURE_REGISTRY=registry.fixture:5000 \
    FAKE_DOCKER_DRIFT="" docker_runtime_contract; then
  echo "Docker runtime contract accepted daemon.json registry drift" >&2; exit 1
fi

# The Windows bundle preparer must reject a BOM path escape before copying any payload.
if command -v powershell.exe >/dev/null 2>&1 && command -v wslpath >/dev/null 2>&1 \
    && powershell.exe -NoProfile -Command 'exit 0' >/dev/null 2>&1; then
  ps_root="$tmpdir/ps/a/b"
  mkdir -p "$ps_root"
  sed 's#^BOM_DEPLOYOPS_JAR=.*#BOM_DEPLOYOPS_JAR=../../victim.jar#' \
    "$SCRIPT_DIR/bundles/RELEASE-BOM.env" > "$ps_root/RELEASE-BOM.env"
  cat > "$ps_root/RELEASE-LOCK.env" <<EOF
LOCK_SCHEMA_VERSION=2
LOCK_BOM_RELEASE=$BOM_RELEASE
LOCK_GITLAB_IMAGE=x
LOCK_GITLAB_DIGEST=x
LOCK_GITLAB_PINNED_REF=x
LOCK_GITLAB_PLATFORM_DIGEST=x
LOCK_GITLAB_CONFIG_DIGEST=x
LOCK_GITLAB_ARCHIVE_ID=x
LOCK_RUNNER_IMAGE=x
LOCK_RUNNER_DIGEST=x
LOCK_RUNNER_PINNED_REF=x
LOCK_RUNNER_PLATFORM_DIGEST=x
LOCK_RUNNER_CONFIG_DIGEST=x
LOCK_RUNNER_ARCHIVE_ID=x
LOCK_RUNNER_HELPER_IMAGE=x
LOCK_RUNNER_HELPER_DIGEST=x
LOCK_RUNNER_HELPER_PINNED_REF=x
LOCK_RUNNER_HELPER_PLATFORM_DIGEST=x
LOCK_RUNNER_HELPER_CONFIG_DIGEST=x
LOCK_RUNNER_HELPER_ARCHIVE_ID=x
LOCK_RUNNER_BUILD_IMAGE=x
LOCK_RUNNER_BUILD_DIGEST=x
LOCK_RUNNER_BUILD_PINNED_REF=x
LOCK_RUNNER_BUILD_PLATFORM_DIGEST=x
LOCK_RUNNER_BUILD_CONFIG_DIGEST=x
LOCK_RUNNER_BUILD_ARCHIVE_ID=x
LOCK_NEXUS_IMAGE=x
LOCK_NEXUS_DIGEST=x
LOCK_NEXUS_PINNED_REF=x
LOCK_NEXUS_PLATFORM_DIGEST=x
LOCK_NEXUS_CONFIG_DIGEST=x
LOCK_NEXUS_ARCHIVE_ID=x
LOCK_DEPLOYOPS_JAR=x
LOCK_DEPLOYOPS_VERSION=x
LOCK_DEPLOYOPS_REQUIRED_MIGRATIONS=x
LOCK_DEPLOYOPS_SHA256=x
LOCK_WORKSYSTEM_JAR=x
LOCK_WORKSYSTEM_VERSION=x
LOCK_WORKSYSTEM_SHA256=x
EOF
  printf 'do-not-overwrite\n' > "$tmpdir/ps/victim.jar"
  if powershell.exe -NoProfile -ExecutionPolicy Bypass \
      -File "$(wslpath -w "$SCRIPT_DIR/../scripts/prepare-offline-bundle.ps1")" \
      -BundleRoot "$(wslpath -w "$ps_root")" \
      -DeployJar "$(wslpath -w "$SCRIPT_DIR/tests/installer-p1-regression.sh")" \
      >/dev/null 2>&1; then
    echo "PowerShell preparer accepted BOM_DEPLOYOPS_JAR path escape" >&2; exit 1
  fi
  [[ "$(cat "$tmpdir/ps/victim.jar")" == do-not-overwrite ]] \
    || { echo "PowerShell preparer overwrote an escaped path before validation" >&2; exit 1; }
fi

bash "$SCRIPT_DIR/tests/nexus-eula-pending-regression.sh"
echo "installer P1 regression checks passed"
