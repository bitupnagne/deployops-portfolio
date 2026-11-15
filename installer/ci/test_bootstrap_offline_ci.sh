#!/usr/bin/env bash
# Negative entry-point contracts only; no Docker daemon or Nexus is contacted.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
temp="$(mktemp -d /tmp/offline-ci-bootstrap-contract.XXXXXX)"
cleanup() { [[ "$temp" == /tmp/offline-ci-bootstrap-contract.* && -d "$temp" && ! -L "$temp" ]] && rm -rf -- "$temp"; }
trap cleanup EXIT
mkdir "$temp/bin"
printf '#!/bin/sh\necho called >> "$BOOTSTRAP_DOCKER_SPY"\nexit 99\n' > "$temp/bin/docker"
chmod 700 "$temp/bin/docker"
export PATH="$temp/bin:$PATH" BOOTSTRAP_DOCKER_SPY="$temp/docker-called"
printf 'untrusted fixture never reaches signature verification\n' > "$temp/key"
printf '{"username":"fixture","password":"fixture"}\n' > "$temp/auth"
chmod 600 "$temp/auth"
ln -s "$temp/auth" "$temp/auth-link"
refuse() {
  local name="$1" expected="$2"; shift 2
  if bash "$HERE/bootstrap-offline-ci.sh" "$@" > "$temp/result" 2>&1; then printf 'FAIL: %s unexpectedly succeeded\n' "$name"; exit 1; fi
  [[ ! -e "$temp/run" && ! -e "$temp/docker-called" ]] || { printf 'FAIL: %s caused state/Docker mutation\n' "$name"; exit 1; }
  grep -Fq -- "$expected" "$temp/result" || { printf 'FAIL: %s refused at the wrong guard\n' "$name"; exit 1; }
  printf 'PASS: %s\n' "$name"
}
refuse default 'Select one explicit operation'
refuse missing-eula 'Administrator must review/accept Nexus EULA' --operation seed-dry-run
refuse unsafe-network 'Invalid utility network' --operation prepare-images --post-eula-confirmed --utility-network 'bridge;touch /tmp/x'
refuse credential-symlink 'Credential files must be regular root-owned' --operation seed-dry-run --post-eula-confirmed --trusted-public-key "$temp/key" --state-dir "$temp/run" --nexus-url https://nexus.example --maven-auth-file "$temp/auth-link"
chmod 644 "$temp/auth"
refuse world-readable-credential 'Credential files must be regular root-owned' --operation seed-dry-run --post-eula-confirmed --trusted-public-key "$temp/key" --state-dir "$temp/run" --nexus-url https://nexus.example --maven-auth-file "$temp/auth"
chmod 600 "$temp/auth"
refuse latest-tag 'Fixed registry/repository/tag required' --operation publish-images --post-eula-confirmed --trusted-public-key "$temp/key" --state-dir "$temp/run" --nexus-url https://registry.example:8443 --registry registry.example --registry-auth-file "$temp/auth" --policy-auth-file "$temp/auth" --docker-repository ci-bases --builder-repository ci/builder --runtime-repository ci/runtime --tag latest
printf 'All six entry-point refusal contracts passed; this is not install/import/publication QA.\n'
