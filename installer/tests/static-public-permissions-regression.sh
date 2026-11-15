#!/usr/bin/env bash
# Regression coverage for WEB static permissions and failed-release rollback selection.
set -euo pipefail

repo="$(cd "$(dirname "$0")/../.." && pwd)"
[[ "${EUID:-$(id -u)}" -eq 0 ]] || {
  echo 'Run this regression as root; it checks root ownership and nginx-user access.' >&2
  exit 1
}
nginx_user="${NGINX_TEST_USER:-nginx}"
id "$nginx_user" >/dev/null 2>&1 || nginx_user=nobody
id "$nginx_user" >/dev/null 2>&1 || { echo 'Neither nginx nor nobody test user exists.' >&2; exit 1; }
command -v runuser >/dev/null 2>&1 || { echo 'runuser is required.' >&2; exit 1; }

tmp="$(mktemp -d "${TMPDIR:-/tmp}/deploy-static-permissions.XXXXXX")"
tmp="$(cd "$tmp" && pwd)"
[[ "$tmp" =~ ^/[A-Za-z0-9._/+:-]+$ && "$tmp" != / ]] || exit 1
trap 'rm -rf -- "$tmp"' EXIT
chmod 0755 "$tmp"
umask 077

# Check the actual Nginx installer helper under its restrictive install-time umask.
source "$repo/installer/components/nginx.sh"
err(){ echo "$*" >&2; }
installer_root="$tmp/installer-site"
_nginx_prepare_static_root "$installer_root"
[[ "$(stat -c '%a:%U:%G' "$installer_root")" == '755:root:root' ]]
[[ "$(stat -c '%a:%U:%G' "$installer_root/releases")" == '755:root:root' ]]
_nginx_prepare_static_root "$tmp/missing-a/missing-b/site"
[[ "$(stat -c '%a:%U:%G' "$tmp/missing-a")" == '755:root:root' ]]
[[ "$(stat -c '%a:%U:%G' "$tmp/missing-a/missing-b")" == '755:root:root' ]]

# Source the production dispatcher while replacing only root/service entry points.
source <(sed '/^main "\$@"$/d' "$repo/installer/deploy-agent/web/deploy-web-static.sh")
require_root(){ :; }
nginx_ready(){ :; }
warn_if_web_root_not_served(){ :; }
active_health_host_for_web_root(){ printf 'localhost\n'; }
root_before="$(stat -c '%a:%U:%G:%i' /)"
releases_before=missing
[[ ! -e /releases ]] || releases_before="$(stat -c '%a:%U:%G:%i' /releases)"
LOCK_FILE="$tmp/invalid-alias.lock"
if (main deploy --web-root '/var/..' --reload-nginx false) >"$tmp/invalid-alias.log" 2>&1; then
  echo 'Dispatcher accepted unsafe root alias.' >&2
  exit 1
fi
[[ ! -e "$LOCK_FILE" ]]
LOCK_FILE="$tmp/deploy.lock"
for invalid_root in '/var/..' '/tmp/.' '//etc' '/'; do
  if _nginx_prepare_static_root "$invalid_root" >"$tmp/invalid-nginx-root.log" 2>&1; then
    echo "Nginx installer accepted unsafe root alias: $invalid_root" >&2
    exit 1
  fi
  if safe_path "$invalid_root"; then
    echo "Deploy agent accepted unsafe root alias: $invalid_root" >&2
    exit 1
  fi
done
[[ "$(stat -c '%a:%U:%G:%i' /)" == "$root_before" ]]
releases_after=missing
[[ ! -e /releases ]] || releases_after="$(stat -c '%a:%U:%G:%i' /releases)"
[[ "$releases_after" == "$releases_before" ]]
nginx(){ [[ "$*" == '-t' && "${TEST_NGINX_TEST:-pass}" == pass ]]; }
systemctl(){ [[ "$*" == 'reload nginx' && "${TEST_NGINX_RELOAD:-pass}" == pass ]]; }
tar(){
  if [[ "${TEST_TAR_EXTRACT:-pass}" == fail && "${1:-}" == '-xzf' ]]; then
    local destination='' previous=''
    for argument in "$@"; do
      [[ "$previous" == '-C' ]] && destination="$argument"
      previous="$argument"
    done
    printf 'partial extraction\n' > "$destination/partial.html"
    return 1
  fi
  command tar "$@"
}
mv(){
  if [[ "${TEST_MV_ACTIVATE:-pass}" == fail && "${1:-}" == '-Tf' ]]; then return 1; fi
  command mv "$@"
}
CONFIG_FILE="$tmp/no-config"
LOCK_FILE="$tmp/deploy.lock"
HEALTHCHECK_SCRIPT="$tmp/health-pass"
cat > "$tmp/health-pass" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
cat > "$tmp/health-fail" <<'EOF'
#!/usr/bin/env bash
exit 1
EOF
chmod 0755 "$tmp/health-pass" "$tmp/health-fail"

make_bundle(){
  local label="$1" content="$2" build="$tmp/build-$1"
  # The production image extraction temp directory is private (0700); tar stores
  # that top-level mode, so the deploy agent must normalize extracted releases.
  mkdir -m 0700 "$build"
  printf '%s\n' "$content" > "$build/index.html"
  chmod 0600 "$build/index.html"
  tar -czf "$tmp/$label.tar.gz" -C "$build" .
  BUNDLE_PATH="$tmp/$label.tar.gz"
  BUNDLE_SHA="$(sha256sum "$BUNDLE_PATH" | awk '{print $1}')"
}
deploy_ok(){
  (main deploy --bundle "$1" --bundle-sha256 "$2" --web-root "$3" \
    --health-port 18090 --health-path / --reload-nginx "$4") > "$5" 2>&1
}
deploy_fail(){
  local status=0
  if (main deploy --bundle "$1" --bundle-sha256 "$2" --web-root "$3" \
      --health-port 18090 --health-path / --reload-nginx "$4") > "$5" 2>&1; then
    echo 'Expected deployment failure unexpectedly succeeded.' >&2
    return 1
  else
    status=$?
  fi
  [[ "$status" -eq 1 ]]
}
current_target(){ readlink -f "$1/current"; }
assert_only_release(){
  local root="$1" expected_count="$2" actual
  actual="$(find "$root/releases" -mindepth 1 -maxdepth 1 -type d | wc -l | tr -d ' ')"
  [[ "$actual" == "$expected_count" ]]
}

# A private parent must fail before any path is created or changed.
mkdir -m 0700 "$tmp/private-parent"
if _nginx_prepare_static_root "$tmp/private-parent/site" >"$tmp/private-nginx.log" 2>&1; then
  echo 'Nginx installation unexpectedly accepted a private parent.' >&2
  exit 1
fi
[[ ! -e "$tmp/private-parent/site" ]]
grep -F 'nginx worker' "$tmp/private-nginx.log" >/dev/null
make_bundle private-check private
if (main deploy --bundle "$BUNDLE_PATH" --bundle-sha256 "$BUNDLE_SHA" \
  --web-root "$tmp/private-parent/site" --reload-nginx false) >"$tmp/private.log" 2>&1; then
  echo 'Deployment through a private parent unexpectedly proceeded.' >&2
  exit 1
fi
[[ ! -e "$tmp/private-parent/site" ]]
[[ "$(stat -c '%a' "$tmp/private-parent")" == '700' ]]
grep -F 'not publicly traversable' "$tmp/private.log" >/dev/null

# The installer must propagate the same path validation failure before writing config
# or restarting nginx; the actual installer has no global errexit guarantee.
NGINX_CONF_RHEL="$tmp/nginx/conf.d/officesystem.conf"
NGINX_CONF_DEB="$NGINX_CONF_RHEL"
OS_FAMILY=rhel
nginx_status(){ printf 'installed\n'; }
section(){ :; }
info(){ :; }
success(){ :; }
ask(){
  case "$1" in
    NGINX_UPSTREAM_HOST) printf '127.0.0.1\n' ;;
    NGINX_MAX_BODY) printf '0\n' ;;
    NGINX_CUSTOM_SERVICES) printf '\n' ;;
    NGINX_STATIC_ROOT) printf '%s\n' "$tmp/private-parent/site" ;;
    NGINX_STATIC_LISTEN) printf '18080\n' ;;
    *) echo "unexpected installer prompt: $1" >&2; return 1 ;;
  esac
}
ask_confirm(){ return 1; }
systemctl(){ printf '%s\n' "$*" >> "$tmp/installer-systemctl.log"; }
if nginx_install >"$tmp/nginx-install.log" 2>&1; then
  echo 'Nginx installer unexpectedly accepted a private static-root parent.' >&2
  exit 1
fi
[[ ! -e "$NGINX_CONF_RHEL" ]]
! grep -F 'restart nginx' "$tmp/installer-systemctl.log" >/dev/null
[[ ! -e "/tmp/officesystem.nginx.$$" ]]

# Reject symlink ancestors even when the link target itself is private.
mkdir -m 0700 "$tmp/private-target"
ln -s "$tmp/private-target" "$tmp/private-parent-link"
if _nginx_prepare_static_root "$tmp/private-parent-link/site" >"$tmp/symlink-nginx.log" 2>&1; then
  echo 'Nginx installer unexpectedly accepted a symlink ancestor.' >&2
  exit 1
fi
if (main deploy --bundle "$BUNDLE_PATH" --bundle-sha256 "$BUNDLE_SHA" \
  --web-root "$tmp/private-parent-link/site" --reload-nginx false) >"$tmp/symlink-agent.log" 2>&1; then
  echo 'Deploy agent unexpectedly accepted a symlink ancestor.' >&2
  exit 1
fi
[[ ! -e "$tmp/private-target/site" ]]
[[ "$(stat -c '%a' "$tmp/private-target")" == '700' ]]
grep -F 'symbolic link' "$tmp/symlink-agent.log" >/dev/null

# Restore the runtime reload mock for deployment-path tests below.
systemctl(){ [[ "$*" == 'reload nginx' && "${TEST_NGINX_RELOAD:-pass}" == pass ]]; }

# First deployment failing health must leave no current symlink or failed candidate.
make_bundle first-fail first-bad
HEALTHCHECK_SCRIPT="$tmp/health-fail"
first_root="$tmp/first-parent/new/first-site"
deploy_fail "$BUNDLE_PATH" "$BUNDLE_SHA" "$first_root" false "$tmp/first-fail.log"
[[ ! -e "$first_root/current" && ! -L "$first_root/current" ]]
assert_only_release "$first_root" 0
[[ "$(stat -c '%a:%U:%G' "$first_root")" == '755:root:root' ]]
[[ "$(stat -c '%a:%U:%G' "$first_root/releases")" == '755:root:root' ]]
[[ "$(stat -c '%a:%U:%G' "$tmp/first-parent")" == '755:root:root' ]]
[[ "$(stat -c '%a:%U:%G' "$tmp/first-parent/new")" == '755:root:root' ]]

# Healthy A -> failing B restores A and deletes B -> healthy C -> manual rollback returns A.
cycle_root="$tmp/cycle-site"
HEALTHCHECK_SCRIPT="$tmp/health-pass"
make_bundle cycle-a good-A
deploy_ok "$BUNDLE_PATH" "$BUNDLE_SHA" "$cycle_root" false "$tmp/cycle-a.log"
release_a="$(current_target "$cycle_root")"
[[ "$(stat -c '%a:%U:%G' "$release_a")" == '755:root:root' ]]
[[ "$(stat -c '%a:%U:%G' "$release_a/index.html")" == '644:root:root' ]]
runuser -u "$nginx_user" -- test -x "$cycle_root"
runuser -u "$nginx_user" -- test -x "$cycle_root/releases"
runuser -u "$nginx_user" -- test -x "$cycle_root/current"
runuser -u "$nginx_user" -- test -r "$cycle_root/current/index.html"
[[ "$(cat "$cycle_root/current/index.html")" == good-A ]]

make_bundle cycle-b bad-B
HEALTHCHECK_SCRIPT="$tmp/health-fail"
deploy_fail "$BUNDLE_PATH" "$BUNDLE_SHA" "$cycle_root" false "$tmp/cycle-b.log"
[[ "$(current_target "$cycle_root")" == "$release_a" ]]
assert_only_release "$cycle_root" 1

# A partially extracted candidate cannot become the next manual rollback target.
make_bundle cycle-extract-b partial-B
TEST_TAR_EXTRACT=fail deploy_fail "$BUNDLE_PATH" "$BUNDLE_SHA" "$cycle_root" false "$tmp/cycle-extract-b.log"
[[ "$(current_target "$cycle_root")" == "$release_a" ]]
assert_only_release "$cycle_root" 1

# Failed atomic activation is also cleaned without disturbing the current release.
make_bundle cycle-activate-b activation-B
TEST_MV_ACTIVATE=fail deploy_fail "$BUNDLE_PATH" "$BUNDLE_SHA" "$cycle_root" false "$tmp/cycle-activate-b.log"
[[ "$(current_target "$cycle_root")" == "$release_a" ]]
[[ ! -e "$cycle_root/current.tmp" && ! -L "$cycle_root/current.tmp" ]]
assert_only_release "$cycle_root" 1

make_bundle cycle-c good-C
HEALTHCHECK_SCRIPT="$tmp/health-pass"
deploy_ok "$BUNDLE_PATH" "$BUNDLE_SHA" "$cycle_root" false "$tmp/cycle-c.log"
[[ "$(cat "$cycle_root/current/index.html")" == good-C ]]
(main rollback --web-root "$cycle_root" --reload-nginx false) >"$tmp/cycle-rollback.log" 2>&1
[[ "$(current_target "$cycle_root")" == "$release_a" ]]
[[ "$(cat "$cycle_root/current/index.html")" == good-A ]]
assert_only_release "$cycle_root" 2

# nginx -t and reload failures both restore A and discard their failed B candidates.
for failure in nginx-test nginx-reload; do
  failure_root="$tmp/$failure-site"
  make_bundle "$failure-a" "good-$failure-A"
  HEALTHCHECK_SCRIPT="$tmp/health-pass"
  TEST_NGINX_TEST=pass TEST_NGINX_RELOAD=pass \
    deploy_ok "$BUNDLE_PATH" "$BUNDLE_SHA" "$failure_root" true "$tmp/$failure-a.log"
  release_a="$(current_target "$failure_root")"
  make_bundle "$failure-b" "bad-$failure-B"
  if [[ "$failure" == nginx-test ]]; then
    TEST_NGINX_TEST=fail TEST_NGINX_RELOAD=pass \
      deploy_fail "$BUNDLE_PATH" "$BUNDLE_SHA" "$failure_root" true "$tmp/$failure-b.log"
  else
    TEST_NGINX_TEST=pass TEST_NGINX_RELOAD=fail \
      deploy_fail "$BUNDLE_PATH" "$BUNDLE_SHA" "$failure_root" true "$tmp/$failure-b.log"
  fi
  [[ "$(current_target "$failure_root")" == "$release_a" ]]
  assert_only_release "$failure_root" 1
done

printf 'PASS: umask 077 public modes/readability, private-parent fail-fast, first-deploy cleanup, A/B/C rollback, and nginx test/reload failure cleanup\n'
