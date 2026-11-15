#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERIFY="$SCRIPT_DIR/gitlab/verify-system-pat.sh"
export GITLAB_SYSTEM_PAT='fixture-only'

curl() {
  local output='' endpoint="${*: -1}"
  while (($#)); do
    if [[ "$1" == -o ]]; then
      output="${2:-}"
      shift 2
    else
      shift
    fi
  done
  [[ -n "$output" ]] || return 1
  case "$endpoint" in
    */personal_access_tokens/self)
      if [[ "${MOCK_BAD_SCOPE:-0}" == 1 ]]; then
        printf '{"active":true,"revoked":false,"scopes":["api"]}\n' > "$output"
      else
        printf '{"active":true,"revoked":false,"scopes":["api","manage_runner"]}\n' > "$output"
      fi
      ;;
    */user)
      printf '%s\n' "$MOCK_USER" > "$output"
      ;;
    */projects/7/members/all/13)
      printf '{"access_level":%s}\n' "${MOCK_ACCESS_LEVEL:-40}" > "$output"
      ;;
    */runners/9)
      printf '{"id":9}\n' > "$output"
      ;;
    *) return 1 ;;
  esac
}
export -f curl

assert_ok() {
  bash "$VERIFY" --url http://gitlab.test --project-id 7 --runner-id 9 >/dev/null
}

assert_rejected() {
  if bash "$VERIFY" --url http://gitlab.test --project-id 7 --runner-id 9 >/dev/null 2>&1; then
    printf 'unexpected PAT acceptance\n' >&2
    exit 1
  fi
}

# GitLab's regular-user /user response omits is_admin; administrator response sets it true.
MOCK_USER='{"id":13,"username":"deployops-bot"}'
export MOCK_USER
assert_ok
MOCK_USER='{"id":13,"username":"deployops-bot","is_admin":false}'
assert_ok
MOCK_USER='{"id":13,"username":"root"}'
assert_rejected
MOCK_USER='{"id":13,"username":"ROOT","is_admin":false}'
assert_rejected
MOCK_USER='{"id":13,"username":"renamed-admin","is_admin":true}'
assert_rejected
MOCK_USER='{"id":13,"username":"deployops-bot","is_admin":"false"}'
assert_rejected
MOCK_USER='{"id":13,"is_admin":false}'
assert_rejected
MOCK_USER='{"id":13,"username":"deployops-bot","is_admin":null}'
assert_rejected
MOCK_USER='{"id":13,"username":"deployops-bot","is_admin":false}'
MOCK_ACCESS_LEVEL=30
export MOCK_ACCESS_LEVEL
assert_rejected
MOCK_ACCESS_LEVEL=40
MOCK_BAD_SCOPE=1
export MOCK_BAD_SCOPE
assert_rejected

printf 'gitlab-system-pat-regression: PASS (regular service account accepted; root/admin, invalid identity, role and scopes rejected)\n'
