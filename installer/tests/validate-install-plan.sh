#!/usr/bin/env bash
# Regression checks for the install-plan validator. No package or service changes.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

key_file="$tmpdir/deploy-ops.pub"
printf 'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIE2zW4h2aLQ3q1Zhgx2V3ZgRkAi4Y4j3KbVQn7p9a1b deployops\n' > "$key_file"

cat > "$tmpdir/was.env" <<EOF
DOCKER_SET_INSECURE_REGISTRY=n
DEPLOY_AGENT_TYPE=was
DEPLOY_AGENT_USER=deploy
DEPLOY_AGENT_AUTHORIZED_KEY_FILE=$key_file
DEPLOY_AGENT_HEALTH_PORT=8080
DEPLOY_AGENT_HEALTH_PATH=/actuator/health
EOF

"$SCRIPT_DIR/install.sh" --validate --bundle-os rocky8 --answers "$tmpdir/was.env" --components docker,deploy-agent

cat > "$tmpdir/web.env" <<EOF
NGINX_UPSTREAM_HOST=198.18.11.100
NGINX_STATIC_LISTEN=80
DEPLOY_AGENT_TYPE=web
DEPLOY_AGENT_USER=deploy
DEPLOY_AGENT_AUTHORIZED_KEY_FILE=$key_file
DEPLOY_AGENT_HEALTH_PORT=80
DEPLOY_AGENT_HEALTH_PATH=/
EOF
"$SCRIPT_DIR/install.sh" --validate --bundle-os rocky8 --answers "$tmpdir/web.env" --components nginx,deploy-agent

cat > "$tmpdir/office.env" <<'EOF'
WORKSYSTEM_DB_PASSWORD=worksystem-db-password
WORKSYSTEM_ADMIN_PASSWORD=worksystem-admin-password
SSO_SHARED_SECRET=0123456789abcdef0123456789abcdef
ANONYMITY_SECRET=abcdef0123456789abcdef0123456789
EOF
"$SCRIPT_DIR/install.sh" --validate --bundle-os rocky8 --answers "$tmpdir/office.env" --components worksystem-server

cat > "$tmpdir/bad-was.env" <<EOF
DEPLOY_AGENT_TYPE=web
DEPLOY_AGENT_AUTHORIZED_KEY_FILE=$key_file
DEPLOY_AGENT_HEALTH_PORT=8080
EOF
if "$SCRIPT_DIR/install.sh" --validate --bundle-os rocky8 --answers "$tmpdir/bad-was.env" --components docker,deploy-agent; then
  echo "expected WEB agent on Docker WAS plan to fail" >&2
  exit 1
fi

cat > "$tmpdir/bad-deploy.env" <<'EOF'
DEPLOYOPS_DB_PASSWORD=change-me-deployops-db
DEPLOYOPS_ADMIN_PASSWORD=change-me-admin
EOF
if "$SCRIPT_DIR/install.sh" --validate --bundle-os rocky8 --answers "$tmpdir/bad-deploy.env" --components deploy-system; then
  echo "expected placeholder secret to fail" >&2
  exit 1
fi

echo "install-plan validation regression checks passed"
