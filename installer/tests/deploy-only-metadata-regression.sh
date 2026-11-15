#!/usr/bin/env bash
# Read/validate only; never installs packages or services.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"' EXIT
. "$root/lib/release-metadata.sh"
cp "$root/bundles/RELEASE-BOM.env" "$tmp/legacy-bom"
cp "$root/bundles/RELEASE-LOCK.env" "$tmp/legacy-lock"
sed '/^BOM_WORKSYSTEM_INCLUDED=/d' "$tmp/legacy-bom" > "$tmp/bom"
sed '/^LOCK_WORKSYSTEM_INCLUDED=/d' "$tmp/legacy-lock" > "$tmp/lock"
load_release_bom "$tmp/bom"
load_release_lock "$tmp/lock"
[[ "$BOM_WORKSYSTEM_INCLUDED" == true && "$LOCK_WORKSYSTEM_INCLUDED" == true ]]
sed '/^BOM_WORKSYSTEM_/d' "$tmp/legacy-bom" > "$tmp/bom"
printf 'BOM_WORKSYSTEM_INCLUDED=false\n' >> "$tmp/bom"
sed '/^LOCK_WORKSYSTEM_/d' "$tmp/legacy-lock" > "$tmp/lock"
printf 'LOCK_WORKSYSTEM_INCLUDED=false\n' >> "$tmp/lock"
load_release_bom "$tmp/bom"
load_release_lock "$tmp/lock"
[[ "$BOM_WORKSYSTEM_INCLUDED" == false && "$LOCK_WORKSYSTEM_INCLUDED" == false ]]
[[ -z "${BOM_WORKSYSTEM_JAR+x}" && -z "${LOCK_WORKSYSTEM_SHA256+x}" ]]
cp "$tmp/bom" "$tmp/clean-bom"
printf 'BOM_WORKSYSTEM_JAR=common/worksystem-server/worksystem.jar\n' >> "$tmp/bom"
if load_release_bom "$tmp/bom" 2>"$tmp/error"; then exit 1; fi
grep -q 'excluded component metadata must be absent' "$tmp/error"
cp "$tmp/clean-bom" "$tmp/bom"
sed 's/INCLUDED=false/INCLUDED=maybe/' "$tmp/bom" > "$tmp/invalid"
if load_release_bom "$tmp/invalid" 2>"$tmp/error"; then exit 1; fi
load_release_bom "$tmp/bom"
if load_release_lock "$tmp/legacy-lock" 2>"$tmp/error"; then exit 1; fi
grep -q 'inclusion lock does not match' "$tmp/error"
load_release_bom "$tmp/bom"

# Invoke the verifier's actual payload selection block with no optional directory, then a stale directory.
BUNDLE_ROOT="$tmp"
errors=0
require_file() { echo 'Unexpected optional JAR lookup' >&2; exit 1; }
fail() { errors=$((errors + 1)); }
payload_check="$(sed -n '/^worksystem_jar=/,/^java_archive=/p' "$root/verify-offline-bundle.sh" | sed '$d')"
[[ -n "$payload_check" ]]
eval "$payload_check"
[[ "$errors" == 0 ]]
mkdir -p "$tmp/common/worksystem-server"
eval "$payload_check"
[[ "$errors" == 1 ]]

# Exercise the real installer entry point. Reject excluded Work before OS/root/answers/service work.
mkdir "$tmp/bundles"
cp "$tmp/bom" "$tmp/bundles/RELEASE-BOM.env"
if BUNDLE_ROOT="$tmp/bundles" bash "$root/install.sh" --unattended --components worksystem-server \
    --answers "$tmp/nonexistent-answers" >"$tmp/install-log" 2>&1; then
  echo 'Installer accepted excluded component' >&2; exit 1
fi
grep -q '별도의 Worksystem 설치 패키지' "$tmp/install-log"
if grep -q 'nonexistent-answers' "$tmp/install-log"; then
  echo 'Excluded component was rejected after reading unrelated answers' >&2; exit 1
fi
echo 'PASS: legacy metadata, explicit exclusion, forbidden extra metadata, mismatch, real installer early refusal'
