#!/bin/bash
set -euo pipefail

CONFIG_FILE="/usr/local/deploy/conf/was-agent.env"
HEALTH_PORT=""
HEALTH_PATH=""
HEALTH_HOST=""
RETRIES=20
SLEEP_SEC=3

usage(){
  cat <<'EOF'
Usage: local-healthcheck.sh [--port PORT] [--path PATH] [--host HOST] [--retries COUNT] [--sleep SEC]
EOF
}

parse_args(){
  while [[ "$#" -gt 0 ]]; do
    case "$1" in
      --port) HEALTH_PORT="$2"; shift 2 ;;
      --path) HEALTH_PATH="$2"; shift 2 ;;
      --host) HEALTH_HOST="$2"; shift 2 ;;
      --retries) RETRIES="$2"; shift 2 ;;
      --sleep) SLEEP_SEC="$2"; shift 2 ;;
      -h|--help) usage; exit 0 ;;
      *) echo "Unknown argument: $1"; usage; exit 1 ;;
    esac
  done
}

load_defaults(){
  if [[ -f "$CONFIG_FILE" ]]; then
    # shellcheck disable=SC1090
    source "$CONFIG_FILE"
  fi
  HEALTH_PORT="${HEALTH_PORT:-${DEFAULT_HEALTH_PORT:-8080}}"
  HEALTH_PATH="${HEALTH_PATH:-${DEFAULT_HEALTH_PATH:-/actuator/health}}"
}

main(){
  local attempt=1
  local url

  parse_args "$@"
  load_defaults

  command -v curl >/dev/null 2>&1 || { echo "curl is required."; exit 1; }
  [[ "$HEALTH_PORT" =~ ^[0-9]{1,5}$ ]] || { echo "Invalid health port: $HEALTH_PORT"; exit 1; }
  [[ -z "$HEALTH_HOST" || "$HEALTH_HOST" =~ ^[A-Za-z0-9][A-Za-z0-9.-]*[A-Za-z0-9]$ || "$HEALTH_HOST" =~ ^[A-Za-z0-9]$ ]] || {
    echo "Invalid health host: $HEALTH_HOST" >&2
    exit 1
  }
  url="http://127.0.0.1:${HEALTH_PORT}${HEALTH_PATH}"

  while (( attempt <= RETRIES )); do
    if [[ -n "$HEALTH_HOST" ]]; then
      if curl -fsS --max-time 3 -H "Host: $HEALTH_HOST" "$url" >/dev/null 2>&1; then
        echo "Health check passed: $url (Host: $HEALTH_HOST)"
        exit 0
      fi
    elif curl -fsS --max-time 3 "$url" >/dev/null 2>&1; then
      echo "Health check passed: $url"
      exit 0
    fi
    sleep "$SLEEP_SEC"
    attempt=$((attempt + 1))
  done

  echo "Health check failed: $url"
  exit 1
}

main "$@"
