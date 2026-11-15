#!/usr/bin/env bash
# Exercises actual static deploy/rollback functions twice with a frozen timestamp.
set -euo pipefail

repo="$(cd "$(dirname "$0")/../.." && pwd)"
tmp="$(mktemp -d "${TMPDIR:-/tmp}/deploy_static_test.XXXXXX")"
tmp="$(cd "$tmp" && pwd)"
[[ "$tmp" =~ ^/[A-Za-z0-9._/+:-]+$ && "$tmp" != / ]] || exit 1
trap 'rm -rf -- "$tmp"' EXIT
# deploy-web-static.sh validates that every web-root ancestor is traversable by nginx.
# mktemp creates this test parent as 0700, unlike production /var/www and /srv/web.
chmod 755 -- "$tmp"

mkdir -p "$tmp/bin" "$tmp/build-v1" "$tmp/build-v2"
printf 'release-v1\n' > "$tmp/build-v1/index.html"
printf 'release-v2\n' > "$tmp/build-v2/index.html"
tar -czf "$tmp/v1.tar.gz" -C "$tmp/build-v1" .
tar -czf "$tmp/v2.tar.gz" -C "$tmp/build-v2" .
cat > "$tmp/bin/healthcheck" <<'EOF'
#!/usr/bin/env bash
[[ "$*" == '--port 18090 --path / --host fixture.example.invalid' ]]
EOF
chmod +x "$tmp/bin/healthcheck"

# Keep the production functions and argument parser, while isolating external service actions.
source <(sed '/^main "\$@"$/d' "$repo/installer/deploy-agent/web/deploy-web-static.sh")
require_root(){ :; }
nginx_ready(){ :; }
warn_if_web_root_not_served(){ :; }
active_health_host_for_web_root(){ printf 'fixture.example.invalid\n'; }
date(){ printf '20260924123456\n'; }
CONFIG_FILE="$tmp/nonexistent.conf"
HEALTHCHECK_SCRIPT="$tmp/bin/healthcheck"
LOCK_FILE="$tmp/deploy.lock"
web_root="$tmp/site"

sha1="$(sha256sum "$tmp/v1.tar.gz" | awk '{print $1}')"
sha2="$(sha256sum "$tmp/v2.tar.gz" | awk '{print $1}')"
main deploy --bundle "$tmp/v1.tar.gz" --bundle-sha256 "$sha1" \
  --web-root "$web_root" --health-port 18090 --health-path / --reload-nginx false
first="$(readlink -f "$web_root/current")"
[[ "$(cat "$web_root/current/index.html")" == release-v1 ]]

main deploy --bundle "$tmp/v2.tar.gz" --bundle-sha256 "$sha2" \
  --web-root "$web_root" --health-port 18090 --health-path / --reload-nginx false
second="$(readlink -f "$web_root/current")"
[[ "$first" != "$second" ]]
[[ "${first##*/}" == 20260924123456-* && "${second##*/}" == 20260924123456-* ]]
[[ "$(cat "$web_root/current/index.html")" == release-v2 ]]

main rollback --web-root "$web_root" --reload-nginx false
[[ "$(readlink -f "$web_root/current")" == "$first" ]]
[[ "$(cat "$web_root/current/index.html")" == release-v1 ]]

printf 'PASS: same-second releases remain unique and rollback restores the prior release\n'
