#!/usr/bin/env bash
# Deploy backup: coherent DB + JAR/config + TLS + immutable agent transfer files.
set -euo pipefail
[[ $EUID -eq 0 ]] || { echo '[backup][ERR] root required' >&2; exit 1; }
umask 077
APP_HOME="${DEPLOY_APP_HOME:-/opt/deploy-system}"
JAR_FILE="$APP_HOME/app/deploy-ops.jar"
HELPER="${DEPLOYOPS_CONFIG_HELPER:-/usr/local/deploy/bin/deployops-embedded-config.py}"
MAINTENANCE=/usr/local/deploy/bin/deployops-maintenance.py
STATE_HELPER=/usr/local/deploy/bin/deployops-state.py
TLS_HELPER=/usr/local/deploy/bin/deployops-tls.py
SERVICE=deploy-system
TIMER=deployops-nexus-backup.timer
BACKUP_ROOT="${DEPLOYOPS_BACKUP_DIR:-/var/backups/deploy-system}"
KEEP_DAILY="${DEPLOYOPS_KEEP_DAILY:-7}"
KEEP_WEEKLY="${DEPLOYOPS_KEEP_WEEKLY:-4}"
KEEP_MONTHLY="${DEPLOYOPS_KEEP_MONTHLY:-6}"
log() { printf '[backup] %s\n' "$*"; }
err() { printf '[backup][ERR] %s\n' "$*" >&2; }
PY=/usr/libexec/platform-python
[[ -x "$PY" ]] || PY="$(command -v python3)"
for required in "$JAR_FILE" "$HELPER" "$MAINTENANCE" "$STATE_HELPER" "$TLS_HELPER"; do
  [[ -f "$required" && ! -L "$required" ]] || { err 'Required coherent-backup helper is missing'; exit 1; }
done
for keep in "$KEEP_DAILY" "$KEEP_WEEKLY" "$KEEP_MONTHLY"; do
  [[ "$keep" =~ ^[1-9][0-9]{0,3}$ ]] || { err 'Invalid backup retention count'; exit 1; }
done
for part in "$BACKUP_ROOT" "$(dirname "$BACKUP_ROOT")"; do
  [[ "$part" == /* && "$part" != / && "$part" != *'/../'* && "$part" != */.. && "$part" != */. && "$part" != *'/./'* && "$part" != *'//'* && ! -L "$part" ]] || { err 'Unsafe backup root'; exit 1; }
done
"$PY" -I -B - "$BACKUP_ROOT" <<'PY'
import os, stat, sys
root=sys.argv[1]
if os.path.normpath(root)!=root: raise SystemExit('Canonical backup path required')
cursor=root
while not os.path.lexists(cursor): cursor=os.path.dirname(cursor)
while True:
    info=os.lstat(cursor)
    sticky=cursor!=root and bool(info.st_mode & stat.S_ISVTX)
    if not stat.S_ISDIR(info.st_mode) or stat.S_ISLNK(info.st_mode) or info.st_uid!=0 or (info.st_mode & 0o022 and not sticky):
        raise SystemExit('Backup ancestry must be root-controlled')
    parent=os.path.dirname(cursor)
    if parent==cursor: break
    cursor=parent
PY
install -d -m 700 -o root -g root "$BACKUP_ROOT"
# Patch already owns this exact root broker lock. Daily/manual backups obtain it here.
if [[ "${DEPLOYOPS_BACKUP_LOCKS_HELD:-0}" != 1 ]]; then
  lock=/var/lib/deployops/nexus-backup/state/broker.lock
  [[ -d "${lock%/*}" && ! -L "${lock%/*}" && "$(stat -c '%u:%a' "${lock%/*}")" == 0:700 ]] || { err 'Broker lock directory is not root-private'; exit 1; }
  if [[ ! -e "$lock" && ! -L "$lock" ]]; then ( set -o noclobber; : > "$lock" ) || exit 1; fi
  [[ -f "$lock" && ! -L "$lock" && "$(stat -c '%u:%a:%h' "$lock")" == 0:600:1 ]] || { err 'Unsafe broker lock'; exit 1; }
  exec 201<>"$lock"
  flock -n 201 || { err 'Nexus/patch operation is running; backup skipped safely'; exit 1; }
fi
fields_file="$(mktemp)"
health_active=0; timer_active=0; stopped=0
cleanup() {
  local result=$? ready=1 attempt
  trap - EXIT
  rm -f -- "$fields_file"
  if (( stopped && health_active )); then
    if ! systemctl start "$SERVICE"; then ready=0; result=1
    else
      ready=0
      for attempt in $(seq 1 30); do
        if "$PY" -I -B "$TLS_HELPER" --jar "$JAR_FILE" --mode health-check --port "${APP_PORT:-9090}" >/dev/null 2>&1; then ready=1; break; fi
        sleep 2
      done
      (( ready )) || result=1
    fi
  fi
  if (( stopped && timer_active && ready )); then systemctl start "$TIMER" || result=1; fi
  (( ready )) || err 'Application health did not recover; Nexus timer remains stopped'
  exit "$result"
}
trap cleanup EXIT
chmod 600 "$fields_file"
"$PY" -I -B "$HELPER" fields "$JAR_FILE" > "$fields_file"
mapfile -d '' -t fields < "$fields_file"
DB_URL="${fields[0]}"; DB_USER="${fields[1]}"; DB_PASS="${fields[2]}"; APP_PORT="${fields[3]}"
unset fields
rm -f -- "$fields_file"
rest="${DB_URL#*//}"; DB_HOST="${rest%%:*}"; rest="${rest#*:}"
DB_PORT="${rest%%/*}"; rest="${rest#*/}"; DB_NAME="${rest%%\?*}"
DUMP_BIN="$(command -v mariadb-dump || command -v mysqldump)" || { err 'MariaDB dump client missing'; exit 1; }
# A live agent may still be running after Deploy stops. Both checks are mandatory.
DEPLOY_APP_HOME="$APP_HOME" "$PY" -I -B "$MAINTENANCE" --check-idle >/dev/null
systemctl is-active --quiet "$SERVICE" && health_active=1 || true
systemctl is-active --quiet "$TIMER" && timer_active=1 || true
stopped=1
systemctl stop "$TIMER"
systemctl stop "$SERVICE"
DEPLOY_APP_HOME="$APP_HOME" "$PY" -I -B "$MAINTENANCE" --check-idle >/dev/null
stamp="$(date +%Y%m%d-%H%M%S)-$$"; day="$(date +%u)"; dom="$(date +%d)"
tier=daily; [[ "$day" != 7 ]] || tier=weekly; [[ "$dom" != 01 ]] || tier=monthly
dest="$BACKUP_ROOT/$tier/$stamp"
install -d -m 700 "$BACKUP_ROOT/$tier" "$dest"
log 'Capturing idle Deploy DB, matching configuration, TLS and agent transfer state'
if ! MYSQL_PWD="$DB_PASS" "$DUMP_BIN" -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" \
    --single-transaction --routines --events --default-character-set=utf8mb4 "$DB_NAME" 2>"$dest/db.err" | gzip -1 > "$dest/db.sql.gz"; then
  err 'Database dump failed; private diagnostics preserved'; exit 1
fi
[[ -s "$dest/db.sql.gz" ]] || { err 'Empty DB dump'; exit 1; }
rm -f -- "$dest/db.err"
tar -czf "$dest/config.tar.gz" -C "$APP_HOME" config app/deploy-ops.jar
"$PY" -I -B "$STATE_HELPER" capture --archive "$dest/runtime-state.tar.gz" --app-home "$APP_HOME" > "$dest/runtime-state.json"
( cd "$dest" && sha256sum db.sql.gz config.tar.gz runtime-state.tar.gz runtime-state.json > SHA256SUMS )
cat > "$dest/복원방법.txt" <<EOF
Deploy backup: $stamp ($tier)
포함: db.sql.gz + config.tar.gz + runtime-state.tar.gz + runtime-state.json
runtime-state: 회사 TLS 개인키/CA/HTTPS 검사 설정 + data/agent-artifacts; 원래 사용자/그룹/권한 보존.
WAS·WEB의 에이전트 인증/실행 기록과 NAS 업무 데이터는 각 서버의 별도 백업 대상입니다.

1. SHA256SUMS를 확인하고 같은 세대 파일을 함께 사용합니다.
2. 신규 서버에는 동일 앱 계정명/그룹과 지원되는 설치기·보조 코드를 먼저 준비합니다.
3. 앱과 Nexus 타이머 정지:
   sudo systemctl stop deploy-system deployops-nexus-backup.timer
4. config.tar.gz를 $APP_HOME 아래에 복원하고 앱 계정/그룹과 JAR 640을 확인합니다.
5. TLS와 전송 파일 상태 복원(서비스가 켜져 있으면 도구가 거절):
   sudo $PY /usr/local/deploy/bin/deployops-state.py restore --archive runtime-state.tar.gz --app-home $APP_HOME
6. db.sql.gz를 같은 세대 DB로 복원합니다. 인증정보는 JAR에서 확인하며 명령 인자에 비밀번호를 넣지 않습니다.
7. HTTPS를 사용하는 구성만 /etc/deploy-system/tls-health.json의 회사 HTTPS 주소·CA·DNS 경로를 확인합니다.
   IP 기반 HTTP 구성은 회사 CA가 필요하지 않습니다. 백업 당시 JAR의 HTTP 설정과 실제 접속 IP·포트를 확인합니다.
8. 앱 기동 후 HTTP(S)·DB 상태 검사:
   sudo systemctl start deploy-system
   sudo $PY /usr/local/deploy/bin/deployops-tls.py --jar $APP_HOME/app/deploy-ops.jar --mode health-check --port $APP_PORT
9. 정상 확인 후 기존 활성 정책에 따라 deployops-nexus-backup.timer를 복구합니다.
10. Deploy 웹에서 에이전트 등록/버전/연결·준비 검사와 GitLab/Nexus 연결을 확인합니다.
DB 백업 시점 이후 실제 대상 서버 변경은 자동으로 되돌아가지 않으므로 릴리스/마운트/실행 상태를 대조합니다.
EOF
chmod 600 "$dest"/*
printf 'COMPLETE\n' > "$dest/BACKUP-COMPLETE"
log "Complete backup: $dest"
prune() {
  local kind="$1" keep="$2" count=0 directory
  [[ -d "$BACKUP_ROOT/$kind" && ! -L "$BACKUP_ROOT/$kind" ]] || return 0
  while IFS= read -r -d '' directory; do
    [[ -f "$directory/BACKUP-COMPLETE" && ! -L "$directory" ]] || continue
    count=$((count+1))
    if (( count > keep )); then
      [[ "$directory" == "$BACKUP_ROOT/$kind/"* && "$directory" != *'/../'* && ! -L "$directory" ]] || { err 'Unsafe retention target'; return 1; }
      rm -rf -- "$directory"
    fi
  done < <(find "$BACKUP_ROOT/$kind" -mindepth 1 -maxdepth 1 -type d -print0 | sort -zr)
}
prune daily "$KEEP_DAILY"; prune weekly "$KEEP_WEEKLY"; prune monthly "$KEEP_MONTHLY"
