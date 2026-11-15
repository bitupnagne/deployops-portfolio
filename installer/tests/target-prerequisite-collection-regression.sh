#!/usr/bin/env bash
# 실제 다운로드 없이 수집기의 arch query/closure/원자 교체 계약을 검사한다.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
task_tmp="$(mktemp -d)"
trap 'rm -rf "$task_tmp"' EXIT
mkdir -p "$task_tmp/installer/bundles" "$task_tmp/installer/lib" "$task_tmp/installer/components"
cp "$root/bundles/fetch-bundles.sh" "$root/bundles/RELEASE-BOM.env" "$task_tmp/installer/bundles/"
cp "$root/lib/"{release-metadata,rpm-repository,rpm-signing-keys}.sh "$task_tmp/installer/lib/"
cp "$root/components/target-prerequisites.sh" "$task_tmp/installer/components/"
. "$task_tmp/installer/bundles/fetch-bundles.sh" --os rocky8
fetch_rpm_signing_keys() { :; }
target_prerequisites_verify_rpm_signatures() { :; }
write_rpm_repository() { mkdir -p "$1/repodata"; printf fixture > "$1/repodata/repomd.xml"; }
BAD_ARCH=''
dnf() {
  local arch_filter='' dest='' arg
  local args=("$@")
  printf '%s\n' "$@" > "$task_tmp/dnf-args"
  while (( $# )); do
    case "$1" in
      --arch=*) arch_filter="${1#--arch=}"; shift ;;
      --destdir) dest="$2"; shift 2 ;;
      *) shift ;;
    esac
  done
  [[ "$arch_filter" == x86_64,noarch && -n "$dest" ]] || return 1
  local name
  for name in $(target_prerequisites_packages); do
    printf '%s|0:1.0-1|x86_64' "$name" > "$dest/$name.x86_64.rpm"
  done
  printf 'policy-dependency|0:1.0-1|noarch' > "$dest/policy-dependency.noarch.rpm"
  if [[ -n "$BAD_ARCH" ]]; then printf 'policy-dependency|0:1.0-1|%s' "$BAD_ARCH" > "$dest/policy-dependency.$BAD_ARCH.rpm"; fi
}
rpm() {
  local metadata
  metadata="$(cat "${*: -1}")"
  if [[ " $* " == *" %{ARCH} "* ]]; then printf '%s' "${metadata##*|}"; else printf '%s\n' "$metadata"; fi
}
fetch_target_prerequisites
grep -Fx -- '--arch=x86_64,noarch' "$task_tmp/dnf-args"
grep -Fx -- '--resolve' "$task_tmp/dnf-args"
grep -Fx -- '--alldeps' "$task_tmp/dnf-args"
grep -Fx -- '--forcearch=x86_64' "$task_tmp/dnf-args"
target="$BUNDLE_ROOT/rocky8/target-prerequisites"
[[ "$(wc -l < "$target/PACKAGES.lock")" == 13 ]]
awk -F'|' '$3!="x86_64" && $3!="noarch" {exit 1}' "$target/PACKAGES.lock"
cp "$target/PACKAGES.lock" "$task_tmp/original.lock"
for BAD_ARCH in i686 aarch64 src; do
  if (fetch_target_prerequisites); then echo 'Collector accepted an unsupported dependency architecture' >&2; exit 1; fi
  cmp "$target/PACKAGES.lock" "$task_tmp/original.lock"
done
printf 'target prerequisite collection regression: PASS (mocked DNF/RPM, no downloads)\n'
