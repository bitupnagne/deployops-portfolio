#!/usr/bin/env bash
# deploy-read.sh — 읽기 전용 진단 에이전트
#
#   AI 어시스턴트와 운영자 진단 화면이 "서버 상태를 본다"를 수행하는 유일한 통로다.
#   sudoers 에 이 스크립트만 등록되고, 이 스크립트는 아래 목록에 있는 동작만 한다.
#
# ★ 설계 원칙 — 왜 이런 모양인가
#
#   1) 명령을 받지 않고 "기능 이름"을 받는다.
#      호출자는 `journalctl -u X` 같은 명령 문자열을 넘기지 못한다. `log-service` 처럼
#      정해진 기능 이름과 인자만 넘기고, 실제 명령은 여기서 조립한다.
#      AI 가 문장을 만들어 내는 특성상, 명령 문자열을 받는 순간 무엇이든 실행될 수 있다.
#
#   2) 쓰기 동작이 없다. 파일을 만들지도, 서비스를 건드리지도 않는다.
#      systemctl 은 status/is-active 만, docker 는 ps/logs/inspect 만 쓴다.
#
#   3) 인자를 셸에 넘기지 않는다. 서비스명·컨테이너명은 화이트리스트 정규식으로 검사하고,
#      줄 수는 정수 범위로 자른다. eval·명령치환·사용자 지정 경로가 없다.
#
#   4) 출력 크기를 자른다. 무제한이면 AI 토큰 비용이 폭발하고, 한 번의 호출로
#      로그 전체가 밖으로 나갈 수 있다.
#
#   5) 시크릿을 지운다. 로그에는 토큰·비밀번호가 섞인다. 나가기 전에 여기서 한 번 마스킹하고
#      (배포시스템도 한 번 더 한다 — 두 겹으로 둔다).
set -uo pipefail

MAX_LINES=500          # 한 번에 볼 수 있는 최대 줄 수
MAX_BYTES=$((256*1024)) # 출력 상한 256KB

usage() {
  cat <<'EOF'
Usage: deploy-read.sh <기능> [인자]

  health                       서비스/디스크/메모리 요약
  log-service <서비스명> [줄수]  systemd 유닛 로그 (기본 200, 최대 500)
  log-container <이름> [줄수]    컨테이너 로그
  container-list               실행 중인 컨테이너 목록
  container-info <이름>         컨테이너 상태·이미지·재시작 횟수
  disk                         디스크 사용량
  memory                       메모리/스왑 사용량
  release-info                 현재 배포된 정적 릴리스(심볼릭 링크 대상)
  capabilities                 이 스크립트가 제공하는 기능 목록(JSON)

읽기 전용이다. 어떤 기능도 파일이나 서비스 상태를 바꾸지 않는다.
EOF
}

die() { printf '%s\n' "$1" >&2; exit 2; }

# 서비스/컨테이너 이름 — 셸 특수문자를 애초에 통과시키지 않는다.
safe_name() { [[ "$1" =~ ^[A-Za-z0-9][A-Za-z0-9._@-]{0,63}$ ]]; }

# 줄 수 — 정수만, 1..MAX_LINES 로 자른다.
clamp_lines() {
  local n="${1:-200}"
  [[ "$n" =~ ^[0-9]{1,4}$ ]] || n=200
  (( n < 1 )) && n=1
  (( n > MAX_LINES )) && n=$MAX_LINES
  printf '%s' "$n"
}

# 시크릿 마스킹 — 값 형태로 판별한다(키 이름에 의존하면 새 이름이 생길 때마다 샌다).
mask() {
  sed -E \
    -e 's/(gl(pat|rt)-)[A-Za-z0-9_-]{8,}/\1***MASKED***/g' \
    -e 's/(eyJ[A-Za-z0-9_-]{10,})\.[A-Za-z0-9._-]+/\1.***MASKED***/g' \
    -e 's/([Bb]earer[[:space:]]+)[A-Za-z0-9._-]{12,}/\1***MASKED***/g' \
    -e 's/(enc:v[0-9]+:)[A-Za-z0-9+\/=]{8,}/\1***MASKED***/g' \
    -e 's/(([Pp]ass(word)?|[Ss]ecret|[Tt]oken|[Kk]ey)[[:space:]]*[=:][[:space:]]*)[^[:space:],;"]{4,}/\1***MASKED***/g' \
    -e 's#(://[^:/@[:space:]]+):[^@[:space:]]+@#\1:***MASKED***@#g' \
    -e 's/\b[0-9a-fA-F]{40,}\b/***MASKED-HEX***/g'
}

# 출력 공통 처리: 마스킹 → 크기 제한
emit() { mask | head -c "$MAX_BYTES"; }

cmd_health() {
  echo "== 서비스 =="
  local u st
  for u in docker mariadb nginx deploy-system; do
    systemctl list-unit-files "$u.service" >/dev/null 2>&1 || continue
    # is-active 는 "unknown"/"inactive" 를 stdout 에 찍으면서 실패도 한다.
    # `|| echo unknown` 을 붙이면 두 줄이 잡혀 출력이 깨진다 — AI 가 읽는 출력이라 한 줄로 고정한다.
    st="$(systemctl is-active "$u" 2>/dev/null | head -1)"
    printf '  %-22s %s\n' "$u" "${st:-unknown}"
  done
  echo
  echo "== 디스크 =="; df -hP / /var 2>/dev/null | awk 'NR==1||/\//'
  echo
  echo "== 메모리 =="; free -m 2>/dev/null | head -3
  echo
  echo "== 부팅 이후 =="; uptime 2>/dev/null
}

cmd_log_service() {
  local svc="${1:-}" n; n="$(clamp_lines "${2:-200}")"
  [[ -n "$svc" ]] || die "서비스명이 필요합니다."
  safe_name "$svc" || die "허용되지 않는 서비스명: $svc"
  systemctl list-unit-files "$svc.service" >/dev/null 2>&1 \
    || die "그런 서비스가 없습니다: $svc"
  journalctl -u "$svc" -n "$n" --no-pager 2>&1
}

cmd_log_container() {
  local c="${1:-}" n; n="$(clamp_lines "${2:-200}")"
  [[ -n "$c" ]] || die "컨테이너 이름이 필요합니다."
  safe_name "$c" || die "허용되지 않는 컨테이너 이름: $c"
  command -v docker >/dev/null 2>&1 || die "이 서버에는 docker 가 없습니다."
  docker container inspect "$c" >/dev/null 2>&1 || die "그런 컨테이너가 없습니다: $c"
  docker logs --tail "$n" "$c" 2>&1
}

cmd_container_list() {
  command -v docker >/dev/null 2>&1 || die "이 서버에는 docker 가 없습니다."
  docker ps --format '{{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}'
}

cmd_container_info() {
  local c="${1:-}"
  [[ -n "$c" ]] || die "컨테이너 이름이 필요합니다."
  safe_name "$c" || die "허용되지 않는 컨테이너 이름: $c"
  command -v docker >/dev/null 2>&1 || die "이 서버에는 docker 가 없습니다."
  docker container inspect "$c" \
    --format 'name={{.Name}}
image={{.Config.Image}}
state={{.State.Status}}
started={{.State.StartedAt}}
restarts={{.RestartCount}}
exitcode={{.State.ExitCode}}
oomkilled={{.State.OOMKilled}}
memlimit={{.HostConfig.Memory}}' 2>&1
}

cmd_disk()   { df -hPT 2>/dev/null; }
cmd_memory() { free -m 2>/dev/null; echo; swapon --show 2>/dev/null || true; }

cmd_release_info() {
  local root
  for root in /var/www/app /usr/share/nginx/html; do
    [[ -d "$root/releases" ]] || continue
    echo "web-root: $root"
    echo "current : $(readlink -f "$root/current" 2>/dev/null || echo '(없음)')"
    echo "releases:"; ls -1t "$root/releases" 2>/dev/null | head -10 | sed 's/^/  /'
    echo
  done
}

cmd_capabilities() {
  cat <<'EOF'
{"readOnly":true,"capabilities":[
 {"name":"health","args":[]},
 {"name":"log-service","args":["service","lines?"]},
 {"name":"log-container","args":["container","lines?"]},
 {"name":"container-list","args":[]},
 {"name":"container-info","args":["container"]},
 {"name":"disk","args":[]},
 {"name":"memory","args":[]},
 {"name":"release-info","args":[]}
],"limits":{"maxLines":500,"maxBytes":262144}}
EOF
}

case "${1:-}" in
  health)         shift; cmd_health          "$@" | emit ;;
  log-service)    shift; cmd_log_service     "$@" | emit ;;
  log-container)  shift; cmd_log_container   "$@" | emit ;;
  container-list) shift; cmd_container_list   "$@" | emit ;;
  container-info) shift; cmd_container_info  "$@" | emit ;;
  disk)           shift; cmd_disk            "$@" | emit ;;
  memory)         shift; cmd_memory          "$@" | emit ;;
  release-info)   shift; cmd_release_info    "$@" | emit ;;
  capabilities)   cmd_capabilities ;;
  -h|--help|'')   usage ;;
  *)              die "알 수 없는 기능: $1 (deploy-read.sh --help)" ;;
esac
