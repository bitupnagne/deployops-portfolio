"""Promote one verified restore clone. Preserve the old stopped canonical for rollback.

Runs only under the existing root broker lock. IDs are the only client-selected
input; names, mounts, keys and published ports come from root restore evidence
and the exact existing canonical container. No delete, overwrite or auto replay.
"""
import datetime
import json
import os
import re
import stat
from pathlib import Path
from types import SimpleNamespace


def require(ok):
    if not ok:
        raise ValueError('PROMOTION_PREFLIGHT_REJECTED')


def result(job, status, canonical=None, rollback=None, error=None):
    return dict(schema=1, requestId=job['requestId'], restoreRequestId=job['restoreRequestId'],
        status=status, canonicalContainerId=canonical, rollbackContainerId=rollback,
        errorCode=error, completedAt=None if status == 'RUNNING' else
        datetime.datetime.now(datetime.timezone.utc).isoformat())


def validate_job(value, ident):
    require(type(value) is dict and set(value) == {'schema', 'requestId', 'restoreRequestId', 'action'})
    require(type(value['schema']) is int and value['schema'] == 1 and value['action'] == 'PROMOTE')
    require(type(value['requestId']) is int and value['requestId'] == ident and ident > 0)
    require(type(value['restoreRequestId']) is int and 0 < value['restoreRequestId'] <= 9223372036854775807)
    return value


def persistent_key(parent, ident, raw):
    """Copy a restored key, retaining required Unix modes under broker umask 077."""
    if not parent.exists():
        parent.mkdir(mode=0o750)
        os.chown(str(parent), 0, 200)
        os.chmod(str(parent), 0o750)
    require(not parent.is_symlink() and parent.stat().st_uid == 0
        and parent.stat().st_gid == 200 and stat.S_IMODE(parent.stat().st_mode) == 0o750)
    keydir = parent / ('restored-%s' % ident)
    require(not os.path.lexists(str(keydir)))
    keydir.mkdir(mode=0o750)
    os.chown(str(keydir), 0, 200)
    os.chmod(str(keydir), 0o750)
    key = keydir / 'secrets.json'
    fd = os.open(str(key), os.O_CREAT | os.O_EXCL | os.O_WRONLY | os.O_NOFOLLOW, 0o440)
    with os.fdopen(fd, 'wb') as handle:
        handle.write(raw)
        handle.flush()
        os.fsync(handle.fileno())
        os.fchown(handle.fileno(), 0, 200)
        os.fchmod(handle.fileno(), 0o440)
    require(key.read_bytes() == raw)
    return key


def retain_rollback(library, cid, name):
    """A stopped always-restart rollback must stay stopped after daemon restart."""
    stopped = library.inspect(cid)['State']
    require(not stopped['Running'] and stopped['ExitCode'] in (0, 143)
        and not stopped['OOMKilled'] and not stopped['Error'])
    library.call(['update', '--restart=no', cid])
    require(library.inspect(cid)['HostConfig']['RestartPolicy']['Name'] == 'no')
    library.call(['rename', cid, name])


def prepare(broker, store, config, library, job):
    require(config['externalWritersManaged'] is True and config['allStorageInDataVolume'] is True)
    rid = job['restoreRequestId']
    raw = broker.read_file(store.base / 'results' / ('restore-%s.json' % rid), 0, 0o640, group=store.app_gid)
    restored = broker.parse_unique(raw)
    require(restored['schema'] == 1 and restored['requestId'] == rid and restored['status'] == 'HEALTH_READY'
        and restored['originalProjectionPreserved'] is True and restored['mutationUncertain'] is False
        and restored['cloneName'] == 'nexus-restore-%s' % rid
        and restored['cloneVolume'] == 'nexus-restore-data-%s' % rid
        and restored['cloneNetwork'] == 'nexus-restore-net-%s' % rid
        and re.fullmatch('[0-9a-f]{64}', restored['cloneContainerId'])
        and re.fullmatch('[0-9a-f]{64}', restored['manifestSha256']))
    backup = store.get_result(restored['backupRequestId'])
    require(backup['status'] == 'COMPLETED' and backup['manifestSha256'] == restored['manifestSha256'])
    artifact, meta = library.verify_artifact(str(Path(config['backupRoot']) / backup['artifactId']))
    require(library.digest(artifact / 'manifest.json') == restored['manifestSha256'])
    require(meta['imageId'] == config['imageId'] and restored['cloneImageId'] == config['imageId'])
    clone = library.inspect(restored['cloneContainerId'])
    key = store.base / 'state' / ('restore-%s-work' % rid) / 'keys' / 'secrets.json'
    args = SimpleNamespace(new_name=restored['cloneName'], new_volume=restored['cloneVolume'])
    require(clone['State']['Running'] is True and library.restore_identity(clone, args, meta, key)
        and clone['HostConfig']['RestartPolicy']['Name'] == 'no')
    library.validate_key(key)
    library.validate_binding(library.volume_path(restored['cloneVolume']), key)
    library.wait_health(clone['Id'])
    original = library.inspect(config['containerName'])
    require(original['Id'] != clone['Id'] and original['State']['Running'] is True)
    source = SimpleNamespace(container_id=original['Id'], container_name=config['containerName'],
        image_id=config['imageId'], volume=config['volume'], key_file=config['keyFile'],
        heap=config['heap'], all_storage_in_data_volume=True)
    library.source_contract(source)
    require(original['HostConfig']['NetworkMode'] == 'bridge'
        and list(original['NetworkSettings']['Networks']) == ['bridge']
        and not original['HostConfig'].get('Privileged')
        and not original['HostConfig'].get('AutoRemove'))
    bindings = original['HostConfig']['PortBindings']
    require(set(bindings) == set(str(p) + '/tcp' for p in meta['containerPorts']))
    for port, items in bindings.items():
        require(len(items) == 1 and items[0]['HostIp'] in ('', '0.0.0.0', '127.0.0.1')
            and re.fullmatch('[1-9][0-9]{0,4}', items[0]['HostPort'])
            and int(items[0]['HostPort']) <= 65535)
    rollback = config['containerName'] + '-rollback-' + str(job['requestId'])
    require(library.call(['ps', '-aq', '--filter', 'name=^/' + rollback + '$']) == '')
    # No second writer may use either source or restored data volume.
    for cid in library.call(['ps', '-q', '--no-trunc']).split():
        item = library.inspect(cid)
        if any(m.get('Name') in (config['volume'], restored['cloneVolume']) and m.get('RW')
               for m in item['Mounts']):
            require(item['Id'] in (original['Id'], clone['Id']))
    return dict(restored=restored, original=original, clone=clone, key=key,
        rollback=rollback, config=dict(config), keyBytes=broker.read_file(key, 0, 0o440, group=200))


def process(broker, store, config, library, job):
    ident = job['requestId']
    claim = store.base / 'state' / ('promotion-%s.json' % ident)
    output = store.base / 'results' / ('promotion-%s.json' % ident)
    if os.path.lexists(str(claim)):
        # Never infer success after a crash, and never repeat a stop/rename/run.
        old = None
        if os.path.lexists(str(output)):
            old = broker.parse_unique(broker.read_file(output, 0, 0o640, group=store.app_gid))
        if old is None or old.get('status') not in ('COMPLETED', 'FAILED', 'RECOVERY_REQUIRED'):
            store.write(output, result(job, 'RECOVERY_REQUIRED', error='RESULT_UNCERTAIN'))
        return
    store.write(claim, job, exclusive=True)
    store.write(output, result(job, 'RUNNING'))
    changed = False
    old_cid = None
    new_cid = None
    try:
        with library.budget(1800):
            observed = prepare(broker, store, config, library, job)
            original, clone = observed['original'], observed['clone']
            old_cid = original['Id']
            state = store.base / 'state' / ('promotion-%s-work' % ident)
            require(not os.path.lexists(str(state)))
            state.mkdir(mode=0o700)
            store.write(state / 'preimage.json', dict(job=job, config=config,
                originalProjection=library.projection(original), originalRunning=True,
                originalRestartPolicy=original['HostConfig']['RestartPolicy'],
                cloneProjection=library.projection(clone), rollbackName=observed['rollback']), exclusive=True)
            # The restored key is copied byte-for-byte to the normal persistent
            # system configuration location; no newly generated encryption key.
            key = persistent_key(Path('/etc/deployops/nexus-secrets'), ident, observed['keyBytes'])
            store.write(state / 'mutation-started.json', dict(schema=1, started=True), exclusive=True)
            changed = True
            library.call(['stop', '--time', '120', clone['Id']])
            stopped = library.inspect(clone['Id'])['State']
            require(not stopped['Running'] and stopped['ExitCode'] in (0, 143)
                and not stopped['OOMKilled'] and not stopped['Error'])
            library.call(['stop', '--time', '120', original['Id']])
            retain_rollback(library, original['Id'], observed['rollback'])
            command = ['run', '-d', '--pull', 'never', '--name', config['containerName'], '--restart', 'always',
                '--network', 'bridge', '--stop-timeout', '120']
            for port, bindings in sorted(original['HostConfig']['PortBindings'].items()):
                binding = bindings[0]
                publish = (binding['HostIp'] + ':' if binding['HostIp'] else '') + binding['HostPort'] + ':' + port[:-4]
                command += ['--publish', publish]
            for name in ('INSTALL4J_ADD_VM_PARAMS', 'NEXUS_SECRETS_KEY_FILE'):
                values = [value for value in original['Config']['Env'] if value.startswith(name + '=')]
                require(len(values) == 1)
                command += ['--env', values[0]]
            command += ['--mount', 'type=volume,source=' + observed['restored']['cloneVolume'] + ',target=/nexus-data',
                '--volume', str(key) + ':/run/nexus-secrets/secrets.json:ro,Z', config['imageId']]
            new_cid = library.call(command).strip()
            require(re.fullmatch('[0-9a-f]{64}', new_cid))
            library.wait_health(new_cid)
            current = library.inspect(new_cid)
            updated = dict(config, volume=observed['restored']['cloneVolume'], keyFile=str(key))
            args = SimpleNamespace(container_id=new_cid, container_name=config['containerName'],
                image_id=config['imageId'], volume=updated['volume'], key_file=str(key),
                heap=config['heap'], all_storage_in_data_volume=True)
            library.source_contract(args)
            old = library.inspect(old_cid)
            require(not old['State']['Running'] and old['Name'] == '/' + observed['rollback'])
            require(not library.inspect(clone['Id'])['State']['Running'])
            require(json.loads(broker.read_file(broker.CONFIG, 0, 0o600)) == config)
            temporary = broker.CONFIG.parent / ('.nexus-promotion-%s.json' % ident)
            store.write(temporary, updated, exclusive=True)
            os.replace(str(temporary), str(broker.CONFIG))
            require(json.loads(broker.read_file(broker.CONFIG, 0, 0o600)) == updated)
            store.write(output, result(job, 'COMPLETED', canonical=new_cid, rollback=old_cid))
    except Exception:
        store.write(output, result(job, 'RECOVERY_REQUIRED' if changed else 'FAILED',
            canonical=new_cid, rollback=old_cid if changed else None,
            error='PROMOTION_FAILED' if changed else 'PREFLIGHT_REJECTED'))


def run_queue(broker, store, config, library, ready=True):
    for path in sorted((store.base / 'inbox').iterdir()):
        match = re.fullmatch(r'promotion-([1-9][0-9]{0,18})\.json', path.name)
        if not match:
            continue
        ident = int(match.group(1))
        job = validate_job(store.read_inbox(path), ident)
        output = store.base / 'results' / path.name
        if os.path.lexists(str(output)):
            old = broker.parse_unique(broker.read_file(output, 0, 0o640, group=store.app_gid))
            if old['status'] in ('COMPLETED', 'FAILED'):
                continue
            if old['status'] == 'RECOVERY_REQUIRED':
                return True
        if not ready and not os.path.lexists(str(store.base / 'state' / path.name)):
            return True
        # A legacy or uncertain backup cannot be bypassed by a later promotion.
        # With no promotion queued, leave the ordinary backup recovery queue free.
        for backup_id in store.ids('state'):
            try:
                require(broker.releases_maintenance(store.get_result(backup_id))
                    and not store.mutation_uncertain(backup_id))
            except Exception:
                return True
        process(broker, store, config, library, job)
        return True
    return False
