#!/usr/bin/env bash
# Regression coverage for active Nginx root-directive diagnostics.
set -euo pipefail

repo="$(cd "$(dirname "$0")/../.." && pwd)"
# Load production functions without running the command-line entry point.
source <(sed '/^main "\$@"$/d' "$repo/installer/deploy-agent/web/deploy-web-static.sh")

fixture=''
nginx(){
  [[ "${1:-}" == '-T' ]] || return 2
  printf '%s\n' "$fixture"
}

fixture=$'# active configuration\nroot "/var/www/app/current" ;\nroot /var/www/app/current ; # inline comment\n# root /var/www/app/current;\nroot /var/www/app/currently;'
output="$(warn_if_web_root_not_served /var/www/app 2>&1)"
grep -Fx 'ACTIVE_ROOT_DIRECTIVE=true' <<<"$output" >/dev/null
fixture=$'root /var/www/app/current/;'
output="$(warn_if_web_root_not_served /var/www/app 2>&1)"
grep -Fx 'ACTIVE_ROOT_DIRECTIVE=true' <<<"$output" >/dev/null
fixture="server { listen 80; root /var/www/app/current/; }"
output="$(warn_if_web_root_not_served /var/www/app 2>&1)"
grep -Fx 'ACTIVE_ROOT_DIRECTIVE=true' <<<"$output" >/dev/null
fixture="server { location /x { try_files \$uri \$uri/ =404; } root /var/www/app/current/; }"
output="$(warn_if_web_root_not_served /var/www/app 2>&1)"
grep -Fx 'ACTIVE_ROOT_DIRECTIVE=true' <<<"$output" >/dev/null
fixture="root '/var/www/app/current/';"
output="$(warn_if_web_root_not_served /var/www/app 2>&1)"
grep -Fx 'ACTIVE_ROOT_DIRECTIVE=true' <<<"$output" >/dev/null
if grep -F 'WEB_ROOT_SERVED=true' <<<"$output" >/dev/null; then
  echo 'Diagnostic overclaimed that the active root directive serves requests.' >&2
  exit 1
fi

fixture=$'# active configuration\nroot /var/www/app;\n# root /var/www/app/current;'
if output="$(warn_if_web_root_not_served /var/www/app 2>&1)"; then
  echo 'A mismatched active Nginx root was accepted.' >&2
  exit 1
fi
grep -Fx 'ACTIVE_ROOT_DIRECTIVE=false' <<<"$output" >/dev/null
grep -F '새 릴리스가 실제 서비스되지 않을 수 있어 배포를 중단합니다.' <<<"$output" >/dev/null

# A stale or wrong server can still return HTTP 200. The active root gate must
# reject it before creating a release tree, so it cannot be reported as deployed.
fixture=$'# configuration file /etc/nginx/conf.d/officesystem.conf:\nserver {\n    root /var/www/app;\n    location / { try_files \$uri \$uri/ =404; }\n}'
tmp="$(mktemp -d -t nginx-root-gate.XXXXXX)"
tmp="$(cd "$tmp" && pwd)"
trap 'rm -rf -- "$tmp"' EXIT
: > "$tmp/bundle.tar.gz"
WEB_ROOT="$tmp/missing/nested/site"
BUNDLE="$tmp/bundle.tar.gz"
BUNDLE_SHA256="$(printf 'a%.0s' {1..64})"
verify_bundle(){ :; }
verify_bundle_members(){ :; }
nginx_ready(){ :; }
if run_deploy >"$tmp/deploy.log" 2>&1; then
  echo 'Deployment passed with an unrelated HTTP-capable root.' >&2
  exit 1
fi
grep -F 'Active Nginx root does not match the static release path' "$tmp/deploy.log" >/dev/null
[[ ! -e "$tmp/missing" && -f "$tmp/bundle.tar.gz" ]]

echo 'PASS: active root diagnostic handles comments and blocks mismatched static deployment before mutation'
