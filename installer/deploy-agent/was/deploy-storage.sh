#!/bin/bash
set -euo pipefail
if [[ -x /usr/libexec/platform-python ]]; then
  exec /usr/libexec/platform-python /usr/local/deploy/bin/deploy-storage.py "$@"
fi
exec /usr/bin/python3 /usr/local/deploy/bin/deploy-storage.py "$@"
