#!/usr/bin/env bash
# Real entrypoints and flock. Redirect only lock paths into a temporary fixture.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
tmp="$(mktemp -d /tmp/deploy-nginx-lock.XXXXXXXX)"
trap 'rm -rf -- "$tmp"' EXIT
mkdir "$tmp/locks" "$tmp/bin"
for name in deploy-web-app deploy-web-static deploy-web-proxy deploy-nginx-policy; do
  sed "s|/var/lock/|$tmp/locks/|g" "$repo/installer/deploy-agent/web/$name.sh" > "$tmp/$name.sh"
  bash -n "$tmp/$name.sh"
done
for name in docker nginx systemctl; do
  printf '#!/bin/bash\necho unexpected-%s >> "%s"\nexit 99\n' "$name" "$tmp/mutations" > "$tmp/bin/$name"
  chmod +x "$tmp/bin/$name"
done
export PATH="$tmp/bin:$PATH"
exec 7>"$tmp/locks/deploy-ops-nginx.lock"
flock -n 7
for name in deploy-web-app deploy-web-static deploy-web-proxy deploy-nginx-policy; do
  case "$name" in
    deploy-web-app) args=(deploy --container fixture --image fixture:tag --reload-nginx true);;
    deploy-web-static) args=(deploy --web-root /var/www/fixture);;
    deploy-web-proxy) args=(reload --health-port 8080);;
    deploy-nginx-policy) args=(apply --policy-name fixture);;
  esac
  if bash "$tmp/$name.sh" "${args[@]}" > "$tmp/$name.log" 2>&1; then
    echo "$name ignored common nginx lock"; exit 1
  fi
  grep -Eq 'nginx.*진행|nginx operation is in progress' "$tmp/$name.log"
  [[ ! -e "$tmp/mutations" ]]
done
# Downtime permission is explicit, strict and single-valued before touching any service.
for value in true false; do
  if bash "$tmp/deploy-nginx-policy.sh" traffic-drain --rollout-allow-empty "$value" > "$tmp/allow-empty.log" 2>&1; then
    echo 'traffic drain ignored nginx lock'; exit 1
  fi
  grep -q 'nginx operation is in progress' "$tmp/allow-empty.log"
done
for value in yes 1 ''; do
  if bash "$tmp/deploy-nginx-policy.sh" traffic-drain --rollout-allow-empty "$value" > "$tmp/allow-empty.log" 2>&1; then
    echo 'invalid downtime permission accepted'; exit 1
  fi
  grep -q -- '--rollout-allow-empty' "$tmp/allow-empty.log"
done
for invalid in missing duplicate; do
  args=(traffic-drain --rollout-allow-empty)
  [[ "$invalid" != duplicate ]] || args+=(true --rollout-allow-empty false)
  if bash "$tmp/deploy-nginx-policy.sh" "${args[@]}" > "$tmp/allow-empty.log" 2>&1; then
    echo 'missing or repeated downtime permission accepted'; exit 1
  fi
  grep -q -- '--rollout-allow-empty' "$tmp/allow-empty.log"
done
# Read-only capabilities neither contend on the common lock nor create per-agent locks.
bash "$tmp/deploy-web-static.sh" capabilities > "$tmp/capabilities"
grep -qx EXACT_RESTORE_VERSION=1 "$tmp/capabilities"
[[ ! -e "$tmp/locks/deploy-ops-web-static.lock" ]]
[[ ! -e "$tmp/locks/deploy-ops-web-proxy.lock" ]]
[[ ! -e "$tmp/locks/deploy-ops-nginx-policy.lock" ]]
echo 'PASS: all four WEB mutation entrypoints reject common nginx lock contention before service changes; capabilities remain read-only'
