#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BOM_GITLAB_IMAGE=fixture
. "$SCRIPT_DIR/components/gitlab.sh"
docker() {
  [[ "$1" == exec && "$2" == worksystem-gitlab ]] || return 99
  shift 2
  case "$1" in
    test)
      case "$3" in
        /var/opt/gitlab/bootstrapped) return "${bootstrap_rc:-0}";;
        /var/opt/gitlab/gitlab-rails/etc/database.yml) return "${config_rc:-0}";;
        *) return 99;;
      esac;;
    sh) [[ "$3" == *'[c]inc-client'* ]] || return 99; return "${chef_rc:-0}";;
    gitlab-ctl)
      [[ "$2" == status ]] || return 99
      case "$3" in puma) return "${puma_rc:-0}";;sidekiq) return "${sidekiq_rc:-0}";;*) return 99;;esac;;
    curl) [[ "${*: -1}" == http://127.0.0.1:8929/-/health ]] || return 99;return "${internal_rc:-0}";;
    *) return 99;;
  esac
}
curl() {
  [[ "${*: -1}" == http://127.0.0.1:8929/users/sign_in ]] || return 99
  return "${external_rc:-0}"
}
container_runtime_healthy() { return "${container_rc:-0}"; }
sleep() { :; }
gitlab_wait_healthy 8929 1
for pending in bootstrap_rc config_rc chef_rc puma_rc sidekiq_rc; do
  printf -v "$pending" 1
  if gitlab_wait_healthy 8929 1; then echo "Premature success: $pending" >&2; exit 1; fi
  printf -v "$pending" 0
done
internal_rc=1
if gitlab_wait_healthy 8929 1; then exit 1; fi
internal_rc=0 external_rc=1
if gitlab_wait_healthy 8929 1; then exit 1; fi
external_rc=0 container_rc=1
if gitlab_wait_healthy 8929 1; then exit 1; fi
echo 'gitlab-health-regression: PASS (bootstrap, Rails config, Chef completion, app services, internal/published HTTP, Docker health required)'
