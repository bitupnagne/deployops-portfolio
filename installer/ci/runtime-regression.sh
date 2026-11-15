#!/usr/bin/env bash
# 실제 수집 context 사용. 원본은 읽기만 하고, 새 임시 복제본만 변조합니다.
set -euo pipefail
export LC_ALL=C
[[ $# == 2 ]]
source_context=$(realpath "$1")
external_pin=$2
ci=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
work=$(mktemp -d /tmp/deployops-runtime-regression.XXXXXXXX)
trap 'rm -rf -- "$work"' EXIT
mkdir "$work/context"
(cd "$source_context"; tar -cf - .) | tar -xf - -C "$work/context"
context=$work/context
pin() { sha256sum "$context/RUNTIME.lock" | cut -d' ' -f1; }
relock() { (cd "$context"; find . -type f ! -name RUNTIME.lock -printf '%P\n' | sort | while IFS= read -r path; do sha256sum "$path"; done > RUNTIME.lock); }
expect_failure() {
    local label=$1 expected=$2
    if bash "$ci/runtime-verify.sh" "$context" "$expected" > "$work/result" 2>&1; then
        echo "FAIL unexpected success: $label" >&2; exit 1
    fi
    echo "PASS rejects $label"
}
bash "$ci/runtime-verify.sh" "$context" "$external_pin"
expect_failure 'wrong external pin' "$(printf '%064d' 0)"
cp "$context/IDENTITY" "$work/IDENTITY"
printf 'tamper\n' >> "$context/IDENTITY"
expect_failure 'modified payload' "$external_pin"
cp "$work/IDENTITY" "$context/IDENTITY"
printf 'unexpected\n' > "$context/extra"
expect_failure 'unlocked payload' "$external_pin"
rm "$context/extra"
mv "$context/IDENTITY" "$work/missing"
expect_failure 'missing payload' "$external_pin"
mv "$work/missing" "$context/IDENTITY"
ln -s /etc/passwd "$context/escape"
expect_failure 'symlink' "$external_pin"
rm "$context/escape"
cp "$context/RUNTIME.lock" "$work/original.lock"
printf '%064d  ../escape\n' 0 >> "$context/RUNTIME.lock"
expect_failure 'traversal with new pin' "$(pin)"
cp "$work/original.lock" "$context/RUNTIME.lock"
head -n 1 "$work/original.lock" >> "$context/RUNTIME.lock"
expect_failure 'duplicate lock path' "$(pin)"
cp "$work/original.lock" "$context/RUNTIME.lock"
cp "$context/IDENTITY" "$work/identity-original"
sed -i 's/^ARCH=amd64$/ARCH=arm64/' "$context/IDENTITY"
relock
expect_failure 'wrong architecture with new pin' "$(pin)"
cp "$work/identity-original" "$context/IDENTITY"
printf 'invalid-package-row\n' >> "$context/PACKAGES.tsv"
relock
expect_failure 'invalid package row with new pin' "$(pin)"
sed -i '$d' "$context/PACKAGES.tsv"
relock
bash "$ci/runtime-verify.sh" "$context" "$external_pin"
echo 'PASS runtime regression: valid context twice + 9 negative cases'
