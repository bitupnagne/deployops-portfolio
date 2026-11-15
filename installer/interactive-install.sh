#!/usr/bin/env bash
# Readable launcher; execution is subject to the company's approved installer policy.
set -euo pipefail
if [[ ${EUID:-$(id -u)} -ne 0 ]]; then
  printf '%s\n' '설치는 root 권한이 필요합니다. 반입은 deploy/gitlab/docker/nginx 계정으로 하고, 승인된 root 세션에서 다시 실행하세요.' >&2
  exit 1
fi
installer_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
package="$(dirname -- "$installer_dir")"
entry="$package/Deploy-System-Interactive-Installer.sh"
if [[ -f "$entry" ]]; then
  exec /bin/bash "$entry" --package-root "$package" "$@"
fi
if [[ -e /var/lib/deploy-installer/installation.json ]]; then
  printf '%s\n' '이 위치는 완전한 r8 반입본이 아닙니다. 등록된 설치기는 /usr/local/sbin/deploy-installer 로 재개하세요.' >&2
  exit 1
fi
# Source and prerequisites-only trees keep their local wizard entry point.
python=/usr/libexec/platform-python
[[ -x "$python" && -f "$installer_dir/interactive_install.py" ]] || { echo '기본 설치기를 찾을 수 없습니다.' >&2; exit 1; }
exec "$python" -I -B "$installer_dir/interactive_install.py" --package-root "$package" "$@"
