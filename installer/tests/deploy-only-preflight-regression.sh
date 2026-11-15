#!/usr/bin/env bash
# Exercise the real verifier's JAR gate using actual tiny ZIPs, without services/network.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"' EXIT
. "$root/lib/release-metadata.sh"
load_release_bom "$root/bundles/RELEASE-BOM.env"
python_bin="$(command -v python3 || true)"
[[ -n "$python_bin" ]] || python_bin=/usr/libexec/platform-python
[[ -x "$python_bin" ]] || { echo 'Python3 required' >&2; exit 1; }
"$python_bin" - "$tmp" "$BOM_DEPLOYOPS_VERSION" "$BOM_DEPLOYOPS_REQUIRED_MIGRATIONS" <<'PY'
import sys,zipfile
from pathlib import Path
base=Path(sys.argv[1])
with zipfile.ZipFile(str(base/'deploy.jar'),'w') as jar:
    jar.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nImplementation-Version: '+sys.argv[2]+'\n')
    for migration in sys.argv[3].split(','):
        jar.writestr('BOOT-INF/classes/db/migration/'+migration,'-- fixture')
for name,version in [('valid.jar','0.1.0-SNAPSHOT'),('wrong.jar','99.0.0')]:
    with zipfile.ZipFile(str(base/name),'w') as jar:
        jar.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nImplementation-Version: '+version+'\n')
PY
jar_gate="$(sed -n '/^if \[\[ -n "\$archive_tool" \]\]; then$/,/^verify_os_bundle() {/p' "$root/verify-offline-bundle.sh" | sed '$d')"
[[ -n "$jar_gate" ]] || { echo 'Real verifier JAR gate not found' >&2; exit 1; }
ok() { :; }
fail() { errors=$((errors+1)); failures+="$*"$'\n'; }
deploy_jar="$tmp/deploy.jar"
archive_python="$python_bin"
for archive_tool in python unzip; do
  [[ "$archive_tool" != unzip ]] || command -v unzip >/dev/null || continue
  for scenario in excluded included-missing included-valid included-wrong legacy-missing; do
    errors=0; failures=''; worksystem_manifest=''
    BOM_WORKSYSTEM_VERSION=0.1.0-SNAPSHOT
    case "$scenario" in
      excluded) BOM_WORKSYSTEM_INCLUDED=false; worksystem_jar="$tmp/missing.jar" ;;
      included-missing) BOM_WORKSYSTEM_INCLUDED=true; worksystem_jar="$tmp/missing.jar" ;;
      included-valid) BOM_WORKSYSTEM_INCLUDED=true; worksystem_jar="$tmp/valid.jar" ;;
      included-wrong) BOM_WORKSYSTEM_INCLUDED=true; worksystem_jar="$tmp/wrong.jar" ;;
      legacy-missing) unset BOM_WORKSYSTEM_INCLUDED; worksystem_jar="$tmp/missing.jar" ;;
    esac
    eval "$jar_gate"
    case "$scenario" in
      excluded|included-valid) [[ "$errors" == 0 ]] ;;
      *) [[ "$errors" == 1 && "$failures" == *'Worksystem JAR version does not match BOM'* ]] ;;
    esac
    printf 'PASS real JAR gate: %s / %s\n' "$archive_tool" "$scenario"
  done
done
# Require explicit deploy-only metadata for a payload that omits Worksystem.
load_release_bom "$root/bundles/RELEASE-BOM.env"
sed '/^BOM_WORKSYSTEM_/d' "$root/bundles/RELEASE-BOM.env" > "$tmp/deploy-only-bom"
printf 'BOM_WORKSYSTEM_INCLUDED=false\n' >> "$tmp/deploy-only-bom"
load_release_bom "$tmp/deploy-only-bom"
[[ "$BOM_WORKSYSTEM_INCLUDED" == false && -z "${BOM_WORKSYSTEM_VERSION+x}" ]]
echo 'PASS explicit exclusion removes optional version metadata; legacy missing remains rejected'
