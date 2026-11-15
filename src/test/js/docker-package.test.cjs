const assert = require('node:assert/strict');
const test = require('node:test');
const { readFileSync, existsSync } = require('node:fs');
const { resolve, join } = require('node:path');
const root = resolve(__dirname, '../../..');

test('public image retains its non-root upload boundary and required maintenance sources', () => {
  const dockerfile = readFileSync(join(root, 'Dockerfile'), 'utf8');
  assert.match(dockerfile, /USER deployops/);
  assert.match(dockerfile, /install -d -o deployops -g deployops -m 0700/);
  assert.match(dockerfile, /ENV NGINX_UPLOAD_DIR="\/home\/deployops\/\.deployops\/nginx-uploads"/);
  assert.match(dockerfile, /COPY installer \.\/installer/);
  const manifest = JSON.parse(readFileSync(join(root, 'installer/deploy-system/maintenance-payload.json'), 'utf8'));
  for (const entry of manifest.files) {
    assert.ok(existsSync(join(root, 'installer', entry.source)), 'maintenance source must be included: ' + entry.source);
  }
  for (const name of ['.env', '.env.example', 'docker-compose.yml', 'src/main/resources/application.yml']) {
    assert.equal(existsSync(join(root, name)), false, 'operator configuration must remain external');
  }
});
