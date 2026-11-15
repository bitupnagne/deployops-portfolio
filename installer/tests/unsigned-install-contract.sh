#!/usr/bin/env bash
# No Docker, package installation, key generation or network calls.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
temp="$(mktemp -d)"
trap 'rm -rf "$temp"' EXIT
errors=0
fail() { errors=$((errors+1)); }
ok() { :; }
err() { printf '%s\n' "$*" >&2; }
eval "$(sed -n '/^manifest_paths_are_safe() {/,/^}/p' "$ROOT/verify-offline-bundle.sh")"
eval "$(sed -n '/^verify_manifest_integrity() {/,/^}/p' "$ROOT/verify-offline-bundle.sh")"
eval "$(sed -n '/^verify_package_lock() {/,/^}/p' "$ROOT/verify-offline-bundle.sh")"
. "$ROOT/lib/rpm-repository.sh"
mkdir -p "$temp/bundles/ubuntu/nginx" "$temp/repository/repodata"
BUNDLE_ROOT="$temp/bundles"
printf 'not a valid manifest\n' > "$BUNDLE_ROOT/MANIFEST.sha256"
verify_manifest=0
verify_manifest_integrity
[[ "$errors" == 0 ]]
verify_manifest=1
verify_manifest_integrity
(( errors > 0 ))
printf 'PASS: no-auth mode ignores SHA inventory; optional mode rejects malformed inventory\n'

directory="$BUNDLE_ROOT/ubuntu/nginx"
printf 'package fixture\n' > "$directory/nginx.deb"
printf 'nginx|1.0|amd64|%064d|nginx.deb\n' 0 > "$directory/PACKAGES.lock"
dpkg-deb() {
  case "$3" in Package) printf nginx;; Version) printf '%s' "${fixture_version:-1.0}";; Architecture) printf amd64;; esac
}
verify_manifest=0; errors=0
verify_package_lock "$directory" deb nginx
[[ "$errors" == 0 ]]
verify_manifest=1; errors=0
verify_package_lock "$directory" deb nginx
(( errors > 0 ))
verify_manifest=0; errors=0; fixture_version=2.0
verify_package_lock "$directory" deb nginx
(( errors > 0 ))
unset fixture_version
rm "$directory/nginx.deb"
errors=0; verify_package_lock "$directory" deb nginx
(( errors > 0 ))
printf 'PASS: stale SHA accepted by default; missing payload and wrong package version remain blocked\n'

printf '<repomd/>\n' > "$temp/repository/repodata/repomd.xml"
OFFLINE_VERIFY_AUTHENTICITY=0
verify_rpm_repository "$temp/repository"
OFFLINE_VERIFY_AUTHENTICITY=1
if verify_rpm_repository "$temp/repository"; then exit 1; fi
OFFLINE_VERIFY_AUTHENTICITY=0
rm "$temp/repository/repodata/repomd.xml"
if verify_rpm_repository "$temp/repository"; then exit 1; fi
printf 'PASS: RPM repo uses ordinary metadata by default and still rejects missing repomd\n'

eval "$(sed -n '/^docker_jq_binary() {/,/^}/p' "$ROOT/components/docker.sh")"
bundle_file() { printf '%s' "$temp/bundled-jq"; }
command() { if [[ "${1:-}" == -v && "${2:-}" == jq ]]; then return 1; fi; builtin command "$@"; }
printf '#!/bin/sh\nexit 0\n' > "$temp/bundled-jq"
chmod +x "$temp/bundled-jq"
BOM_JQ_SHA256="$(printf '%064d' 0)"
OFFLINE_VERIFY_AUTHENTICITY=0
[[ "$(docker_jq_binary)" == "$temp/bundled-jq" ]]
OFFLINE_VERIFY_AUTHENTICITY=1
if docker_jq_binary >/dev/null 2>&1; then exit 1; fi
printf 'PASS: bundled jq has no default SHA authentication gate\n'
unset -f command

bash "$ROOT/verify-offline-bundle.sh" --help > "$temp/help"
grep -Fq 'By default only installation prerequisites' "$temp/help"
grep -Fq -- '--verify-manifest' "$temp/help"
printf 'PASS: verifier help runs without OpenSSL, key or bundle validation\n'

# Execute the actual install.sh argument construction/call with a verifier spy.
# The installer body stays unchanged; package/service functions are never called.
mkdir "$temp/installer-spy"
printf '%s\n' '#!/usr/bin/env bash' 'printf "%s\n" "$OFFLINE_VERIFY_AUTHENTICITY" "$BUNDLE_ROOT" "$BUNDLE_OS" "$@"' > "$temp/installer-spy/verify-offline-bundle.sh"
install_call="$(sed -n '/^  local verify_args=()/,/^  env BUNDLE_ROOT=.*verify-offline-bundle.sh/p' "$ROOT/install.sh" | sed 's/[[:space:]]*\\$//')"
[[ -n "$install_call" ]]
check_install_call() {
  local SCRIPT_DIR="$temp/installer-spy" BUNDLE_ROOT="$temp/bundles" BUNDLE_OS=rocky8
  local require_java_ci="$1"
  export OFFLINE_MANIFEST_PUBLIC_KEY=/not-needed-and-not-present.pem
  export OFFLINE_VERIFY_AUTHENTICITY=1
  eval "$install_call"
}
check_install_call 0 > "$temp/installer.actual"
printf '%s\n' 0 "$temp/bundles" rocky8 > "$temp/installer.expected"
cmp "$temp/installer.expected" "$temp/installer.actual"
check_install_call 1 > "$temp/installer.actual"
printf '%s\n' 0 "$temp/bundles" rocky8 --require-java-ci > "$temp/installer.expected"
cmp "$temp/installer.expected" "$temp/installer.actual"
printf 'PASS: actual install.sh path forces no-auth default, ignores legacy key input and forwards optional Java CI readiness\n'

. "$ROOT/components/common.sh"
JAVA_HOME_DIR="$temp/java-home"
JAVA_PROFILE="$temp/java-profile.sh"
BOM_JAVA_ARCHIVE=java.tar.gz
BOM_JAVA_SHA256="$(printf '%064d' 0)"
java_prepare_parent() { :; }
java_status() { printf installed; }
info() { :; }
success() { :; }
bundle_file() { printf '%s' "$temp/java.tar.gz"; }
mkdir -p "$JAVA_HOME_DIR/bin" "$temp/jdk-fixture/jdk/bin"
printf '#!/bin/sh\nprintf '\''openjdk version "17.0.1"\\n'\''\n' > "$JAVA_HOME_DIR/bin/java"
chmod +x "$JAVA_HOME_DIR/bin/java"
printf existing-installation > "$JAVA_HOME_DIR/existing.marker"
printf 'damaged archive' > "$temp/java.tar.gz"
OFFLINE_VERIFY_AUTHENTICITY=0
if java_install force >"$temp/java-error" 2>&1; then exit 1; fi
[[ "$(cat "$JAVA_HOME_DIR/existing.marker")" == existing-installation ]]
cp "$JAVA_HOME_DIR/bin/java" "$temp/jdk-fixture/jdk/bin/java"
tar -czf "$temp/java.tar.gz" -C "$temp/jdk-fixture" jdk
if java_install force >"$temp/java-error" 2>&1; then exit 1; fi
[[ "$(cat "$JAVA_HOME_DIR/existing.marker")" == existing-installation ]]
printf '#!/bin/sh\nprintf '\''openjdk version "21.0.9"\\n'\''\n' > "$temp/jdk-fixture/jdk/bin/java"
chmod +x "$temp/jdk-fixture/jdk/bin/java"
tar -czf "$temp/java.tar.gz" -C "$temp/jdk-fixture" jdk
java_install force >"$temp/java-result" 2>&1
[[ ! -e "$JAVA_HOME_DIR/existing.marker" && -x "$JAVA_HOME_DIR/bin/java" && -f "$JAVA_PROFILE" ]]
printf 'PASS: damaged archives and wrong Java versions preserve old JDK; readable Java 21 installs without SHA gate\n'

jq_tool="${TEST_JQ_BINARY:-$(builtin command -v jq || true)}"
[[ -n "$jq_tool" ]] || { printf 'TEST_JQ_BINARY or jq is required for seed JSON regression\n' >&2; exit 1; }
test_jq() { "$jq_tool" -b "$@"; }
jq_command="$jq_tool"
[[ "$jq_tool" != *.exe ]] || jq_command=test_jq
. "$ROOT/lib/java-ci-payload.sh"
payload="$temp/java-ci"
mkdir -p "$payload/inputs/builder" "$payload/inputs/runtime" "$payload/maven-seed/repository"
printf archive > "$payload/builder.tar"
printf archive > "$payload/runtime.tar"
printf pom > "$payload/maven-seed/repository/fixture.pom"
printf '%s\n' '{"images":{"builder":{"archive":"builder.tar"},"runtime":{"archive":"runtime.tar"}}}' > "$payload/CI-PAYLOAD.json"
printf '%s\n' '{"files":[{"path":"repository/fixture.pom"}]}' > "$payload/maven-seed/seed.lock.json"
java_ci_install_readiness "$payload" "$jq_command"
printf 'malformed JSON\n' > "$payload/maven-seed/seed.lock.json"
if java_ci_install_readiness "$payload" "$jq_command" >/dev/null 2>&1; then exit 1; fi
printf '%s\n' '{"files":"invalid-schema"}' > "$payload/maven-seed/seed.lock.json"
if java_ci_install_readiness "$payload" "$jq_command" >/dev/null 2>&1; then exit 1; fi
printf '%s\n' '{"files":[{"path":"repository/missing.pom"}]}' > "$payload/maven-seed/seed.lock.json"
if java_ci_install_readiness "$payload" "$jq_command" >/dev/null 2>&1; then exit 1; fi
printf 'PASS: actual jq rejects malformed seed JSON, wrong schema and missing seed files in default mode\n'

test_python="${TEST_PYTHON_BINARY:-$(builtin command -v python3 || true)}"
[[ -n "$test_python" ]] || { printf 'TEST_PYTHON_BINARY or python3 is required for embedded JAR validation\n' >&2; exit 1; }
python3() { "$test_python" "$@"; }
python3 - "$temp" <<'PY'
import json, sys, zipfile
from pathlib import Path
root = Path(sys.argv[1])
data = {
    'spring': {'datasource': {'url': 'jdbc:mariadb://127.0.0.1:3306/fixture', 'username': 'fixture', 'password': 'fixture-password'}},
    'server': {'port': 9090},
    'app': {'crypto': {'secret-key': 'fixture-key-not-a-real-secret', 'key-id': 'fixture', 'previous-keys': ''},
            'sso': {'shared-secret': 'fixture-sso-not-a-real-secret'},
            'bootstrap': {'admin-password': 'fixture-admin-password', 'image-transfer-mode': 'push'},
            'nginx': {'upload-dir': '/opt/deploy-system/uploads'}},
    'deploy': {'configuration': {'embedded': True}}}
with zipfile.ZipFile(root / 'valid.jar', 'w') as jar:
    jar.writestr('BOOT-INF/classes/application-install.yml', json.dumps(data))
with zipfile.ZipFile(root / 'invalid.jar', 'w') as jar:
    jar.writestr('fixture.txt', 'embedded config deliberately absent')
PY
SCRIPT_DIR="$ROOT"
. "$ROOT/components/deploy-system.sh"
BUNDLE_ROOT="$temp"; BOM_DEPLOYOPS_JAR=valid.jar
deploy_system_validate_embedded_config
BOM_DEPLOYOPS_JAR=invalid.jar
if deploy_system_validate_embedded_config >/dev/null 2>&1; then exit 1; fi
eval "$(sed -n '/^required_vars_for() {/,/^}/p' "$ROOT/install.sh")"
[[ -z "$(required_vars_for deploy-system)" ]]
printf 'PASS: actual Deploy JAR validator accepts embedded settings without answer secrets and rejects missing config\n'
