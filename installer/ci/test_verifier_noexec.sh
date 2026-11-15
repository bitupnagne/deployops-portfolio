#!/usr/bin/env bash
# Linux filesystem contract only; never invokes the full bootstrap or Docker.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
task_tmp="$(mktemp -d /tmp/deployops-ci-noexec-test.XXXXXX)"
trap 'rm -rf -- "$task_tmp"' EXIT
INSTALLER="$task_tmp/installer"; state="$task_tmp/state"
mkdir "$INSTALLER" "$state"
printf '%s\n' '#!/usr/bin/env bash' 'printf "%s\n" "$BUNDLE_ROOT" "$@"' > "$INSTALLER/verify-offline-bundle.sh"
chmod 0644 "$INSTALLER/verify-offline-bundle.sh"
[[ ! -x "$INSTALLER/verify-offline-bundle.sh" ]]
verify_args=(--require-java-ci)
call="$(sed -n '/^BUNDLE_ROOT=.*verify-offline-bundle.sh/p' "$HERE/bootstrap-offline-ci.sh")"
[[ -n "$call" ]]
eval "$call"
printf '%s\n' "$INSTALLER/bundles" --require-java-ci > "$task_tmp/expected"
cmp "$task_tmp/expected" "$state/verification.log"
printf 'PASS: actual CI bootstrap verifier call runs mode-0644 script through Bash and forwards Java CI readiness\n'
