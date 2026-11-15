#!/usr/bin/env bash
# 앱과 root-local 보조 코드를 동일 JAR 세대로 교체한다. 인프라 RPM은 별도 유지보수한다.
set -euo pipefail
[[ $EUID -eq 0 && ( $# -eq 1 || $# -eq 4 ) ]] || { echo '사용법: sudo patch-deployops.sh /반입경로/deploy-ops.jar' >&2; exit 2; }
py=/usr/libexec/platform-python
[[ -x "$py" ]] || py="$(command -v python3)"
if [[ $# -eq 4 ]]; then
  [[ "$1" == --recover-nexus-query && "$4" == --acknowledge-read-only-recovery ]] || { echo '지원하지 않는 복구 인자' >&2; exit 2; }
  recovery="$(cd -- "$(dirname -- "$0")" && pwd)/recover-readonly-patch.py"
  [[ -f "$recovery" && ! -L "$recovery" ]] || { echo '최신 반입 패키지의 installer/deploy-system/patch-deployops.sh에서 복구 모드를 실행하세요.' >&2; exit 2; }
  exec "$py" -I -B "$recovery" "$2" "$3" "$4"
fi
exec "$py" -I -B /usr/local/deploy/bin/deployops-maintenance.py "$1"
