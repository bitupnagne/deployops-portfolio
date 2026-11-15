#!/usr/bin/env bash
# Exercise the real collector with synthetic RPM headers. No network or installs.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
task_tmp="$(mktemp -d)"
trap 'rm -rf "$task_tmp"' EXIT
mkdir -p "$task_tmp/installer/"{bundles,lib,components}
cp "$root/bundles/"{fetch-bundles.sh,RELEASE-BOM.env} "$task_tmp/installer/bundles/"
cp "$root/lib/"{release-metadata,rpm-repository,rpm-signing-keys}.sh "$task_tmp/installer/lib/"
cp "$root/components/target-prerequisites.sh" "$task_tmp/installer/components/"
. "$task_tmp/installer/bundles/fetch-bundles.sh" --os rocky8

put_rpm() { printf '%s|%s|%s\n' "$2" "$3" "${4:-x86_64}" > "$1/$2-${3#*:}.${4:-x86_64}.rpm"; }
rpm() {
  local metadata
  metadata="$(cat "${*: -1}")"
  if [[ " $* " == *" %{ARCH} "* ]]; then printf '%s' "${metadata##*|}"; else printf '%s\n' "$metadata"; fi
}
fetch_rpm_signing_keys() { :; }
target_prerequisites_verify_rpm_signatures() { :; }
write_rpm_repository() { mkdir -p "$1/repodata"; printf fixture > "$1/repodata/repomd.xml"; }
runtime_calls=0; initial_calls=0; response=good; initial_with_runtime=1
dnf() {
  local dest='' runtime=0 args=("$@")
  while (( $# )); do
    case "$1" in
      --destdir) dest="$2"; shift 2 ;;
      --repofrompath=rocky-runtime-baseos,*) runtime=1; shift ;;
      *) shift ;;
    esac
  done
  [[ -n "$dest" ]] || return 1
  if (( runtime )); then
    runtime_calls=$((runtime_calls + 1))
    printf '%s\n' "${args[@]}" > "$task_tmp/runtime-args"
    [[ "$response" != fail ]] || return 1
    local version='0:4.14.3-32.el8_10'
    [[ "$response" != drift ]] || version='0:4.14.3-33.el8_10'
    put_rpm "$dest" python3-rpm "$version"
    [[ "$response" == omit ]] || put_rpm "$dest" rpm-build-libs "$version"
  else
    initial_calls=$((initial_calls + 1))
    put_rpm "$dest" test-root '0:1.0-1'
    if (( initial_with_runtime )); then put_rpm "$dest" rpm-libs '0:4.14.3-32.el8_10'; fi
  fi
}
passed=0
pass() { passed=$((passed + 1)); }

# A component which never updates rpm-libs must not add new runtime packages.
mkdir "$task_tmp/no-runtime"
put_rpm "$task_tmp/no-runtime" test-root '0:1.0-1'
complete_rocky_rpm_runtime_closure "$task_tmp/no-runtime"
[[ "$runtime_calls" == 0 ]]; pass

# An old clean OS can keep both installed reverse dependants by upgrading them.
mkdir "$task_tmp/complete"
put_rpm "$task_tmp/complete" rpm-libs '0:4.14.3-32.el8_10'
complete_rocky_rpm_runtime_closure "$task_tmp/complete"
[[ "$runtime_calls" == 1 ]]
for arg in '--releasever=8.10' '--forcearch=x86_64' '--disablerepo=*' \
  '--repofrompath=rocky-runtime-baseos,https://dl.rockylinux.org/pub/rocky/8.10/BaseOS/x86_64/os/' \
  '--repofrompath=rocky-runtime-appstream,https://dl.rockylinux.org/pub/rocky/8.10/AppStream/x86_64/os/' \
  '--arch=x86_64,noarch' '--resolve' '--alldeps' \
  'python3-rpm-0:4.14.3-32.el8_10.x86_64' 'rpm-build-libs-0:4.14.3-32.el8_10.x86_64'; do
  grep -Fx -- "$arg" "$task_tmp/runtime-args" >/dev/null
done
if grep -E 'allowerasing|skip-broken|nobest|nodeps|nogpgcheck' "$task_tmp/runtime-args"; then exit 1; fi
pass
complete_rocky_rpm_runtime_closure "$task_tmp/complete"
[[ "$runtime_calls" == 1 ]]; pass

mkdir "$task_tmp/wrong-version"
put_rpm "$task_tmp/wrong-version" rpm-libs '0:4.14.3-32.el8_10'
put_rpm "$task_tmp/wrong-version" python3-rpm '0:4.14.3-31.el8'
if complete_rocky_rpm_runtime_closure "$task_tmp/wrong-version"; then exit 1; fi
[[ "$runtime_calls" == 1 ]]; pass

mkdir "$task_tmp/ambiguous"
put_rpm "$task_tmp/ambiguous" rpm-libs '0:4.14.3-32.el8_10'
put_rpm "$task_tmp/ambiguous" rpm-libs '0:4.14.3-31.el8'
if complete_rocky_rpm_runtime_closure "$task_tmp/ambiguous"; then exit 1; fi
pass

mkdir "$task_tmp/wrong-arch"
put_rpm "$task_tmp/wrong-arch" rpm-libs '0:4.14.3-32.el8_10' i686
if complete_rocky_rpm_runtime_closure "$task_tmp/wrong-arch"; then exit 1; fi
pass

for response in fail drift omit; do
  mkdir "$task_tmp/$response"
  put_rpm "$task_tmp/$response" rpm-libs '0:4.14.3-32.el8_10'
  if complete_rocky_rpm_runtime_closure "$task_tmp/$response"; then exit 1; fi
  pass
done
response=good

# Both component routes must complete the family before lock/repodata publishing.
_dnf_download docker test-root
target="$BUNDLE_ROOT/rocky8/docker"
grep -F 'python3-rpm|0:4.14.3-32.el8_10|x86_64|' "$target/PACKAGES.lock" >/dev/null
grep -F 'rpm-build-libs|0:4.14.3-32.el8_10|x86_64|' "$target/PACKAGES.lock" >/dev/null
[[ -s "$target/repodata/repomd.xml" ]]; pass
fetch_target_prerequisites
target="$BUNDLE_ROOT/rocky8/target-prerequisites"
grep -F 'python3-rpm|0:4.14.3-32.el8_10|x86_64|' "$target/PACKAGES.lock" >/dev/null
grep -F 'rpm-build-libs|0:4.14.3-32.el8_10|x86_64|' "$target/PACKAGES.lock" >/dev/null
pass

# Failed dependency resolution must preserve the last published repository.
for route in docker target-prerequisites; do
  target="$BUNDLE_ROOT/rocky8/$route"
  cp "$target/PACKAGES.lock" "$task_tmp/$route.before"
  response=fail
  if [[ "$route" == docker ]]; then
    if (_dnf_download docker test-root); then exit 1; fi
  else
    if (fetch_target_prerequisites); then exit 1; fi
  fi
  cmp "$target/PACKAGES.lock" "$task_tmp/$route.before"
  if compgen -G "$BUNDLE_ROOT/rocky8/.*.collect.*" >/dev/null; then exit 1; fi
  pass
done
printf 'rpm runtime closure collection: PASS (%s cases, mocked DNF/RPM, no downloads or installs)\n' "$passed"
