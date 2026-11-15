#!/usr/bin/env bash
# Malformed/unloadable installer modules must fail before product mutations.
set -euo pipefail
source_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fixture="$(mktemp -d)"
trap 'rm -rf -- "$fixture"' EXIT
mkdir -p "$fixture/installer/lib" "$fixture/installer/components"
cp "$source_root/install.sh" "$fixture/installer/install.sh"
cp "$source_root"/lib/*.sh "$fixture/installer/lib/"
cp "$source_root"/components/*.sh "$fixture/installer/components/"
export MUTATION_MARKER="$fixture/mutation"
cat > "$fixture/installer/lib/release-metadata.sh" <<'SOURCE'
load_release_bom() { touch "$MUTATION_MARKER"; return 1; }
SOURCE
printf 'broken() {\n' > "$fixture/installer/lib/zz-malformed.sh"
if bash "$fixture/installer/install.sh" --validate > "$fixture/output" 2>&1; then
  echo 'FAIL syntax-invalid source accepted' >&2; exit 1
fi
grep -q 'Installer source syntax invalid' "$fixture/output"
[[ ! -e "$MUTATION_MARKER" ]]
rm "$fixture/installer/lib/zz-malformed.sh"
printf '\nreturn 7\n' >> "$fixture/installer/lib/release-metadata.sh"
if bash "$fixture/installer/install.sh" --validate > "$fixture/output" 2>&1; then
  echo 'FAIL failing source accepted' >&2; exit 1
fi
[[ ! -e "$MUTATION_MARKER" ]]
rm "$fixture/installer/lib/nexus-backup.sh"
if SCRIPT_DIR="$fixture/installer" bash -c '. "$SCRIPT_DIR/components/deploy-system.sh"' > "$fixture/output" 2>&1; then
  echo 'FAIL missing nested backup helper accepted' >&2; exit 1
fi
echo 'PASS malformed module, failed source and missing nested backup helper are rejected'
