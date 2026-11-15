#!/bin/bash
# WEB CONTAINER compatibility entrypoint. Nginx policy files remain approval-managed.
set -euo pipefail

web_core(){
  local core="/usr/local/deploy/bin/deploy-container-core.sh"
  [[ -f "$core" && ! -L "$core" && "$(stat -c '%u:%g:%a' "$core")" == '0:0:750' ]] \
    || { echo "공통 컨테이너 에이전트 설치·소유권을 확인하세요." >&2; return 1; }
  DEPLOY_CONTAINER_ROLE=web "$core" "$@"
}

web_nginx_ready(){
  command -v nginx >/dev/null 2>&1 \
    || { echo "WEB 컨테이너의 nginx 반영에는 설치된 nginx가 필요합니다." >&2; return 1; }
  systemctl is-active --quiet nginx \
    || { echo "nginx가 실행 중이 아닙니다. 승인된 WEB 정책을 먼저 준비하세요." >&2; return 1; }
  nginx -t
}

web_nginx_reload(){
  web_nginx_ready && systemctl reload nginx
}

web_require_root(){
  [[ "${EUID}" -eq 0 ]] || { echo "Run as root." >&2; return 1; }
}

web_nginx_lock(){
  command -v flock >/dev/null 2>&1 || { echo "flock is required." >&2; return 1; }
  # FD8 is shared by all nginx writers; the container core uses its own FD9.
  exec 8>/var/lock/deploy-ops-nginx.lock
  flock -n 8 || { echo "다른 WEB nginx 작업이 진행 중입니다." >&2; return 1; }
}

web_main(){
  web_require_root || return $?
  (( $# > 0 )) || { echo "deploy-web-app.sh ACTION [core options] [--reload-nginx true|false]" >&2; return 1; }
  local action="$1" reload=true seen_reload=false has_generation=false rc
  shift
  local args=()
  while (( $# )); do
    case "$1" in
      --reload-nginx)
        [[ "$seen_reload" == false && $# -ge 2 && ( "$2" == true || "$2" == false ) ]] \
          || { echo "--reload-nginx는 true 또는 false를 한 번만 지정하세요." >&2; return 1; }
        seen_reload=true; reload="$2"; shift 2 ;;
      *)
        [[ "$1" != --deployment-id ]] || has_generation=true
        args+=("$1"); shift ;;
    esac
  done
  # Read-only queries and drain/stop never reload nginx.
  case "$action" in
    deploy|restore|start|restart) ;;
    *) reload=false ;;
  esac
  if [[ "$action" == deploy && "$has_generation" == false ]]; then
    args+=(--deployment-id "web-$(date +%s)-$$")
  fi
  if [[ "$reload" == true ]]; then
    web_nginx_lock || return $?
  fi
  if [[ "$reload" == true && "$action" != restore ]]; then
    web_nginx_ready || return $?
  fi
  # The core owns project isolation, image checks, locks, health and durable recovery.
  if web_core "$action" "${args[@]}"; then :; else
    rc=$?; echo "컨테이너 작업 실패: nginx를 reload하지 않았습니다." >&2; return "$rc"
  fi
  [[ "$reload" == true ]] || return 0
  if web_nginx_reload; then return 0; fi
  echo "WEB nginx 반영 실패: 배포 완료로 처리하지 않습니다." >&2
  if [[ "$action" == deploy ]]; then
    # Restore uses the same immutable generation and the prior instance's own health contract.
    if web_core restore "${args[@]}"; then
      echo "WEB_CONTAINER_RESTORED=true"
      if ! web_nginx_reload; then echo "NGINX_RECOVERY_REQUIRED=true" >&2; fi
    else
      echo "AUTOMATIC_RESTORE_FAILED=true" >&2
    fi
  else
    echo "NGINX_RECOVERY_REQUIRED=true" >&2
  fi
  return 1
}

web_main "$@"
