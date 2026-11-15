#!/usr/bin/env bash
# Exercises the actual static status action against temporary releases; no real service commands run.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
tmp="$(mktemp -d -t deploy-static-status.XXXXXX)"
tmp="$(cd "$tmp" && pwd)"
[[ "$tmp" == */deploy-static-status.* && "$tmp" != / ]] || exit 1
trap 'rm -rf -- "$tmp"' EXIT
mkdir -p "$tmp/bin" "$tmp/site/releases/r1" "$tmp/outside"
printf 'unchanged\n' > "$tmp/site/releases/r1/index.html"
printf 'touch "%s/config-was-sourced"\n' "$tmp" > "$tmp/config"
cat > "$tmp/bin/systemctl" <<'EOF'
#!/usr/bin/env bash
[[ "$*" == 'is-active nginx' ]] || exit 99
printf '%s\n' "${TEST_NGINX_STATE:-active}"
[[ "${TEST_NGINX_STATE:-active}" == active ]]
EOF
for cmd in docker nginx flock install; do
  printf '#!/usr/bin/env bash\nprintf "unexpected command\\n" >&2\nexit 99\n' > "$tmp/bin/$cmd"
done
chmod +x "$tmp/bin/"*
export PATH="$tmp/bin:$PATH"

# Replace only the process entry point and the root check, retaining actual main/status/argument parsing.
source <(sed '/^main "\$@"$/d' "$repo/installer/deploy-agent/web/deploy-web-static.sh")
require_root(){ :; }
CONFIG_FILE="$tmp/config"
LOCK_FILE="$tmp/must-not-create.lock"

missing="$(main status --web-root "$tmp/site")"
[[ "$missing" == *'RELEASE_EXISTS=false'* && "$missing" == *'NGINX_ACTIVE=true'* ]]
ln -s "$tmp/site/releases/r1" "$tmp/site/current"
present="$(main status --web-root "$tmp/site")"
[[ "$present" == *'RELEASE_EXISTS=true'* && "$present" == *"CURRENT_RELEASE=$tmp/site/releases/r1"* ]]
stopped="$(TEST_NGINX_STATE=inactive main status --web-root "$tmp/site")"
[[ "$stopped" == *'RELEASE_EXISTS=true'* && "$stopped" == *'NGINX_ACTIVE=false'* ]]
unknown="$(TEST_NGINX_STATE=unknown main status --web-root "$tmp/site")"
[[ "$unknown" == *'NGINX_ACTIVE=unknown'* ]]
rm -- "$tmp/site/current"
ln -s "$tmp/outside" "$tmp/site/current"
outside="$(main status --web-root "$tmp/site")"
[[ "$outside" == *'RELEASE_EXISTS=false'* && "$outside" != *"CURRENT_RELEASE=$tmp/outside"* ]]
if (main status --web-root "$tmp/site/../outside") >/dev/null 2>&1; then
  echo 'Unsafe WebRoot was accepted' >&2
  exit 1
fi
[[ ! -e "$tmp/must-not-create.lock" && ! -e "$tmp/config-was-sourced" ]]
[[ "$(cat "$tmp/site/releases/r1/index.html")" == unchanged ]]
printf 'PASS: static status missing/present/stopped/unknown/escaped path, no lock/config source or service mutation\n'
