#!/usr/bin/env bash
target_prerequisites_packages() {
  printf '%s\n' sudo policycoreutils policycoreutils-python-utils iproute procps-ng shadow-utils util-linux tar gzip curl
}
target_agent_require_tools() {
  local tool
  for tool in sudo visudo systemctl getent useradd groupadd gpasswd tar gzip curl flock findmnt getenforce; do
    command -v "$tool" >/dev/null || { err "Missing agent prerequisite: $tool"; return 1; }
  done
  [[ -x /usr/libexec/platform-python ]] || { err 'Rocky platform-python is required'; return 1; }
}

target_prerequisites_status() {
  target_agent_require_tools >/dev/null 2>&1 && echo installed || echo missing
}

target_prerequisites_install() {
  local dir
  [[ "${DEPLOY_AGENT_TRANSPORT:-agent}" == agent ]] || { err 'Agent transport is required'; return 1; }
  [[ "$OS_FAMILY" == rhel ]] || { err 'Target bootstrap requires Rocky RPM packages'; return 1; }
  dir="$(bundle_dir target-prerequisites)" || return 1
  target_prerequisites_replace_curl_variants "$dir" || return 1
  install_local_packages "$dir" || return 1
  target_agent_require_tools
}
target_prerequisites_replace_curl_variants() {
  local dir="$1" mode="${2:---apply}" inventory helper
  [[ "$mode" == --apply || "$mode" == --plan-only ]] || return 1
  inventory="$(rpm -qa --qf '%{NAME}\n')" || return 1
  if ! printf '%s\n' "$inventory" | grep -Fx -e curl-minimal -e libcurl-minimal >/dev/null; then return 0; fi
  verify_rpm_repository "$dir" || return 1
  if [[ "${OFFLINE_VERIFY_AUTHENTICITY:-0}" == 1 ]]; then
    target_prerequisites_verify_rpm_signatures "$dir" "$dir/../../common/rpm-keys" || return 1
  fi
  if [[ "$mode" == --apply && "${OFFLINE_VERIFY_AUTHENTICITY:-0}" == 1 ]]; then
    bootstrap_rpm_signing_keys "$dir/../../common/rpm-keys" || return 1
  fi
  helper="$SCRIPT_DIR/lib/target-curl-variants.py"
  [[ -x /usr/libexec/platform-python && -f "$helper" && ! -L "$helper" ]] \
    || { err 'Rocky DNF Python helper is required for curl variant replacement'; return 1; }
  /usr/libexec/platform-python "$helper" --repository "$dir" "$mode" || return 1
}
target_prerequisites_verify_rpm_signatures() (
  local dir="$1" key_dir="$2" task_signature_tmp file output
  verify_rpm_signing_keys "$key_dir" || return 1
  task_signature_tmp="$(mktemp -d)" || return 1
  trap 'rm -rf "$task_signature_tmp"' EXIT
  rpm --dbpath "$task_signature_tmp" --initdb || return 1
  # 다른 시스템 키를 재사용하지 않는다. 이 closure는 Rocky publisher만 허용한다.
  rpmkeys --dbpath "$task_signature_tmp" --import "$key_dir/rocky8.gpg" || return 1
  local files=("$dir"/*.rpm)
  [[ -f "${files[0]}" ]] || return 1
  for file in "${files[@]}"; do
    output="$(LC_ALL=C rpmkeys --dbpath "$task_signature_tmp" --checksig "$file" 2>&1)" || return 1
    [[ "$output" == *': digests signatures OK' ]] || { printf 'Rocky publisher signature is required: %s\n' "$file" >&2; return 1; }
  done
)
