#!/usr/bin/env bash
set -euo pipefail
# 상세 인증 호환 경로의 기존 회귀 검사. 기본 설치는 unsigned-install-contract.sh에서 확인.
export OFFLINE_VERIFY_AUTHENTICITY=1
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
. "$script_dir/lib/release-metadata.sh"
. "$script_dir/lib/java-ci-payload.sh"
jq_bin="$(command -v jq)"
fixture_jq_bin="$jq_bin"
fixture="$(mktemp -d)"; root="$fixture/payload"
trap 'rm -rf -- "$fixture"' EXIT
mkdir -p "$root/evidence" "$root/maven-seed/sources/example" "$root/maven-seed/repository/example/library/1"
hash() { sha256sum "$1" | cut -d' ' -f1; }
inventory() {
  local directory="$1" excluded="$2" file relative
  while IFS= read -r -d '' file; do
    relative="${file#"$directory/"}"
    [[ "$relative" == "$excluded" ]] && continue
    "$jq_bin" -nc --arg p "$relative" --arg s "$(hash "$file")" --argjson n "$(stat -c %s "$file")" '{path:$p,size:$n,sha256:$s}'
  done < <(find "$directory" -type f -print0 | sort -z)
}
reject() { if "$@"; then printf 'Unexpected acceptance: %s\n' "$*" >&2; exit 1; fi; }
for role in builder runtime; do
  build="$fixture/$role"; mkdir -p "$build/blobs/sha256" "$root/inputs/$role"
  printf 'fixture layer %s' "$role" > "$build/layer"
  layer="$(hash "$build/layer")"; cp "$build/layer" "$build/blobs/sha256/$layer"
  "$jq_bin" -ncS --arg l "sha256:$layer" '{os:"linux",architecture:"amd64",rootfs:{type:"layers",diff_ids:[$l]}}' > "$build/config"
  config="$(hash "$build/config")"; cp "$build/config" "$build/blobs/sha256/$config"
  "$jq_bin" -ncS --arg c "sha256:$config" --arg l "sha256:$layer" '{schemaVersion:2,config:{digest:$c},layers:[{digest:$l}]}' > "$root/evidence/$role-manifest.json"
  manifest="$(hash "$root/evidence/$role-manifest.json")"; cp "$root/evidence/$role-manifest.json" "$build/blobs/sha256/$manifest"
  "$jq_bin" -ncS --arg m "sha256:$manifest" '{schemaVersion:2,manifests:[{digest:$m,platform:{os:"linux",architecture:"amd64"}}]}' > "$root/evidence/$role-index.json"
  index="$(hash "$root/evidence/$role-index.json")"; cp "$root/evidence/$role-index.json" "$build/blobs/sha256/$index"
  "$jq_bin" -ncS --arg c "blobs/sha256/$config" --arg l "blobs/sha256/$layer" --arg t "fixture-$role:v1" '[{Config:$c,Layers:[$l],RepoTags:[$t]}]' > "$build/manifest.json"
  tar -cf "$root/$role.tar" -C "$build" manifest.json blobs
  printf 'FROM scratch\n' > "$root/inputs/$role/Dockerfile"
  inventory "$root/inputs/$role" INPUTS.lock.json | "$jq_bin" -scS '{schemaVersion:1,files:.}' > "$fixture/input-lock"
  cp "$fixture/input-lock" "$root/inputs/$role/INPUTS.lock.json"
  "$jq_bin" -ncS --arg r "$role" --arg a "$(hash "$root/$role.tar")" --arg i "$index" --arg m "$manifest" --arg c "sha256:$config" \
    '{tag:("fixture-"+$r+":v1"),archive:($r+".tar"),archiveSha256:$a,index:("evidence/"+$r+"-index.json"),indexSha256:$i,manifest:("evidence/"+$r+"-manifest.json"),manifestSha256:$m,configDigest:$c,input:$r}' > "$fixture/$role-image.json"
  "$jq_bin" -ncS --arg r "$role" --arg p "$(hash "$root/inputs/$role/INPUTS.lock.json")" '{directory:("inputs/"+$r),lockSha256:$p}' > "$fixture/$role-input.json"
done
printf '<project/>' > "$root/maven-seed/sources/example/pom.xml"
printf '<project/>' > "$root/maven-seed/repository/example/library/1/library-1.pom"
inventory "$root/maven-seed" seed.lock.json | "$jq_bin" -scS --arg p "$(hash "$root/maven-seed/sources/example/pom.xml")" \
  '{schema:1,identity:{java:"fixture"},projects:[{name:"example",pom_sha256:$p,files:["pom.xml"]}],files:.}' > "$fixture/seed-lock"
cp "$fixture/seed-lock" "$root/maven-seed/seed.lock.json"
inventory "$root" CI-PAYLOAD.json | "$jq_bin" -scS --slurpfile b "$fixture/builder-image.json" --slurpfile r "$fixture/runtime-image.json" \
  --slurpfile bi "$fixture/builder-input.json" --slurpfile ri "$fixture/runtime-input.json" --arg s "$(hash "$root/maven-seed/seed.lock.json")" \
  '{schemaVersion:1,origin:"local-build-candidate-not-publisher-authenticated",images:{builder:$b[0],runtime:$r[0]},inputs:{builder:$bi[0],runtime:$ri[0]},seed:{directory:"maven-seed",lockSha256:$s},files:.}' > "$fixture/metadata"
cp "$fixture/metadata" "$root/CI-PAYLOAD.json"
java_ci_verify "$root" "$jq_bin"
printf extra > "$root/unlocked.txt"; reject java_ci_verify "$root" "$jq_bin"; rm "$root/unlocked.txt"
printf changed >> "$root/maven-seed/repository/example/library/1/library-1.pom"
reject java_ci_verify "$root" "$jq_bin"
printf '<project/>' > "$root/maven-seed/repository/example/library/1/library-1.pom"
"$jq_bin" -cS '.files += [.files[0]]' "$fixture/metadata" > "$root/CI-PAYLOAD.json"; reject java_ci_verify "$root" "$jq_bin"
"$jq_bin" -cS '.files[0].path="../escape"' "$fixture/metadata" > "$root/CI-PAYLOAD.json"; reject java_ci_verify "$root" "$jq_bin"
cp "$fixture/metadata" "$root/CI-PAYLOAD.json"
ln -s "$fixture/metadata" "$root/alias.json"; reject java_ci_verify "$root" "$jq_bin"; rm "$root/alias.json"
printf '{"a":{"x":1},"a":{"y":2}}\n' > "$fixture/duplicates.json"
reject java_ci_json_unique "$fixture/duplicates.json" "$jq_bin"
printf '{"a":[{"x":1},{"x":2}],"b":{}}\n' > "$fixture/unique.json"
java_ci_json_unique "$fixture/unique.json" "$jq_bin"
java_ci_verify "$root" "$jq_bin"
cp "$root/builder.tar" "$fixture/good-builder.tar"
cp "$root/evidence/builder-manifest.json" "$fixture/good-platform.json"
cp "$root/evidence/builder-index.json" "$fixture/good-index.json"
cp "$fixture/builder/manifest.json" "$fixture/good-docker-manifest.json"
cp "$root/CI-PAYLOAD.json" "$fixture/good-metadata.json"
repack_builder() {
  local metadata="$fixture/repack-metadata.json" files="$fixture/repack-files.json"
  tar -cf "$root/builder.tar" -C "$fixture/builder" manifest.json blobs
  "$fixture_jq_bin" -cS --arg a "$(hash "$root/builder.tar")" '.images.builder.archiveSha256=$a' "$root/CI-PAYLOAD.json" > "$metadata"
  inventory "$root" CI-PAYLOAD.json | "$fixture_jq_bin" -s . > "$files"
  "$fixture_jq_bin" -cS --slurpfile f "$files" '.files=$f[0]' "$metadata" > "$root/CI-PAYLOAD.json"
}
refresh_archive_hashes() {
  "$fixture_jq_bin" -cS --arg a "$(hash "$root/builder.tar")" '.images.builder.archiveSha256=$a' "$root/CI-PAYLOAD.json" > "$fixture/repack-metadata.json"
  inventory "$root" CI-PAYLOAD.json | "$fixture_jq_bin" -s . > "$fixture/repack-files.json"
  "$fixture_jq_bin" -cS --slurpfile f "$fixture/repack-files.json" '.files=$f[0]' "$fixture/repack-metadata.json" > "$root/CI-PAYLOAD.json"
}
restore_builder() {
  cp "$fixture/good-builder.tar" "$root/builder.tar"
  cp "$fixture/good-platform.json" "$root/evidence/builder-manifest.json"
  cp "$fixture/good-index.json" "$root/evidence/builder-index.json"
  cp "$fixture/good-docker-manifest.json" "$fixture/builder/manifest.json"
  cp "$fixture/good-metadata.json" "$root/CI-PAYLOAD.json"
}
# Outer inventory and every declared archive pin stay correct for malformed inner JSON.
sed 's/"Config":/"Config":"ignored","Config":/' "$fixture/good-docker-manifest.json" > "$fixture/builder/manifest.json"
repack_builder
java_ci_inventory "$root" "$root/CI-PAYLOAD.json" "$fixture_jq_bin" CI-PAYLOAD.json
reject image_archive_id_for_tag "$root/builder.tar" fixture-builder:v1 "$fixture_jq_bin"
reject java_ci_verify "$root" "$fixture_jq_bin"
restore_builder
# Duplicate regular archive member also reaches the safe member extractor.
tar -rf "$root/builder.tar" -C "$fixture/builder" manifest.json
refresh_archive_hashes
java_ci_inventory "$root" "$root/CI-PAYLOAD.json" "$fixture_jq_bin" CI-PAYLOAD.json
reject image_archive_id_for_tag "$root/builder.tar" fixture-builder:v1 "$fixture_jq_bin"
reject java_ci_verify "$root" "$fixture_jq_bin"
restore_builder
sed 's/"os":/"os":"ignored","os":/' "$fixture/builder/config" > "$fixture/duplicate-config.json"
new_config="$(hash "$fixture/duplicate-config.json")"
cp "$fixture/duplicate-config.json" "$fixture/builder/blobs/sha256/$new_config"
"$fixture_jq_bin" -cS --arg c "blobs/sha256/$new_config" '.[0].Config=$c' "$fixture/good-docker-manifest.json" > "$fixture/builder/manifest.json"
"$fixture_jq_bin" -cS --arg c "sha256:$new_config" '.config.digest=$c' "$fixture/good-platform.json" > "$root/evidence/builder-manifest.json"
new_manifest="$(hash "$root/evidence/builder-manifest.json")"
cp "$root/evidence/builder-manifest.json" "$fixture/builder/blobs/sha256/$new_manifest"
"$fixture_jq_bin" -cS --arg m "sha256:$new_manifest" '.manifests[0].digest=$m' "$fixture/good-index.json" > "$root/evidence/builder-index.json"
new_index="$(hash "$root/evidence/builder-index.json")"
cp "$root/evidence/builder-index.json" "$fixture/builder/blobs/sha256/$new_index"
"$fixture_jq_bin" -cS --arg c "sha256:$new_config" --arg m "$new_manifest" --arg i "$new_index" \
  '.images.builder.configDigest=$c|.images.builder.manifestSha256=$m|.images.builder.indexSha256=$i' "$root/CI-PAYLOAD.json" > "$fixture/reconfigured.json"
cp "$fixture/reconfigured.json" "$root/CI-PAYLOAD.json"
repack_builder
java_ci_inventory "$root" "$root/CI-PAYLOAD.json" "$fixture_jq_bin" CI-PAYLOAD.json
reject image_archive_id_for_tag "$root/builder.tar" fixture-builder:v1 "$fixture_jq_bin"
reject java_ci_verify "$root" "$fixture_jq_bin"
restore_builder
java_ci_verify "$root" "$fixture_jq_bin"
mkdir -p "$fixture/bundles/common" "$fixture/runner-config"
mv "$root" "$fixture/bundles/common/java-ci"; root="$fixture/bundles/common/java-ci"
BUNDLE_ROOT="$fixture/bundles"; RUNNER_CONFIG="$fixture/runner-config"
BOM_RUNNER_IMAGE=fixture-runner:v1; BOM_RUNNER_HELPER_IMAGE=fixture-helper:v1; BOM_RUNNER_BUILD_IMAGE=fixture-cli:v1
LOCK_RUNNER_ARCHIVE_ID=fixture; LOCK_RUNNER_HELPER_ARCHIVE_ID=fixture; LOCK_RUNNER_BUILD_ARCHIVE_ID=fixture
. "$script_dir/components/gitlab-runner.sh"
# External Docker/GitLab adapters are mocked; real CI preload/status composition is exercised.
container_exists() { return 0; }
image_matches_archive_id() { return 0; }
container_matches_archive_id() { return 0; }
container_runtime_healthy() { return 0; }
container_mount_destination_present() { return 0; }
docker_jq_binary() { printf '%s' "$fixture_jq_bin"; }
policy_rc=0
gitlab_runner_server_policy_contract() { return "$policy_rc"; }
docker() {
  if [[ "$1 $2" == 'image inspect' ]]; then
    if [[ "$3" == --help ]]; then printf '%s\n' '--platform'; return 0; fi
    [[ -f "$fixture/loaded" ]] || return 1
    local tag="${!#}" role
    role="${tag#fixture-}"; role="${role%:v1}"
    printf 'linux|amd64|%s\n' "$("$jq_bin" -r --arg r "$role" '.images[$r].configDigest' "$root/CI-PAYLOAD.json")"
  elif [[ "$1" == load ]]; then
    touch "$fixture/loaded"
  elif [[ "$1" == exec ]]; then
    return 0
  else return 1; fi
}
[[ "$(gitlab_runner_status)" == drift ]]
gitlab_runner_ci_preload
[[ "$(gitlab_runner_status)" == unregistered ]]
mv "$root/CI-PAYLOAD.json" "$fixture/status-metadata.json"
[[ "$(gitlab_runner_status)" == drift ]]
printf '{invalid json\n' > "$root/CI-PAYLOAD.json"
[[ "$(gitlab_runner_status)" == drift ]]
"$fixture_jq_bin" -cS '.images={}' "$fixture/status-metadata.json" > "$root/CI-PAYLOAD.json"
[[ "$(gitlab_runner_status)" == drift ]]
cp "$fixture/status-metadata.json" "$root/CI-PAYLOAD.json"
cat > "$RUNNER_CONFIG/config.toml" <<'EOF'
[[runners]]
  executor = "docker"
  [runners.docker]
    image = "fixture-cli:v1"
    pull_policy = ["never"]
    allowed_pull_policies = ["never"]
EOF
[[ "$(gitlab_runner_status)" == installed ]]
policy_rc=1
[[ "$(gitlab_runner_status)" == unverified ]]
rm "$fixture/loaded"
[[ "$(gitlab_runner_status)" == drift ]]
printf 'Java CI Bash contract regressions: PASS (synthetic fixtures only)\n'
