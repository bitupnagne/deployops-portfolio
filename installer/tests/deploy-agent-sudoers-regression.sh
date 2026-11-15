#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
. "$ROOT/installer/deploy-agent/sudoers-render.sh"
template="$ROOT/installer/deploy-agent/deploy-remote.sudoers"

render() { render_deploy_agent_sudoers "$template" "$1" "$2" deploysvc diaguser; }
assert_has() { grep -Fq -- "$2" <<< "$1" || { echo "missing expected sudoers entry: $2" >&2; exit 1; }; }
assert_lacks() { ! grep -Fq -- "$2" <<< "$1" || { echo "unexpected sudoers entry: $2" >&2; exit 1; }; }

was="$(render was 1)"
assert_has "$was" 'deploysvc ALL=(root) NOPASSWD: /usr/local/deploy/bin/deploy-was-app.sh, /usr/local/deploy/bin/deploy-service-control.sh'
assert_has "$was" '/usr/local/deploy/bin/deploy-runtime.sh'
assert_has "$was" 'diaguser ALL=(root) NOPASSWD: /usr/local/deploy/bin/deploy-read.sh'
assert_lacks "$was" 'deploy-web-'

web="$(render web 1)"
assert_has "$web" '/usr/local/deploy/bin/deploy-web-app.sh'
assert_has "$web" '/usr/local/deploy/bin/deploy-web-static.sh'
assert_has "$web" '/usr/local/deploy/bin/deploy-web-proxy.sh'
assert_has "$web" '/usr/local/deploy/bin/deploy-nginx-policy.sh'
assert_has "$web" '/usr/local/deploy/bin/deploy-service-control.sh'
assert_has "$web" '/usr/local/deploy/bin/deploy-runtime.sh'
assert_lacks "$(grep '^diaguser ALL=' <<< "$web")" 'deploy-runtime'
assert_lacks "$web" 'deploy-was-app.sh'

both_no_read="$(render both 0)"
assert_has "$both_no_read" '/usr/local/deploy/bin/deploy-was-app.sh'
assert_has "$both_no_read" '/usr/local/deploy/bin/deploy-nginx-policy.sh'
assert_has "$both_no_read" '/usr/local/deploy/bin/deploy-service-control.sh'
assert_lacks "$both_no_read" 'diaguser ALL='
assert_lacks "$both_no_read" '__'
component="$ROOT/installer/components/deploy-agent.sh"
grep -Fq 'if [[ "$read_enabled" == 1 ]]; then' "$component" || {
  echo 'component drift check must require deploy-read.sh only when read-agent is enabled' >&2
  exit 1
}
installer="$ROOT/installer/deploy-agent/install-deploy-agent.sh"
line_of() { awk -v pattern="$1" 'index($0, pattern) { print NR; exit }' "$installer"; }
render_line="$(line_of 'render_deploy_agent_sudoers "$sudoers_template"')"
validate_line="$(line_of 'visudo -cf "$SUDOERS_CANDIDATE"')"
switch_line="$(line_of 'mv "$SUDOERS_CANDIDATE" "$SUDOERS_PATH"')"
ensure_user_line="$(line_of '  ensure_deploy_user')"
scripts_line="$(line_of 'install -m 750 -o root -g root "$health_script"')"
[[ -n "$render_line" && -n "$validate_line" && -n "$switch_line" && -n "$ensure_user_line" && -n "$scripts_line" \
  && "$render_line" -lt "$validate_line" && "$validate_line" -lt "$switch_line" && "$switch_line" -lt "$ensure_user_line" && "$ensure_user_line" -lt "$scripts_line" ]] || {
  echo 'installer must render and validate sudoers before file changes, then install the restrictive policy first' >&2
  exit 1
}
grep -Fq 'usermod --shell "$nologin" "$READ_USER"' "$installer" || {
  echo 'no-read mode must disable the read account shell' >&2
  exit 1
}
grep -Fq 'rm -f -- "$home_dir/.ssh/authorized_keys" "$home_dir/.ssh/authorized_keys2"' "$installer" || {
  echo 'no-read mode must remove the managed read SSH key' >&2
  exit 1
}
grep -Fq 'usermod --lock "$1"' "$installer" || {
  echo 'read account must remain password-locked across re-enable' >&2
  exit 1
}
if grep -Eq '(^|[;&|[:space:]])passwd[[:space:]]+-[lu]' "$installer"; then
  echo 'read account password must never be unlocked during re-enable' >&2
  exit 1
fi
grep -Fq 'usermod --shell /bin/bash "$READ_USER"' "$installer" || {
  echo 're-enabling read-agent must restore its command shell' >&2
  exit 1
}
grep -Fq 'install -m 600 -o "$READ_USER" -g "$READ_USER" "$AUTHORIZED_KEY_FILE" "$home_dir/.ssh/authorized_keys"' "$installer" || {
  echo 're-enabling read-agent must restore the configured SSH key' >&2
  exit 1
}
grep -Fq 'Re-enabling read-agent requires --authorized-key-file' "$installer" || {
  echo 're-enabling after no-read mode must require a key to restore access' >&2
  exit 1
}
grep -Fq 'rm -f -- "$INSTALL_BASE/bin/deploy-web-app.sh"' "$installer" || {
  echo 'WAS downgrade must remove stale WEB scripts' >&2
  exit 1
}
grep -Fq 'rm -f -- "$INSTALL_BASE/bin/deploy-was-app.sh"' "$installer" || {
  echo 'WEB downgrade must remove stale WAS script' >&2
  exit 1
}

if render invalid 1 >/dev/null 2>&1; then
  echo 'invalid agent type was accepted' >&2
  exit 1
fi
if render was yes >/dev/null 2>&1; then
  echo 'invalid read-agent flag was accepted' >&2
  exit 1
fi

echo 'PASS: deploy-agent sudoers is role-scoped, optionally read-only, and rejects invalid markers'
