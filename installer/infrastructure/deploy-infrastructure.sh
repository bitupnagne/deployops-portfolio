#!/usr/bin/env bash
set -euo pipefail
if [[ -x /usr/bin/python3 ]]; then
  exec /usr/bin/python3 /usr/local/deploy/bin/deploy-infrastructure.py "$@"
fi
exec /usr/libexec/platform-python /usr/local/deploy/bin/deploy-infrastructure.py "$@"
