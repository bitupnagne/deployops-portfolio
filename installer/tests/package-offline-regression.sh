#!/usr/bin/env bash
# 실제 패키지 설치/네트워크 없이 설치 함수와 OS별 lock 연결을 검증한다.
set -euo pipefail
# 기존 상세 검증 호환 모드의 회귀 계약이다.
export OFFLINE_VERIFY_AUTHENTICITY=1
verify_manifest=1
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
task_tmp="$(mktemp -d)"
trap 'rm -rf "$task_tmp"' EXIT
. "$SCRIPT_DIR/lib/os.sh"
eval "$(sed -n '/^verify_os_bundle() {/,/^}/p' "$SCRIPT_DIR/verify-offline-bundle.sh")"
eval "$(sed -n '/^verify_package_lock() {/,/^}/p' "$SCRIPT_DIR/verify-offline-bundle.sh")"
eval "$(sed -n '/^manifest_paths_are_safe() {/,/^}/p' "$SCRIPT_DIR/verify-offline-bundle.sh")"
if [[ "${1:-}" == --manifest-only ]]; then
  manifest_paths_are_safe "$2"
  printf 'Actual manifest path validation: PASS (%s)\n' "$2"
  exit 0
fi
if [[ "${1:-}" == --manifest-stage ]]; then
  BUNDLE_ROOT="$2"; errors=0; verify_manifest=1
  trusted_public_key="${OFFLINE_MANIFEST_PUBLIC_KEY:-}"; require_signature=1
  fail() { errors=$((errors + 1)); printf '[FAIL] %s\n' "$*" >&2; }
  ok() { printf '[OK] %s\n' "$*"; }
  eval "$(sed -n '/^verify_manifest_signature() {/,/^}/p' "$SCRIPT_DIR/verify-offline-bundle.sh")"
  eval "$(sed -n '/^verify_manifest_signature$/,$p' "$SCRIPT_DIR/verify-offline-bundle.sh")"
  exit 0
fi
err() { printf '%s\n' "$*" >&2; }
fail() { errors=$((errors + 1)); }
ok() { :; }
BUNDLE_ROOT="$task_tmp/bundles"
# 이 fixture는 패키지 gate만 담당한다. 실제 key bytes/import는 rpm-signing-keys-regression에서 검증한다.
verify_rpm_signing_keys() { :; }
target_prerequisites_verify_rpm_signatures() {
  [[ "${target_prerequisite_signature_result:-ok}" == ok ]]
}
dpkg-deb() {
  local name="${2##*/}"; name="${name%.deb}"; name="${name%%_*}"
  case "$3" in Package) printf '%s' "$name";; Version) printf '%s' "${metadata_version:-1.0}";; Architecture) printf amd64;; esac
}
rpm() {
  local file="${@: -1}" name
  name="${file##*/}"; name="${name%.rpm}"
  printf '%s|%s|x86_64\n' "$name" "${metadata_version:-1.0}"
}
make_group() {
  local os="$1" group="$2" kind="$3" arch="$4" name sha; shift 4
  mkdir -p "$BUNDLE_ROOT/$os/$group"
  : > "$BUNDLE_ROOT/$os/$group/PACKAGES.lock"
  for name in "$@"; do
    printf 'fixture %s' "$name" > "$BUNDLE_ROOT/$os/$group/$name.$kind"
    sha="$(sha256sum "$BUNDLE_ROOT/$os/$group/$name.$kind" | awk '{print $1}')"
    printf '%s|1.0|%s|%s|%s.%s\n' "$name" "$arch" "$sha" "$name" "$kind" >> "$BUNDLE_ROOT/$os/$group/PACKAGES.lock"
  done
  if [[ "$kind" == rpm ]]; then
    mkdir -p "$BUNDLE_ROOT/$os/$group/repodata"
    printf 'fixture metadata' > "$BUNDLE_ROOT/$os/$group/repodata/repomd.xml"
    (cd "$BUNDLE_ROOT/$os/$group" && sha256sum repodata/repomd.xml > REPODATA.sha256)
  fi
}
for os in ubuntu rocky8; do
  if [[ "$os" == ubuntu ]]; then
    kind=deb; arch=amd64
    make_group "$os" docker "$kind" "$arch" docker.io containerd docker-buildx docker-compose-v2
  else
    kind=rpm; arch=x86_64
    make_group "$os" docker "$kind" "$arch" docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  fi
  make_group "$os" mariadb "$kind" "$arch" mariadb-server
  make_group "$os" nginx "$kind" "$arch" nginx
  if [[ "$os" == rocky8 ]]; then
    make_group "$os" target-prerequisites "$kind" "$arch" \
      openssh-server openssh-clients sudo policycoreutils policycoreutils-python-utils \
      iproute procps-ng shadow-utils util-linux tar gzip curl
  fi
  errors=0; verify_os_bundle "$os"; [[ "$errors" == 0 ]]
  for group in mariadb nginx; do
    lock="$BUNDLE_ROOT/$os/$group/PACKAGES.lock"
    cp "$lock" "$task_tmp/lock.saved"
    rm "$lock"
    errors=0; verify_os_bundle "$os"; (( errors > 0 ))
    cp "$task_tmp/lock.saved" "$lock"
    metadata_version=2.0
    errors=0; verify_package_lock "$BUNDLE_ROOT/$os/$group" "$kind"; (( errors > 0 ))
    unset metadata_version
    errors=0; verify_package_lock "$BUNDLE_ROOT/$os/$group" "$kind" missing-required; (( errors > 0 ))
    printf 'tampered' >> "$BUNDLE_ROOT/$os/$group/$(cut -d'|' -f5 "$lock")"
    errors=0; verify_package_lock "$BUNDLE_ROOT/$os/$group" "$kind"; (( errors > 0 ))
    make_group "$os" "$group" "$kind" "$arch" "$([[ "$group" == nginx ]] && echo nginx || echo mariadb-server)"
  done
done

# The Rocky target prerequisite group is part of the production offline gate;
# reject the bundle when its separate publisher-signature check fails.
target_prerequisite_signature_result=invalid
errors=0; verify_os_bundle rocky8; (( errors > 0 ))
unset target_prerequisite_signature_result

# 정상 apt 파일명은 변환하지 않고 그대로 SHA 검증한다.
epoch_dir="$BUNDLE_ROOT/ubuntu/nginx"
epoch_name='nginx_1%3a1.0~noble_amd64.deb'
mv "$epoch_dir/nginx.deb" "$epoch_dir/$epoch_name"
sed -i "s/|nginx.deb$/|$epoch_name/" "$epoch_dir/PACKAGES.lock"
errors=0; verify_package_lock "$epoch_dir" deb nginx; [[ "$errors" == 0 ]]
manifest_fixture="$task_tmp/MANIFEST.sha256"
(cd "$epoch_dir" && sha256sum "$epoch_name") > "$manifest_fixture"
manifest_paths_are_safe "$manifest_fixture"
(cd "$epoch_dir" && sha256sum -c "$manifest_fixture" >/dev/null)
sed 's/$/\r/' "$manifest_fixture" > "$task_tmp/manifest.crlf"
manifest_paths_are_safe "$task_tmp/manifest.crlf"
for bad_path in '../escape.deb' 'dir/../../escape.deb' '/absolute.deb' 'dir/..' 'dir/back\slash.deb' '$(touch-pwn).deb' 'space name.deb'; do
  printf '%064d  %s\n' 0 "$bad_path" > "$manifest_fixture"
  if manifest_paths_are_safe "$manifest_fixture"; then
    echo "Unsafe manifest path accepted: $bad_path" >&2; exit 1
  fi
done
printf '%064d  ../escape.deb\n' 0 > "$manifest_fixture"
for ((row=0; row<2000; row++)); do
  printf '%064d  normal-%s.deb\n' 0 "$row" >> "$manifest_fixture"
done
if manifest_paths_are_safe "$manifest_fixture"; then
  echo 'Large manifest traversal accepted under pipefail' >&2; exit 1
fi
cp "$epoch_dir/PACKAGES.lock" "$task_tmp/safe.lock"
sed -i "s/|$epoch_name$/|..\/escape.deb/" "$epoch_dir/PACKAGES.lock"
errors=0; verify_package_lock "$epoch_dir" deb nginx; (( errors > 0 ))
cp "$task_tmp/safe.lock" "$epoch_dir/PACKAGES.lock"

OS_FAMILY=debian
mkdir "$task_tmp/install"
touch "$task_tmp/install/nginx.deb"
dpkg() {
  printf 'dpkg %s\n' "$*" >> "$task_tmp/calls"
  case "$1" in
    -i) return "${install_rc:-0}";;
    --configure) return "${configure_rc:-0}";;
    --audit) printf '%s' "${audit_text:-}"; return "${audit_rc:-0}";;
  esac
}
apt-get() {
  printf 'apt-get %s\n' "$*" >> "$task_tmp/calls"
  [[ " $* " == *' --no-download '* ]] || { echo 'online call forbidden' >&2; exit 90; }
  if [[ " $* " == *' install '* ]]; then
    [[ " $* " == *' --no-remove '* ]] || exit 91
    return "${repair_rc:-0}"
  fi
  return "${check_rc:-0}"
}
dpkg-query() { printf '%s' "${installed_state:-install ok installed|1.0|amd64}"; }
expect_failure() {
  if install_local_packages "$task_tmp/install"; then
    echo 'Invalid package state accepted' >&2; exit 1
  fi
}
install_local_packages "$task_tmp/install"
install_rc=1; install_local_packages "$task_tmp/install"
repair_rc=1; expect_failure
unset install_rc repair_rc
configure_rc=1; expect_failure; unset configure_rc
audit_rc=1; expect_failure; unset audit_rc
audit_text=broken; expect_failure; unset audit_text
check_rc=1; expect_failure; unset check_rc
installed_state='deinstall ok config-files|1.0|amd64'; expect_failure
installed_state='install ok installed|0.9|amd64'; expect_failure
unset installed_state
# 실제 install.sh 호출문이 readonly custom root를 자식에 전달해야 한다.
mkdir "$task_tmp/preflight"
printf '%s\n' '#!/usr/bin/env bash' 'printf "%s\n" "$BUNDLE_ROOT" "$BUNDLE_OS" "$@"' > "$task_tmp/preflight/verify-offline-bundle.sh"
preflight_call="$(sed -n '/^[[:space:]]*env BUNDLE_ROOT=.*verify-offline-bundle.sh/s/[[:space:]]*\\$//p' "$SCRIPT_DIR/install.sh")"
[[ -n "$preflight_call" ]]
(
  SCRIPT_DIR="$task_tmp/preflight"
  BUNDLE_ROOT="$task_tmp/custom bundle root"
  BUNDLE_OS=ubuntu
  readonly SCRIPT_DIR BUNDLE_ROOT
  verify_args=(--require-signature --trusted-public-key '/custom key/public.pem')
  eval "$preflight_call"
) > "$task_tmp/preflight.actual" 2> "$task_tmp/preflight.stderr"
printf '%s\n' "$task_tmp/custom bundle root" ubuntu --require-signature --trusted-public-key '/custom key/public.pem' > "$task_tmp/preflight.expected"
cmp "$task_tmp/preflight.expected" "$task_tmp/preflight.actual"
[[ ! -s "$task_tmp/preflight.stderr" ]]
echo 'package-offline-regression: PASS (both OS locks, tamper, metadata, required packages, offline recovery and final state)'
