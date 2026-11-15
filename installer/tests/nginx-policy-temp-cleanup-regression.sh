#!/usr/bin/env bash
# Exercise apply_policy's real EXIT-trap wiring with its external operations mocked.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source <(sed '/^\[\[ "\$EUID" -eq 0 \]\]/,$d' \
  "$SCRIPT_DIR/deploy-agent/web/deploy-nginx-policy.sh")

test_root="$(mktemp -d)"
cleanup_test_root(){ rm -rf -- "$test_root"; }
trap cleanup_test_root EXIT

# Certificate and key paths must belong to one request directory so cleanup
# cannot leave a second staged secret behind.
validation_output="$test_root/mismatched-staging.stderr"
set +e
(
  REQUEST_ID=7 POLICY_NAME=cleanup-check SERVER_NAME=cleanup.test
  UPSTREAM_HOST=127.0.0.1 UPSTREAM_PORT=8080
  CERTIFICATE=/var/tmp/deploy-ops/nginx-change-17/certificate.pem
  PRIVATE_KEY=/var/tmp/deploy-ops/nginx-change-18/private-key.pem
  validate_apply
) >/dev/null 2>"$validation_output"
validation_status=$?
set -e
[[ "$validation_status" -ne 0 ]] || {
  echo 'mismatched certificate/key staging directories were accepted' >&2
  exit 1
}
grep -Fqx '인증서와 개인키는 현재 요청의 staging 디렉터리에 있어야 합니다.' "$validation_output" || {
  echo 'mismatched staging paths failed before the request-directory validation message' >&2
  cat "$validation_output" >&2
  exit 1
}

validation_output="$test_root/wrong-request-staging.stderr"
set +e
(
  REQUEST_ID=7 POLICY_NAME=cleanup-check SERVER_NAME=cleanup.test
  UPSTREAM_HOST=127.0.0.1 UPSTREAM_PORT=8080
  CERTIFICATE=/var/tmp/deploy-ops/nginx-change-17/certificate.pem
  PRIVATE_KEY=/var/tmp/deploy-ops/nginx-change-17/private-key.pem
  validate_apply
) >/dev/null 2>"$validation_output"
validation_status=$?
set -e
[[ "$validation_status" -ne 0 ]] || {
  echo 'same-directory certificate/key staging from a different request was accepted' >&2
  exit 1
}
grep -Fqx '인증서와 개인키는 현재 요청의 staging 디렉터리에 있어야 합니다.' "$validation_output" || {
  echo 'wrong-request staging paths failed before the request-directory validation message' >&2
  cat "$validation_output" >&2
  exit 1
}

mock_bin="$test_root/bin"
mkdir -p "$mock_bin"
for tool in nginx openssl; do : > "$mock_bin/$tool"; chmod +x "$mock_bin/$tool"; done

run_apply_case(){
  local case_name="$1" expected_status="$2" fail_nginx="$3"
  local case_root="$test_root/$case_name"
  mkdir -p "$case_root/certificate-stage"
  : > "$case_root/certificate-stage/certificate.pem"
  : > "$case_root/certificate-stage/private-key.pem"
  : > "$case_root/nginx-temp"
  set +e
  (
    export PATH="$mock_bin:$PATH"
    MOCK_TEMP_CONFIG="$case_root/nginx-temp"
    FAIL_NGINX_TEST="$fail_nginx"
    NGINX_TEST_CALLS=0
    REQUEST_ID=7 POLICY_NAME=cleanup-check SERVER_NAME=cleanup.test
    UPSTREAM_HOST=127.0.0.1 UPSTREAM_PORT=8080
    CERTIFICATE="$case_root/certificate-stage/certificate.pem"
    PRIVATE_KEY="$case_root/certificate-stage/private-key.pem"
    BACKUP_ROOT="$case_root/backups"

    # Replace only host-side effects; invoke the actual apply_policy and
    # cleanup_apply_temps functions from the production script.
    validate_apply(){ :; }
    assert_owns_config(){ :; }
    assert_no_conflicting_server(){ :; }
    warn_if_upstream_unreachable(){ :; }
    backup_current(){ BACKUP_DIR="$case_root/backups/backup"; }
    validate_certificate(){ :; }
    render_config(){ :; }
    install(){ :; }
    chown(){ :; }
    restore_backup(){ :; }
    nginx(){
      if [[ "$1" == -t ]]; then
        NGINX_TEST_CALLS=$((NGINX_TEST_CALLS + 1))
        if [[ "$FAIL_NGINX_TEST" == true && "$NGINX_TEST_CALLS" -eq 1 ]]; then return 1; fi
      fi
      return 0
    }
    systemctl(){ return 0; }
    mktemp(){ : > "$MOCK_TEMP_CONFIG"; printf '%s\n' "$MOCK_TEMP_CONFIG"; }

    apply_policy
  ) >/dev/null 2>&1
  local actual_status=$?
  set -e
  [[ "$actual_status" -eq "$expected_status" ]] || {
    echo "$case_name: expected exit $expected_status, got $actual_status" >&2
    return 1
  }
  [[ ! -e "$case_root/nginx-temp" ]] || {
    echo "$case_name: temporary Nginx config remained after apply_policy exit" >&2
    return 1
  }
  [[ ! -e "$case_root/certificate-stage" ]] || {
    echo "$case_name: staged certificate directory remained after apply_policy exit" >&2
    return 1
  }
}

run_apply_case success 0 false
run_apply_case nginx_failure 1 true
echo 'nginx-policy-temp-cleanup-regression: PASS (same-directory validation; apply success/error cleanup and exit status)'
