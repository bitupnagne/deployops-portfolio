#!/usr/bin/env bash
# Regression checks for release image locking, offline archive validation,
# Docker daemon.json merging and GitLab Runner registration policy options.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT
PYTHON_BIN="$(command -v python3 || true)"
if [[ -z "$PYTHON_BIN" && -x /usr/libexec/platform-python ]]; then
  PYTHON_BIN=/usr/libexec/platform-python
fi
[[ -n "$PYTHON_BIN" ]] || { echo 'Python 3 is required for JAR fixture checks' >&2; exit 1; }

# shellcheck disable=SC1091
. "$SCRIPT_DIR/lib/release-metadata.sh"
. "$SCRIPT_DIR/lib/rpm-signing-keys.sh"
load_release_bom "$SCRIPT_DIR/bundles/RELEASE-BOM.env"

# Answers are non-executable data while retaining quoted Korean/spaces/comments.
# shellcheck disable=SC1091
. "$SCRIPT_DIR/lib/ui.sh"
init_ui
for answers_file in "$SCRIPT_DIR/answers.example.env" "$SCRIPT_DIR"/profiles/*.env "$SCRIPT_DIR"/sim/answers/*.env; do
  [[ -f "$answers_file" ]] || continue
  ( load_answers "$answers_file" >/dev/null ) \
    || { echo "shipped answers file rejected by strict parser: $answers_file" >&2; exit 1; }
done
answer_marker="$tmpdir/answer-code-executed"
cat > "$tmpdir/answers-malicious.env" <<EOF
DOCKER_GROUP_USER=\$(touch $answer_marker)
WORKSYSTEM_ADMIN_NAME="홍 길동 # 운영" # inline comment
EOF
load_answers "$tmpdir/answers-malicious.env" >/dev/null
[[ ! -e "$answer_marker" ]] || { echo "answers file executed shell code" >&2; exit 1; }
[[ "$DOCKER_GROUP_USER" == "\$(touch $answer_marker)" ]] \
  || { echo "command substitution was not retained as literal data" >&2; exit 1; }
[[ "$WORKSYSTEM_ADMIN_NAME" == "홍 길동 # 운영" ]] \
  || { echo "quoted Korean/spaces/comment parsing failed" >&2; exit 1; }

for image in "$BOM_GITLAB_IMAGE" "$BOM_RUNNER_IMAGE" "$BOM_RUNNER_HELPER_IMAGE" "$BOM_NEXUS_IMAGE"; do
  [[ "$image" != *:latest ]] || { echo "latest image in release BOM: $image" >&2; exit 1; }
done

# Exercise the collector's fail-closed source RepoDigest lock without network access.
# Boundary: Docker, downloaded package bytes, RPM metadata/signature commands are
# synthetic collector fixtures, not evidence of vendor RPM signature validation.
# Actual shipped GPG keys, key fingerprints/export hashes, image archive hashes,
# and OpenSSL manifest signatures below use real verification tools.
for prerequisite in gpg openssl; do
  command -v "$prerequisite" >/dev/null || { echo "Required test tool missing: $prerequisite" >&2; exit 1; }
done
fetch_fixture="$tmpdir/fetch-installer"
fakebin="$tmpdir/fakebin"
mkdir -p "$fetch_fixture/bundles" "$fetch_fixture/lib" "$fakebin"
mkdir -p "$fetch_fixture/components"
cp "$SCRIPT_DIR/components/target-prerequisites.sh" "$fetch_fixture/components/target-prerequisites.sh"
cp "$SCRIPT_DIR/bundles/fetch-bundles.sh" "$fetch_fixture/bundles/fetch-bundles.sh"
cp "$SCRIPT_DIR/bundles/RELEASE-BOM.env" "$fetch_fixture/bundles/RELEASE-BOM.env"
cp "$SCRIPT_DIR/lib/release-metadata.sh" "$fetch_fixture/lib/release-metadata.sh"
cp "$SCRIPT_DIR/lib/image-archive-member.sh" "$fetch_fixture/lib/image-archive-member.sh"
cp "$SCRIPT_DIR/lib/rpm-repository.sh" "$fetch_fixture/lib/rpm-repository.sh"
cp "$SCRIPT_DIR/lib/rpm-signing-keys.sh" "$fetch_fixture/lib/rpm-signing-keys.sh"
cp "$SCRIPT_DIR/lib/mariadb-packages.sh" "$fetch_fixture/lib/mariadb-packages.sh"
export FIXTURE_REAL_RPM_KEYS="$SCRIPT_DIR/bundles/common/rpm-keys"
write_fixture_rpm_keys() {
  mkdir -p "$1/common/rpm-keys"
  rpm_key_contracts > "$1/common/rpm-keys/KEYS.lock"
  for key in rocky8 docker nginx mariadb; do cp "$FIXTURE_REAL_RPM_KEYS/$key.gpg" "$1/common/rpm-keys/$key.gpg"; done
}
write_fixture_rpm_keys "$fetch_fixture/bundles"
verify_rpm_signing_keys "$fetch_fixture/bundles/common/rpm-keys"
cp "$fetch_fixture/bundles/common/rpm-keys/docker.gpg" "$tmpdir/docker-key.safe"
printf 'tampered' >> "$fetch_fixture/bundles/common/rpm-keys/docker.gpg"
if verify_rpm_signing_keys "$fetch_fixture/bundles/common/rpm-keys" >/dev/null 2>&1; then
  echo 'Real signing-key digest check accepted modified key bytes' >&2; exit 1
fi
cp "$tmpdir/docker-key.safe" "$fetch_fixture/bundles/common/rpm-keys/docker.gpg"
cat > "$fakebin/docker" <<'SH'
#!/usr/bin/env bash
set -eu
config='{"architecture":"amd64","os":"linux","rootfs":{"type":"layers","diff_ids":[]}}'
config_id="$(printf '%s' "$config" | /usr/bin/sha256sum | awk '{print $1}')"
platform_manifest="{\"schemaVersion\":2,\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\",\"config\":{\"digest\":\"sha256:$config_id\",\"size\":${#config}},\"layers\":[]}"
source_digest="$(printf '%s' "$platform_manifest" | /usr/bin/sha256sum | awk '{print $1}')"
case "$1" in
  buildx)
    [[ "${2:-}" == version ]] && exit 0
    [[ "${2:-}" == imagetools && "${3:-}" == inspect ]] || exit 2
    if [[ " $* " == *" --raw "* ]]; then
      printf '%s' "$platform_manifest"
    else
      if [[ "${FAKE_MANIFEST_MOVE:-}" == 1 ]]; then
        printf '{"digest":"sha256:%064d"}\n' 7
      else
        printf '{"digest":"sha256:%s"}\n' "$source_digest"
      fi
    fi
    ;;
  pull)
    [[ "${FAKE_NO_IMAGE_MUTATIONS:-}" != 1 ]] || exit 1
    [[ "$2" == *@sha256:* ]] || { echo "collector pulled a mutable tag: $2" >&2; exit 1; }
    if [[ -n "${FAKE_DOCKER_FAIL_PULL:-}" && "$2" == "${FAKE_DOCKER_FAIL_PULL%:*}"@* ]]; then exit 1; fi
    ;;
  tag) ;;
  save)
    [[ "${FAKE_NO_IMAGE_MUTATIONS:-}" != 1 ]] || exit 1
    shift
    tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
    printf '%s' "$config" > "$tmp/$config_id.json"
    {
      printf '[{"Config":"%s.json","RepoTags":[' "$config_id"
      first=1
      for tag in "$@"; do
        [[ "$first" == 1 ]] || printf ','
        printf '"%s"' "$tag"; first=0
      done
      printf '],"Layers":[]}]\n'
    } > "$tmp/manifest.json"
    tar -cf - -C "$tmp" manifest.json "$config_id.json"
    ;;
  image)
    [[ "${3:-}" == --help ]] && { printf '%s\n' '--format string'; exit 0; }
    format="$4"; image="${*: -1}"
    if [[ "$format" == *RepoDigests* ]]; then
      repo="${image%:*}"
      case "$repo" in docker) repo='docker.io/library/docker' ;; esac
      printf '%s@sha256:%s\n' "$repo" "$source_digest"
    elif [[ "$format" == *Architecture* ]]; then
      printf 'linux|amd64|sha256:%s\n' "$config_id"
    else
      printf 'sha256:%s\n' "$config_id"
    fi
    ;;
  *) exit 2 ;;
esac
SH
cat > "$fakebin/curl" <<'SH'
#!/usr/bin/env bash
set -eu
output=''; head_only=0
while [[ $# -gt 0 ]]; do
  if [[ "$1" == '-o' ]]; then output="$2"; shift 2
  elif [[ "$1" == -*I* ]]; then head_only=1; shift
  else shift; fi
done
[[ "$head_only" == 0 ]] || exit 0
[[ -n "$output" ]] || exit 2
case "$output" in
  */rocky8.asc|*/docker.asc|*/nginx.asc|*/mariadb.asc) key="${output##*/}"; cp "$FIXTURE_REAL_RPM_KEYS/${key%.asc}.gpg" "$output" ;;
  *jq-linux-amd64.part) cp /usr/bin/jq "$output" ;;
  *) printf 'fixture-java\n' > "$output" ;;
esac
SH
cat > "$fakebin/sha256sum" <<'SH'
#!/usr/bin/env bash
case "${1:-}" in
  *OpenJDK21U-jdk_x64_linux_hotspot_21.0.12.1_1.tar.gz.part)
    printf 'ce79869e1307ed8ee1e2baa86a412b1eb5b75d10a01006d788a6f968bcfaee94  %s\n' "$1" ;;
  *jq-linux-amd64.part)
    if [[ "${FAKE_JQ_SHA_BAD:-}" == 1 ]]; then
      printf '%064d  %s\n' 0 "$1"
    else
      printf '5942c9b0934e510ee61eb3e30273f1b3fe2590df93933a93d7c58b81d19c8ff5  %s\n' "$1"
    fi
    ;;
  *) exec /usr/bin/sha256sum "$@" ;;
esac
SH
cat > "$fakebin/dnf" <<'SH'
#!/usr/bin/env bash
[[ "${FAKE_DNF_FAIL:-}" != 1 ]] || exit 1
dest=''; packages=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --destdir) dest="$2"; shift 2 ;;
    download|--resolve|--alldeps|--arch=*|--releasever=*|--forcearch=*|--disablerepo=*|--repofrompath=*|--enablerepo=*|--setopt=*) shift ;;
    *) packages+=("$1"); shift ;;
  esac
done
[[ -n "$dest" ]] || exit 2
for package in "${packages[@]}"; do
  name="$package"; [[ "$package" != MariaDB-*-0:* ]] || name="${package%-0:*}"
  printf '%s' "$package" > "$dest/$name.pkg.rpm"
done
printf '%s' 'libcom_err' > "$dest/libcom_err.pkg.rpm"
SH
cat > "$fakebin/rpm" <<'SH'
#!/usr/bin/env bash
[[ " $* " != *' --initdb '* ]] || exit 0
[[ " $* " != *' %{ARCH} '* ]] || { printf 'x86_64'; exit 0; }
file="${*: -1}"; package="$(awk '{print $1}' "$file")"
case "$file" in *.rpm) version='0:1.0-1' ;; *) version='1.0' ;; esac
if [[ "$package" == MariaDB-*-0:* ]]; then
  version="${package#*-0:}"; version="0:${version%.x86_64}"; package="${package%-0:*}"
fi
printf '%s|%s|x86_64\n' "$package" "$version"
SH
cat > "$fakebin/rpmkeys" <<'SH'
#!/usr/bin/env bash
file="${*: -1}"
if [[ " $* " == *' --import '* ]]; then
  gpg --batch --with-colons --show-keys "$file" >/dev/null 2>&1
  exit $?
fi
[[ " $* " == *' --checksig '* && -f "$file" ]] || exit 2
body="$(cat "$file")"
[[ "$body" =~ ^[A-Za-z0-9._:+-]+([[:space:]]fixture)?$ ]] || exit 3
printf '%s: digests signatures OK\n' "$file"
SH
chmod +x "$fakebin/rpmkeys"
printf 'not a synthetic package!' > "$tmpdir/rejected-fixture.rpm"
if "$fakebin/rpmkeys" --checksig "$tmpdir/rejected-fixture.rpm" >/dev/null; then
  echo 'RPM signature mock accepted data outside its explicit synthetic fixture boundary' >&2; exit 1
fi
cat > "$fakebin/dpkg-deb" <<'SH'
#!/usr/bin/env bash
file="$2"; field="$3"; package="$(awk '{print $1}' "$file")"
case "$field" in Package) printf '%s\n' "$package" ;; Version) printf '1.0\n' ;; Architecture) printf 'x86_64\n' ;; *) exit 2 ;; esac
SH
chmod +x "$fakebin/docker" "$fakebin/curl" "$fakebin/sha256sum" "$fakebin/dnf" "$fakebin/rpm" "$fakebin/dpkg-deb"
cat > "$fakebin/createrepo_c" <<'SH'
#!/usr/bin/env bash
[[ "$1" == --outputdir ]]
mkdir -p "$2/repodata"
printf 'fixture metadata' > "$2/repodata/repomd.xml"
SH
chmod +x "$fakebin/createrepo_c"

# Package collectors complete a staging directory before replacing the prior target.
mkdir -p "$fetch_fixture/bundles/rocky8/docker"
PATH="$fakebin:$PATH" FAKE_DNF_FAIL=1 FAKE_NO_IMAGE_MUTATIONS=1 bash -c \
  'source "$1" --os rocky8; declare -F write_package_lock >/dev/null' _ "$fetch_fixture/bundles/fetch-bundles.sh"
printf 'old-version' > "$fetch_fixture/bundles/rocky8/docker/docker-ce-old.rpm"
PATH="$fakebin:$PATH" bash "$fetch_fixture/bundles/fetch-bundles.sh" --os rocky8 --packages >/dev/null
[[ ! -e "$fetch_fixture/bundles/rocky8/docker/docker-ce-old.rpm" ]] \
  || { echo "atomic package collection retained an old package" >&2; exit 1; }
awk -F'|' '!seen[$1]++ { next } { exit 1 }' "$fetch_fixture/bundles/rocky8/docker/PACKAGES.lock" \
  || { echo "collector emitted duplicate package identities" >&2; exit 1; }
printf 'preserve-on-failure' > "$fetch_fixture/bundles/rocky8/docker/preserve.marker"
if PATH="$fakebin:$PATH" FAKE_DNF_FAIL=1 \
    bash "$fetch_fixture/bundles/fetch-bundles.sh" --os rocky8 --packages >/dev/null 2>&1; then
  echo "package collector accepted a failed staged download" >&2; exit 1
fi
[[ "$(cat "$fetch_fixture/bundles/rocky8/docker/preserve.marker")" == preserve-on-failure ]] \
  || { echo "failed staged package collection changed the prior target" >&2; exit 1; }

export PATH="$fakebin:$PATH"
if PATH="$fakebin:$PATH" bash "$fetch_fixture/bundles/fetch-bundles.sh" --os rocky8 --images >/dev/null 2>&1; then
  echo "collector accepted a first image release without explicit digest approval" >&2
  exit 1
fi
PATH="$fakebin:$PATH" bash "$fetch_fixture/bundles/fetch-bundles.sh" --os rocky8 --images \
  --approve-release-digests >/dev/null
PATH="$fakebin:$PATH" FAKE_NO_IMAGE_MUTATIONS=1 bash "$fetch_fixture/bundles/fetch-bundles.sh" \
  --os rocky8 --lock-images-only >/dev/null
if PATH="$fakebin:$PATH" FAKE_JQ_SHA_BAD=1 \
    bash "$fetch_fixture/bundles/fetch-bundles.sh" --os rocky8 --images >/dev/null 2>&1; then
  echo "collector accepted a jq binary with the wrong SHA-256" >&2
  exit 1
fi
[[ ! -e "$fetch_fixture/bundles/common/jq/jq-linux-amd64.part" ]] \
  || { echo "collector left an unverified jq partial file" >&2; exit 1; }
load_release_lock "$fetch_fixture/bundles/RELEASE-LOCK.env"
PATH="$fakebin:$PATH" bash "$fetch_fixture/bundles/fetch-bundles.sh" --os rocky8 --check-release >/dev/null
if PATH="$fakebin:$PATH" FAKE_MANIFEST_MOVE=1 \
    bash "$fetch_fixture/bundles/fetch-bundles.sh" --os rocky8 --check-release >/dev/null 2>&1; then
  echo "collector check accepted a mutable image tag that moved after release lock" >&2
  exit 1
fi
if PATH="$fakebin:$PATH" FAKE_MANIFEST_MOVE=1 \
    bash "$fetch_fixture/bundles/fetch-bundles.sh" --os rocky8 --images >/dev/null 2>&1; then
  echo "collector silently approved a moved tag through the normal --images path" >&2
  exit 1
fi
for digest_name in LOCK_GITLAB_DIGEST LOCK_RUNNER_DIGEST LOCK_RUNNER_HELPER_DIGEST \
    LOCK_RUNNER_BUILD_DIGEST LOCK_NEXUS_DIGEST; do
  [[ "${!digest_name}" =~ ^sha256:[0-9a-f]{64}$ ]] \
    || { echo "collector emitted invalid digest: $digest_name" >&2; exit 1; }
done
for id_name in LOCK_GITLAB_ARCHIVE_ID LOCK_RUNNER_ARCHIVE_ID LOCK_RUNNER_HELPER_ARCHIVE_ID \
    LOCK_RUNNER_BUILD_ARCHIVE_ID LOCK_NEXUS_ARCHIVE_ID; do
  release_image_id_valid "${!id_name}" \
    || { echo "collector emitted invalid archive ID: $id_name" >&2; exit 1; }
done
if PATH="$fakebin:$PATH" FAKE_DOCKER_FAIL_PULL="$BOM_GITLAB_IMAGE" \
    bash "$fetch_fixture/bundles/fetch-bundles.sh" --os rocky8 --images >/dev/null 2>&1; then
  echo "collector accepted a failed image pull" >&2
  exit 1
fi
[[ ! -e "$fetch_fixture/bundles/RELEASE-LOCK.env" ]] \
  || { echo "collector left a stale release lock after failure" >&2; exit 1; }

fixture="$tmpdir/bundles"
mkdir -p "$fixture/common/"{deploy-system,worksystem-server,java,gitlab,nexus,gitlab-runner,jq} \
  "$fixture/common/release-evidence" "$fixture/rocky8/docker"
write_fixture_docker_packages() {
  local dir="$1" kind="$2"; shift 2
  local package file sha version
  mkdir -p "$dir"
  : > "$dir/PACKAGES.lock"
  for package in "$@"; do
    file="$package-1.0.x86_64.$kind"
    printf '%s\n' "$package fixture" > "$dir/$file"
    sha="$(sha256sum "$dir/$file" | awk '{print $1}')"
    version=1.0; [[ "$kind" != rpm ]] || version=0:1.0-1
    printf '%s|%s|x86_64|%s|%s\n' "$package" "$version" "$sha" "$file" >> "$dir/PACKAGES.lock"
  done
  if [[ "$kind" == rpm ]]; then
    mkdir -p "$dir/repodata"
    printf 'fixture metadata' > "$dir/repodata/repomd.xml"
    (cd "$dir" && sha256sum repodata/repomd.xml > REPODATA.sha256)
  fi
}
write_fixture_rpm_keys "$fixture"
write_fixture_docker_packages "$fixture/rocky8/docker" rpm \
  docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin libcom_err
write_fixture_docker_packages "$fixture/rocky8/mariadb" rpm mariadb-server mariadb mariadb-common
write_fixture_docker_packages "$fixture/rocky8/nginx" rpm nginx
write_fixture_docker_packages "$fixture/rocky8/target-prerequisites" rpm \
  openssh-server openssh-clients sudo policycoreutils policycoreutils-python-utils \
  iproute procps-ng shadow-utils util-linux tar gzip curl
cp "$SCRIPT_DIR/bundles/RELEASE-BOM.env" "$fixture/RELEASE-BOM.env"
cp "$(command -v jq)" "$fixture/common/jq/jq-linux-amd64"
chmod +x "$fixture/common/jq/jq-linux-amd64"
fixture_jq_sha="$(sha256sum "$fixture/common/jq/jq-linux-amd64" | awk '{print $1}')"
sed -i "s/^BOM_JQ_SHA256=.*/BOM_JQ_SHA256=$fixture_jq_sha/" "$fixture/RELEASE-BOM.env"
printf 'java\n' > "$fixture/common/java/$BOM_JAVA_ARCHIVE"
fixture_java_sha="$(sha256sum "$fixture/common/java/$BOM_JAVA_ARCHIVE" | awk '{print $1}')"
sed -i "s/^BOM_JAVA_SHA256=.*/BOM_JAVA_SHA256=$fixture_java_sha/" "$fixture/RELEASE-BOM.env"
"$PYTHON_BIN" - "$fixture/common/worksystem-server/worksystem.jar" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "w") as jar:
    jar.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nImplementation-Version: 0.1.0-SNAPSHOT\n")
PY

# The synthetic positive fixture follows the already validated current BOM.
# Removing required resources below must still make the real verifier fail.
"$PYTHON_BIN" - "$fixture/common/deploy-system/deploy-ops.jar" "$BOM_DEPLOYOPS_REQUIRED_MIGRATIONS" <<'PY'
import re, sys, zipfile
migrations = sys.argv[2].split(",")
if not migrations or len(set(migrations)) != len(migrations) or any(
    not re.fullmatch(r"V[0-9]+__[A-Za-z0-9_]+\.sql", migration) for migration in migrations
):
    raise SystemExit("Invalid or duplicate fixture migration contract")
with zipfile.ZipFile(sys.argv[1], "w") as jar:
    jar.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nImplementation-Version: 1.0.0-SNAPSHOT\n")
    for migration in migrations:
        jar.writestr(f"BOOT-INF/classes/db/migration/{migration}", "-- fixture")
PY

make_image_archive() {
  local target="$1"; shift
  local build="$tmpdir/image-manifest"
  rm -rf "$build"; mkdir -p "$build"
  local config id layer_sha
  printf 'fixture layer for %s' "$*" > "$build/layer.tar"
  layer_sha="$(sha256sum "$build/layer.tar" | awk '{print $1}')"
  config="{\"os\":\"linux\",\"architecture\":\"amd64\",\"tags\":\"$*\",\"rootfs\":{\"type\":\"layers\",\"diff_ids\":[\"sha256:$layer_sha\"]}}"
  id="$(printf '%s' "$config" | sha256sum | awk '{print $1}')"
  printf '%s' "$config" > "$build/$id.json"
  {
    printf '[{"Config":"%s.json","RepoTags":[' "$id"
    local first=1 tag
    for tag in "$@"; do
      (( first )) || printf ','
      printf '"%s"' "$tag"
      first=0
    done
    printf '],"Layers":["layer.tar"]}]\n'
  } > "$build/manifest.json"
  # docker-save layer member는 안전한 digest/layer.tar 형식을 사용한다.
  mkdir -p "$build/$layer_sha"
  mv "$build/layer.tar" "$build/$layer_sha/layer.tar"
  sed -i "s#\"layer.tar\"#\"$layer_sha/layer.tar\"#" "$build/manifest.json"
  tar -czf "$target" -C "$build" manifest.json "$id.json" "$layer_sha/layer.tar"
}

write_lock() {
  local gitlab_id runner_id helper_id build_id nexus_id jar_sha worksystem_sha
  local gitlab_digest runner_digest helper_digest build_digest nexus_digest
  gitlab_id="$(image_archive_id_for_tag "$fixture/common/gitlab/gitlab.tar.gz" "$BOM_GITLAB_IMAGE" "$(command -v jq)")"
  runner_id="$(image_archive_id_for_tag "$fixture/common/gitlab-runner/gitlab-runner.tar.gz" "$BOM_RUNNER_IMAGE" "$(command -v jq)")"
  helper_id="$(image_archive_id_for_tag "$fixture/common/gitlab-runner/gitlab-runner.tar.gz" "$BOM_RUNNER_HELPER_IMAGE" "$(command -v jq)")"
  build_id="$(image_archive_id_for_tag "$fixture/common/gitlab-runner/gitlab-runner.tar.gz" "$BOM_RUNNER_BUILD_IMAGE" "$(command -v jq)")"
  nexus_id="$(image_archive_id_for_tag "$fixture/common/nexus/nexus.tar.gz" "$BOM_NEXUS_IMAGE" "$(command -v jq)")"
  gitlab_digest="sha256:$(sha256sum "$fixture/common/release-evidence/gitlab-source.json" | awk '{print $1}')"
  runner_digest="sha256:$(sha256sum "$fixture/common/release-evidence/runner-source.json" | awk '{print $1}')"
  helper_digest="sha256:$(sha256sum "$fixture/common/release-evidence/runner-helper-source.json" | awk '{print $1}')"
  build_digest="sha256:$(sha256sum "$fixture/common/release-evidence/runner-build-source.json" | awk '{print $1}')"
  nexus_digest="sha256:$(sha256sum "$fixture/common/release-evidence/nexus-source.json" | awk '{print $1}')"
  jar_sha="$(sha256sum "$fixture/common/deploy-system/deploy-ops.jar" | awk '{print $1}')"
  worksystem_sha="$(sha256sum "$fixture/common/worksystem-server/worksystem.jar" | awk '{print $1}')"
  cat > "$fixture/RELEASE-LOCK.env" <<EOF
LOCK_SCHEMA_VERSION=2
LOCK_BOM_RELEASE=$BOM_RELEASE
LOCK_GITLAB_IMAGE=$BOM_GITLAB_IMAGE
LOCK_GITLAB_DIGEST=$gitlab_digest
LOCK_GITLAB_PINNED_REF=$(release_pinned_ref "$BOM_GITLAB_IMAGE" "$gitlab_digest")
LOCK_GITLAB_PLATFORM_DIGEST=$gitlab_digest
LOCK_GITLAB_CONFIG_DIGEST=$gitlab_id
LOCK_GITLAB_ARCHIVE_ID=$gitlab_id
LOCK_RUNNER_IMAGE=$BOM_RUNNER_IMAGE
LOCK_RUNNER_DIGEST=$runner_digest
LOCK_RUNNER_PINNED_REF=$(release_pinned_ref "$BOM_RUNNER_IMAGE" "$runner_digest")
LOCK_RUNNER_PLATFORM_DIGEST=$runner_digest
LOCK_RUNNER_CONFIG_DIGEST=$runner_id
LOCK_RUNNER_ARCHIVE_ID=$runner_id
LOCK_RUNNER_HELPER_IMAGE=$BOM_RUNNER_HELPER_IMAGE
LOCK_RUNNER_HELPER_DIGEST=$helper_digest
LOCK_RUNNER_HELPER_PINNED_REF=$(release_pinned_ref "$BOM_RUNNER_HELPER_IMAGE" "$helper_digest")
LOCK_RUNNER_HELPER_PLATFORM_DIGEST=$helper_digest
LOCK_RUNNER_HELPER_CONFIG_DIGEST=$helper_id
LOCK_RUNNER_HELPER_ARCHIVE_ID=$helper_id
LOCK_RUNNER_BUILD_IMAGE=$BOM_RUNNER_BUILD_IMAGE
LOCK_RUNNER_BUILD_DIGEST=$build_digest
LOCK_RUNNER_BUILD_PINNED_REF=$(release_pinned_ref "$BOM_RUNNER_BUILD_IMAGE" "$build_digest")
LOCK_RUNNER_BUILD_PLATFORM_DIGEST=$build_digest
LOCK_RUNNER_BUILD_CONFIG_DIGEST=$build_id
LOCK_RUNNER_BUILD_ARCHIVE_ID=$build_id
LOCK_NEXUS_IMAGE=$BOM_NEXUS_IMAGE
LOCK_NEXUS_DIGEST=$nexus_digest
LOCK_NEXUS_PINNED_REF=$(release_pinned_ref "$BOM_NEXUS_IMAGE" "$nexus_digest")
LOCK_NEXUS_PLATFORM_DIGEST=$nexus_digest
LOCK_NEXUS_CONFIG_DIGEST=$nexus_id
LOCK_NEXUS_ARCHIVE_ID=$nexus_id
LOCK_DEPLOYOPS_JAR=$BOM_DEPLOYOPS_JAR
LOCK_DEPLOYOPS_VERSION=$BOM_DEPLOYOPS_VERSION
LOCK_DEPLOYOPS_REQUIRED_MIGRATIONS=$BOM_DEPLOYOPS_REQUIRED_MIGRATIONS
LOCK_DEPLOYOPS_SHA256=$jar_sha
LOCK_WORKSYSTEM_JAR=$BOM_WORKSYSTEM_JAR
LOCK_WORKSYSTEM_VERSION=$BOM_WORKSYSTEM_VERSION
LOCK_WORKSYSTEM_SHA256=$worksystem_sha
EOF
}

make_oci_evidence() {
  local name="$1" archive="$2" image="$3" image_id config_digest manifest layer_digest
  image_id="$(image_archive_id_for_tag "$archive" "$image" "$(command -v jq)")"
  config_digest="$image_id"
  layer_digest="sha256:$(printf 'a%.0s' {1..64})"
  manifest="{\"schemaVersion\":2,\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\",\"config\":{\"digest\":\"$config_digest\",\"size\":1},\"layers\":[{\"digest\":\"$layer_digest\",\"size\":1}]}"
  printf '%s' "$manifest" > "$fixture/common/release-evidence/$name-source.json"
  printf '%s' "$manifest" > "$fixture/common/release-evidence/$name-linux-amd64.json"
}

write_manifest() {
  ( cd "$fixture" && find . -type f ! -name MANIFEST.sha256 ! -name MANIFEST.sha256.sig -print0 \
      | sort -z | xargs -0 sha256sum > MANIFEST.sha256 )
}

make_image_archive "$fixture/common/gitlab/gitlab.tar.gz" "$BOM_GITLAB_IMAGE"
make_image_archive "$fixture/common/nexus/nexus.tar.gz" "$BOM_NEXUS_IMAGE"
make_image_archive "$fixture/common/gitlab-runner/gitlab-runner.tar.gz" \
  "$BOM_RUNNER_IMAGE" "$BOM_RUNNER_HELPER_IMAGE" "$BOM_RUNNER_BUILD_IMAGE"
make_oci_evidence gitlab "$fixture/common/gitlab/gitlab.tar.gz" "$BOM_GITLAB_IMAGE"
make_oci_evidence runner "$fixture/common/gitlab-runner/gitlab-runner.tar.gz" "$BOM_RUNNER_IMAGE"
make_oci_evidence runner-helper "$fixture/common/gitlab-runner/gitlab-runner.tar.gz" "$BOM_RUNNER_HELPER_IMAGE"
make_oci_evidence runner-build "$fixture/common/gitlab-runner/gitlab-runner.tar.gz" "$BOM_RUNNER_BUILD_IMAGE"
make_oci_evidence nexus "$fixture/common/nexus/nexus.tar.gz" "$BOM_NEXUS_IMAGE"
write_lock
write_manifest
BUNDLE_ROOT="$fixture" "$SCRIPT_DIR/verify-offline-bundle.sh" --allow-unsigned >/dev/null

# Host-local tag drift is repairable by docker load and must not invalidate the read-only bundle itself.
cat > "$fakebin/docker-local-drift" <<'SH'
#!/usr/bin/env bash
if [[ "${1:-}" == image && "${2:-}" == inspect ]]; then
  printf 'sha256:%064d\n' 9
  exit 0
fi
exit 1
SH
chmod +x "$fakebin/docker-local-drift"
ln -sf "$fakebin/docker-local-drift" "$fakebin/docker"
PATH="$fakebin:$PATH" BUNDLE_ROOT="$fixture" "$SCRIPT_DIR/verify-offline-bundle.sh" --allow-unsigned >/dev/null

# Ubuntu-only media is a supported bundle shape; the verifier must not require rocky8 unconditionally.
rm -rf "$fixture/rocky8"
write_fixture_docker_packages "$fixture/ubuntu/docker" deb \
  docker.io containerd docker-buildx docker-compose-v2
write_fixture_docker_packages "$fixture/ubuntu/mariadb" deb mariadb-server
write_fixture_docker_packages "$fixture/ubuntu/nginx" deb nginx
write_manifest
BUNDLE_OS=ubuntu BUNDLE_ROOT="$fixture" "$SCRIPT_DIR/verify-offline-bundle.sh" --allow-unsigned >/dev/null
rm -rf "$fixture/ubuntu"
write_fixture_docker_packages "$fixture/rocky8/target-prerequisites" rpm \
  openssh-server openssh-clients sudo policycoreutils policycoreutils-python-utils \
  iproute procps-ng shadow-utils util-linux tar gzip curl
write_fixture_docker_packages "$fixture/rocky8/mariadb" rpm mariadb-server mariadb mariadb-common
write_fixture_docker_packages "$fixture/rocky8/nginx" rpm nginx
write_fixture_docker_packages "$fixture/rocky8/docker" rpm \
  docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
write_manifest

cp "$fixture/rocky8/docker/PACKAGES.lock" "$tmpdir/PACKAGES.lock.duplicate"
cat "$tmpdir/PACKAGES.lock.duplicate" >> "$fixture/rocky8/docker/PACKAGES.lock"
write_manifest
if BUNDLE_OS=rocky8 BUNDLE_ROOT="$fixture" "$SCRIPT_DIR/verify-offline-bundle.sh" --allow-unsigned >/dev/null 2>&1; then
  echo "verifier accepted duplicate package identities in PACKAGES.lock" >&2
  exit 1
fi
write_fixture_docker_packages "$fixture/rocky8/docker" rpm \
  docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
write_manifest

cp "$fixture/rocky8/docker/PACKAGES.lock" "$tmpdir/PACKAGES.lock.metadata-safe"
sed -i '1s/|0:1\.0-1|/|0:9.9-1|/' "$fixture/rocky8/docker/PACKAGES.lock"
write_manifest
if BUNDLE_OS=rocky8 BUNDLE_ROOT="$fixture" "$SCRIPT_DIR/verify-offline-bundle.sh" --allow-unsigned >/dev/null 2>&1; then
  echo "verifier accepted package metadata that differs from the payload" >&2
  exit 1
fi
cp "$tmpdir/PACKAGES.lock.metadata-safe" "$fixture/rocky8/docker/PACKAGES.lock"
write_manifest

# The docker-save layer bytes must be bound to config.rootfs.diff_ids, not only outer manifest/config ID.
cp "$fixture/common/gitlab/gitlab.tar.gz" "$tmpdir/gitlab-layer-safe.tar.gz"
layer_extract="$tmpdir/layer-tamper"
mkdir -p "$layer_extract"
tar -xzf "$fixture/common/gitlab/gitlab.tar.gz" -C "$layer_extract"
layer_path="$(jq -er '.[0].Layers[0]' "$layer_extract/manifest.json")"
printf 'tamper' >> "$layer_extract/$layer_path"
config_path="$(jq -er '.[0].Config' "$layer_extract/manifest.json")"
tar -czf "$fixture/common/gitlab/gitlab.tar.gz" -C "$layer_extract" manifest.json "$config_path" "$layer_path"
write_manifest
if BUNDLE_ROOT="$fixture" "$SCRIPT_DIR/verify-offline-bundle.sh" --allow-unsigned >/dev/null 2>&1; then
  echo "verifier accepted Docker layer bytes that differ from config rootfs.diff_ids" >&2
  exit 1
fi
cp "$tmpdir/gitlab-layer-safe.tar.gz" "$fixture/common/gitlab/gitlab.tar.gz"
write_manifest

printf 'tamper\n' >> "$fixture/rocky8/docker/docker-ce-1.0.x86_64.rpm"
write_manifest
if BUNDLE_OS=rocky8 BUNDLE_ROOT="$fixture" "$SCRIPT_DIR/verify-offline-bundle.sh" --allow-unsigned >/dev/null 2>&1; then
  echo "verifier accepted a Docker package that differs from PACKAGES.lock" >&2
  exit 1
fi
write_fixture_docker_packages "$fixture/rocky8/docker" rpm \
  docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
write_manifest

# The lock must bind the source digest evidence to the exact config object inside docker-save.
cp "$fixture/RELEASE-LOCK.env" "$tmpdir/RELEASE-LOCK.archive-safe"
sed -i "s/^LOCK_GITLAB_ARCHIVE_ID=.*/LOCK_GITLAB_ARCHIVE_ID=sha256:$(printf 'f%.0s' {1..64})/" \
  "$fixture/RELEASE-LOCK.env"
write_manifest
if BUNDLE_ROOT="$fixture" "$SCRIPT_DIR/verify-offline-bundle.sh" --allow-unsigned >/dev/null 2>&1; then
  echo "verifier accepted an archive image ID that differs from the release lock" >&2
  exit 1
fi
cp "$tmpdir/RELEASE-LOCK.archive-safe" "$fixture/RELEASE-LOCK.env"
write_manifest

# Required DB lineage cannot be omitted even when JAR version and freshly locked SHA match.
cp "$fixture/common/deploy-system/deploy-ops.jar" "$tmpdir/deploy-ops.safe.jar"
for missing_migration in V21__container_running_health_mode.sql V22__merge_request_reviewer_gitlab_identity.sql V26__audit_target_description_capacity.sql V32__nginx_static_api_proxy.sql V33__durable_target_deployment_attempt.sql; do
"$PYTHON_BIN" - "$tmpdir/deploy-ops.safe.jar" "$fixture/common/deploy-system/deploy-ops.jar" "$missing_migration" <<'PY'
import sys, zipfile
missing_entry = f"BOOT-INF/classes/db/migration/{sys.argv[3]}"
with zipfile.ZipFile(sys.argv[1]) as source, zipfile.ZipFile(sys.argv[2], "w") as jar:
    for entry in source.infolist():
        if entry.filename != missing_entry:
            jar.writestr(entry, source.read(entry.filename))
    # A backup filename must not satisfy the exact Flyway resource path.
    jar.writestr(missing_entry + ".bak", "-- not an executable migration")
PY
write_lock
write_manifest
if BUNDLE_ROOT="$fixture" "$SCRIPT_DIR/verify-offline-bundle.sh" --allow-unsigned > "$tmpdir/missing-migration.log" 2>&1; then
  echo "verifier accepted DeployOps JAR without required migration: $missing_migration" >&2
  exit 1
fi
grep -F "DeployOps JAR does not contain required migration: $missing_migration" "$tmpdir/missing-migration.log" >/dev/null
done
cp "$tmpdir/deploy-ops.safe.jar" "$fixture/common/deploy-system/deploy-ops.jar"
write_lock
write_manifest

# Worksystem is a mandatory release artifact with its own version/SHA lock.
cp "$fixture/common/worksystem-server/worksystem.jar" "$tmpdir/worksystem.safe.jar"
printf 'stale-worksystem\n' >> "$fixture/common/worksystem-server/worksystem.jar"
write_manifest
if BUNDLE_ROOT="$fixture" "$SCRIPT_DIR/verify-offline-bundle.sh" --allow-unsigned >/dev/null 2>&1; then
  echo "verifier accepted Worksystem JAR content that differs from the release lock" >&2
  exit 1
fi
cp "$tmpdir/worksystem.safe.jar" "$fixture/common/worksystem-server/worksystem.jar"
write_lock
write_manifest

# Detached signature requires a public key pinned outside the transported bundle.
command -v openssl >/dev/null 2>&1 || { echo "openssl is required for signature tests" >&2; exit 1; }
{
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$tmpdir/signing-private.pem" >/dev/null 2>&1
  openssl pkey -in "$tmpdir/signing-private.pem" -pubout -out "$tmpdir/trusted-public.pem" >/dev/null 2>&1
  cp "$tmpdir/trusted-public.pem" "$fixture/MANIFEST-PUBLIC-KEY.pem"
  write_manifest
  openssl dgst -sha256 -sign "$tmpdir/signing-private.pem" \
    -out "$fixture/MANIFEST.sha256.sig" "$fixture/MANIFEST.sha256"
  if BUNDLE_ROOT="$fixture" "$SCRIPT_DIR/verify-offline-bundle.sh" --require-signature >/dev/null 2>&1; then
    echo "verifier trusted the public key that traveled inside the bundle" >&2
    exit 1
  fi
  if BUNDLE_ROOT="$fixture" "$SCRIPT_DIR/verify-offline-bundle.sh" \
      --trusted-public-key "$fixture/MANIFEST-PUBLIC-KEY.pem" >/dev/null 2>&1; then
    echo "verifier accepted a bundled path as its trusted public key" >&2
    exit 1
  fi
  ln -s "$tmpdir/trusted-public.pem" "$fixture/external-key-link.pem"
  if BUNDLE_ROOT="$fixture" "$SCRIPT_DIR/verify-offline-bundle.sh" \
      --trusted-public-key "$fixture/external-key-link.pem" >/dev/null 2>&1; then
    echo "verifier accepted an in-bundle symlink as its trusted public-key path" >&2
    exit 1
  fi
  rm -f "$fixture/external-key-link.pem"
  BUNDLE_ROOT="$fixture" "$SCRIPT_DIR/verify-offline-bundle.sh" \
    --trusted-public-key "$tmpdir/trusted-public.pem" >/dev/null
  signature_byte_before="$(od -An -N1 -tu1 "$fixture/MANIFEST.sha256.sig" | tr -d '[:space:]')"
  signature_byte_after=$((signature_byte_before ^ 1))
  printf '%b' "\\$(printf '%03o' "$signature_byte_after")" > "$tmpdir/tampered-signature-byte"
  dd if="$tmpdir/tampered-signature-byte" of="$fixture/MANIFEST.sha256.sig" bs=1 seek=0 count=1 conv=notrunc status=none
  signature_byte_observed="$(od -An -N1 -tu1 "$fixture/MANIFEST.sha256.sig" | tr -d '[:space:]')"
  [[ "$signature_byte_observed" == "$signature_byte_after" && "$signature_byte_observed" != "$signature_byte_before" ]] || {
    echo "signature tamper fixture did not change its first byte" >&2
    exit 1
  }
  printf 'PASS: signature tamper fixture changed first byte %s -> %s\n' "$signature_byte_before" "$signature_byte_observed"
  real_jq="$(command -v jq)"
  mkdir -p "$tmpdir/early-gate-bin"
  cat > "$tmpdir/early-gate-bin/jq" <<EOF
#!/usr/bin/env bash
touch "$tmpdir/untrusted-jq-executed"
exec "$real_jq" "\$@"
EOF
  chmod +x "$tmpdir/early-gate-bin/jq"
  if PATH="$tmpdir/early-gate-bin:$PATH" BUNDLE_ROOT="$fixture" "$SCRIPT_DIR/verify-offline-bundle.sh" \
      --trusted-public-key "$tmpdir/trusted-public.pem" >/dev/null 2>&1; then
    echo "verifier accepted a tampered detached signature" >&2
    exit 1
  fi
  [[ ! -e "$tmpdir/untrusted-jq-executed" ]] || {
    echo "verifier executed jq before rejecting an invalid signature" >&2
    exit 1
  }
  rm -f "$fixture/MANIFEST.sha256.sig" "$fixture/MANIFEST-PUBLIC-KEY.pem"
  write_manifest
}
BUNDLE_ROOT="$fixture" "$SCRIPT_DIR/verify-offline-bundle.sh" >/dev/null
if BUNDLE_ROOT="$fixture" "$SCRIPT_DIR/verify-offline-bundle.sh" --allow-unsigned \
    --require-signature --skip-manifest >/dev/null 2>&1; then
  echo "signature enforcement was bypassed with --skip-manifest" >&2
  exit 1
fi

# Git for Windows writes sha256sum manifests with the canonical binary marker
# (` *path`) instead of the Unix text marker (`  path`). Both must verify.
sed -E 's/^([0-9a-f]{64}) [ *]/\1 */' "$fixture/MANIFEST.sha256" \
  > "$fixture/MANIFEST.sha256.binary"
mv "$fixture/MANIFEST.sha256.binary" "$fixture/MANIFEST.sha256"
BUNDLE_ROOT="$fixture" "$SCRIPT_DIR/verify-offline-bundle.sh" --allow-unsigned >/dev/null
if BUNDLE_ROOT="$fixture" "$SCRIPT_DIR/verify-offline-bundle.sh" --allow-unsigned --require-java-ci >"$tmpdir/require-java-ci.log" 2>&1; then
  echo 'verifier accepted missing Java CI payload for required trial gate' >&2; exit 1
fi
grep -q 'Required Java CI payload is missing' "$tmpdir/require-java-ci.log"
write_manifest

# Bundle metadata is parsed as data; shell syntax must neither run nor pass.
cp "$fixture/RELEASE-BOM.env" "$tmpdir/RELEASE-BOM.safe"
marker="$tmpdir/metadata-code-executed"
printf 'BOM_EVIL=$(touch %s)\n' "$marker" >> "$fixture/RELEASE-BOM.env"
write_manifest
if BUNDLE_ROOT="$fixture" "$SCRIPT_DIR/verify-offline-bundle.sh" --allow-unsigned >/dev/null 2>&1; then
  echo "verifier accepted executable syntax in release metadata" >&2
  exit 1
fi
[[ ! -e "$marker" ]] || { echo "release metadata executed shell code" >&2; exit 1; }
cp "$tmpdir/RELEASE-BOM.safe" "$fixture/RELEASE-BOM.env"
write_manifest

cp "$fixture/RELEASE-LOCK.env" "$tmpdir/RELEASE-LOCK.safe"
printf 'LOCK_EVIL=$(touch %s)\n' "$marker" >> "$fixture/RELEASE-LOCK.env"
write_manifest
if BUNDLE_ROOT="$fixture" "$SCRIPT_DIR/verify-offline-bundle.sh" --allow-unsigned >/dev/null 2>&1; then
  echo "verifier accepted executable syntax in release lock" >&2
  exit 1
fi
[[ ! -e "$marker" ]] || { echo "release lock executed shell code" >&2; exit 1; }
cp "$tmpdir/RELEASE-LOCK.safe" "$fixture/RELEASE-LOCK.env"
write_manifest

# Every regular file must be covered by the outer manifest; unlisted payload is rejected.
printf 'unlisted\n' > "$fixture/common/unlisted.payload"
if BUNDLE_ROOT="$fixture" "$SCRIPT_DIR/verify-offline-bundle.sh" --allow-unsigned >/dev/null 2>&1; then
  echo "verifier accepted an unlisted bundle payload" >&2
  exit 1
fi
rm -f "$fixture/common/unlisted.payload"
write_manifest

cat > "$tmpdir/runner.env" <<EOF
GITLAB_RUNNER_REGISTER=y
GITLAB_RUNNER_URL=http://gitlab:8929
GITLAB_RUNNER_TOKEN=glrt-existing-token
GITLAB_RUNNER_ID=17
GITLAB_RUNNER_VERIFY_API_TOKEN=glpat-verify-runner-fixture
GITLAB_RUNNER_TYPE=project_type
GITLAB_RUNNER_TAG_LIST=docker,offline
GITLAB_RUNNER_RUN_UNTAGGED=n
GITLAB_RUNNER_PROTECTED=y
GITLAB_RUNNER_LOCKED=y
EOF
BUNDLE_ROOT="$fixture" "$SCRIPT_DIR/install.sh" --validate --bundle-os rocky8 \
  --answers "$tmpdir/runner.env" --components gitlab-runner >/dev/null
sed '/GITLAB_RUNNER_URL=/a GITLAB_RUNNER_CLONE_URL=http://host.docker.internal:8929' \
  "$tmpdir/runner.env" > "$tmpdir/runner-clone.env"
BUNDLE_ROOT="$fixture" "$SCRIPT_DIR/install.sh" --validate --bundle-os rocky8 \
  --answers "$tmpdir/runner-clone.env" --components gitlab-runner >/dev/null
sed '/GITLAB_RUNNER_URL=/a GITLAB_RUNNER_DOCKER_IMAGE=docker:latest' \
  "$tmpdir/runner.env" > "$tmpdir/runner-custom-image.env"
if BUNDLE_ROOT="$fixture" "$SCRIPT_DIR/install.sh" --validate --bundle-os rocky8 \
    --answers "$tmpdir/runner-custom-image.env" --components gitlab-runner >/dev/null 2>&1; then
  echo "install plan accepted custom, unverified Runner job image" >&2
  exit 1
fi
sed 's#GITLAB_RUNNER_CLONE_URL=.*#GITLAB_RUNNER_CLONE_URL=http://user:password@gitlab:8929#' \
  "$tmpdir/runner-clone.env" > "$tmpdir/runner-clone-invalid.env"
if BUNDLE_ROOT="$fixture" "$SCRIPT_DIR/install.sh" --validate --bundle-os rocky8 \
    --answers "$tmpdir/runner-clone-invalid.env" --components gitlab-runner >/dev/null 2>&1; then
  echo "install plan accepted credential-bearing Runner clone URL" >&2
  exit 1
fi
sed 's/GITLAB_RUNNER_LOCKED=y/GITLAB_RUNNER_LOCKED=invalid/' "$tmpdir/runner.env" > "$tmpdir/runner-invalid.env"
if BUNDLE_ROOT="$fixture" "$SCRIPT_DIR/install.sh" --validate --bundle-os rocky8 \
    --answers "$tmpdir/runner-invalid.env" --components gitlab-runner >/dev/null 2>&1; then
  echo "install plan accepted an invalid Runner lock policy" >&2
  exit 1
fi

cat > "$tmpdir/runner-api.env" <<EOF
GITLAB_RUNNER_REGISTER=y
GITLAB_RUNNER_URL=http://gitlab:8929
GITLAB_RUNNER_API_TOKEN=glpat-create-runner-fixture
GITLAB_RUNNER_TYPE=project_type
GITLAB_RUNNER_PROJECT_ID=42
GITLAB_RUNNER_TAG_LIST=docker,offline
GITLAB_RUNNER_RUN_UNTAGGED=n
GITLAB_RUNNER_PROTECTED=y
GITLAB_RUNNER_LOCKED=y
EOF
BUNDLE_ROOT="$fixture" "$SCRIPT_DIR/install.sh" --validate --bundle-os rocky8 \
  --answers "$tmpdir/runner-api.env" --components gitlab-runner >/dev/null

cat > "$tmpdir/runner-glrt-unconfirmed.env" <<EOF
GITLAB_RUNNER_REGISTER=y
GITLAB_RUNNER_URL=http://gitlab:8929
GITLAB_RUNNER_TOKEN=glrt-existing-token
GITLAB_RUNNER_TAG_LIST=docker,offline
GITLAB_RUNNER_RUN_UNTAGGED=n
GITLAB_RUNNER_PROTECTED=y
GITLAB_RUNNER_LOCKED=y
EOF
if BUNDLE_ROOT="$fixture" "$SCRIPT_DIR/install.sh" --validate --bundle-os rocky8 \
    --answers "$tmpdir/runner-glrt-unconfirmed.env" --components gitlab-runner >/dev/null 2>&1; then
  echo "install plan accepted glrt token without Runner ID and verification PAT" >&2
  exit 1
fi

# A valid outer SHA must not hide a missing required image inside the Runner archive.
make_image_archive "$fixture/common/gitlab-runner/gitlab-runner.tar.gz" \
  "$BOM_RUNNER_IMAGE" "$BOM_RUNNER_HELPER_IMAGE"
write_manifest
if BUNDLE_ROOT="$fixture" "$SCRIPT_DIR/verify-offline-bundle.sh" --allow-unsigned >/dev/null 2>&1; then
  echo "verifier accepted Runner archive without the build image" >&2
  exit 1
fi
make_image_archive "$fixture/common/gitlab-runner/gitlab-runner.tar.gz" \
  "$BOM_RUNNER_IMAGE" "$BOM_RUNNER_HELPER_IMAGE" "$BOM_RUNNER_BUILD_IMAGE"
write_manifest

# Writable staging is still rejected before host mutation.
trusted_installer="$tmpdir/trusted-installer"
mkdir -p "$trusted_installer"
cp -a "$SCRIPT_DIR/install.sh" "$SCRIPT_DIR/verify-offline-bundle.sh" "$SCRIPT_DIR/"*.py \
  "$trusted_installer/"
cp -a "$SCRIPT_DIR/lib" "$SCRIPT_DIR/components" "$SCRIPT_DIR/deploy-agent" \
  "$trusted_installer/"
chmod -R go-w "$trusted_installer"
mkdir -p "$tmpdir/no-key-bin"
for command_name in dnf yum apt-get dpkg docker systemctl useradd groupadd; do
  cat > "$tmpdir/no-key-bin/$command_name" <<EOF
#!/usr/bin/env bash
echo "$command_name" >> "$tmpdir/host-mutations.log"
exit 0
EOF
  chmod +x "$tmpdir/no-key-bin/$command_name"
done
chmod 777 "$fixture"
if PATH="$tmpdir/no-key-bin:$PATH" BUNDLE_ROOT="$fixture" \
    "$trusted_installer/install.sh" -u --answers "$tmpdir/runner.env" \
    --components gitlab-runner >"$tmpdir/writable-tree-install.log" 2>&1; then
  chmod 755 "$fixture"
  echo "installer accepted a group/world-writable release bundle tree" >&2
  exit 1
fi
chmod 755 "$fixture"
grep -q 'must not be group/world writable' "$tmpdir/writable-tree-install.log" || {
  cat "$tmpdir/writable-tree-install.log" >&2
  echo "installer did not reject a writable release staging path" >&2
  exit 1
}
[[ ! -e "$tmpdir/host-mutations.log" ]] || {
  echo "installer mutated the host before rejecting a writable release path" >&2
  cat "$tmpdir/host-mutations.log" >&2
  exit 1
}
# Exercise normal install.sh up to its actual verifier call; the spy exits before
# any host operation and records that no key/signature flags were requested.
cat > "$trusted_installer/verify-offline-bundle.sh" <<'VERIFY_SPY'
#!/usr/bin/env bash
[[ "$OFFLINE_VERIFY_AUTHENTICITY" == 0 && "$#" == 0 ]] || exit 92
printf 'UNSIGNED_DEFAULT_VERIFIER_REACHED\n'
exit 77
VERIFY_SPY
chmod 755 "$trusted_installer/verify-offline-bundle.sh"
if env -u OFFLINE_MANIFEST_PUBLIC_KEY OFFLINE_REQUIRE_SIGNATURE=n \
    PATH="$tmpdir/no-key-bin:$PATH" BUNDLE_ROOT="$fixture" \
    "$trusted_installer/install.sh" -u --answers "$tmpdir/runner.env" \
    --components gitlab-runner >"$tmpdir/no-key-install.log" 2>&1; then
  echo "installer ignored the verifier failure" >&2
  exit 1
fi
grep -q 'UNSIGNED_DEFAULT_VERIFIER_REACHED' "$tmpdir/no-key-install.log"
[[ ! -e "$tmpdir/host-mutations.log" ]]

[[ ! -e "$tmpdir/host-mutations.log" ]] || {
  echo "installer mutated the host before rejecting a missing trusted key" >&2
  cat "$tmpdir/host-mutations.log" >&2
  exit 1
}

rm -f "$fixture/MANIFEST.sha256"
if BUNDLE_ROOT="$fixture" "$SCRIPT_DIR/verify-offline-bundle.sh" --allow-unsigned >/dev/null 2>&1; then
  echo "verifier accepted a bundle without MANIFEST.sha256" >&2
  exit 1
fi
if BUNDLE_ROOT="$fixture" "$SCRIPT_DIR/install.sh" -u --answers "$tmpdir/runner.env" \
    --components gitlab-runner >/dev/null 2>&1; then
  echo "installer entered mutation path without mandatory bundle preflight" >&2
  exit 1
fi

# shellcheck disable=SC1091
. "$SCRIPT_DIR/components/docker.sh"
existing="$tmpdir/daemon.json"
merged="$tmpdir/daemon-merged.json"
printf '%s\n' '{"log-driver":"journald","data-root":"/srv/docker","insecure-registries":["old:5000"]}' > "$existing"
docker_merge_daemon_json "$(command -v jq)" "$existing" "$merged" "new:5000"
jq -e '."log-driver" == "journald" and ."data-root" == "/srv/docker"' "$merged" >/dev/null
jq -e '(."insecure-registries" | sort) == ["new:5000","old:5000"]' "$merged" >/dev/null

# shellcheck disable=SC1091
. "$SCRIPT_DIR/lib/ui.sh"
# shellcheck disable=SC1091
. "$SCRIPT_DIR/components/gitlab-runner.sh"
api_args=()
build_runner_create_api_args api_args project_type 42 "" offline-runner \
  docker,offline false true ref_protected
api_joined=" ${api_args[*]} "
for expected in 'runner_type=project_type' 'project_id=42' 'tag_list=docker,offline' \
  'run_untagged=false' 'locked=true' 'access_level=ref_protected'; do
  [[ "$api_joined" == *" $expected "* ]] || { echo "runner create API option missing: $expected" >&2; exit 1; }
done

glrt_args=()
build_runner_registration_args glrt_args "http://gitlab:8929" "glrt-fixture" "offline-runner" \
  "$BOM_RUNNER_BUILD_IMAGE" "" "docker,offline" false true true /etc/gitlab-runner/template.toml
glrt_joined=" ${glrt_args[*]} "
[[ "$glrt_joined" != *glrt-fixture* && "$glrt_joined" != *' --token '* ]] || { echo "glrt token leaked in argv" >&2; exit 1; }
for expected in "--docker-helper-image $BOM_RUNNER_HELPER_IMAGE" '--docker-pull-policy never' \
    '--template-config /etc/gitlab-runner/template.toml'; do
  [[ "$glrt_joined" == *" $expected "* ]] || { echo "offline runner option missing: $expected" >&2; exit 1; }
done
[[ "$glrt_joined" != *'--clone-url'* ]] || { echo "unsupported direct clone-url option used" >&2; exit 1; }
write_runner_registration_template "$tmpdir/runner-template-default.toml" ""
grep -Fq 'pull_policy = ["never"]' "$tmpdir/runner-template-default.toml"
grep -Fq 'allowed_pull_policies = ["never"]' "$tmpdir/runner-template-default.toml"
! grep -q '^  clone_url =' "$tmpdir/runner-template-default.toml"
write_runner_registration_template "$tmpdir/runner-template-clone.toml" "http://host.docker.internal:8929"
grep -Fq '  clone_url = "http://host.docker.internal:8929"' "$tmpdir/runner-template-clone.toml"
if write_runner_registration_template "$tmpdir/runner-template-invalid.toml" 'http://user:pass@host:8929'; then
  echo "Runner template accepted credentials in clone_url" >&2; exit 1
fi
for forbidden in '--tag-list' '--run-untagged' '--locked' '--access-level' '--registration-token'; do
  [[ "$glrt_joined" != *" $forbidden"* ]] || { echo "reserved option passed with glrt token: $forbidden" >&2; exit 1; }
done

bash "$SCRIPT_DIR/tests/runner-registration-caller-regression.sh"
echo "installer P0 regression checks passed"
