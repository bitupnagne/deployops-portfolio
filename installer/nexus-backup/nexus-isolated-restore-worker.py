"""Root-only isolated RESTORE jobs; imported under the existing broker lock.

Archive manifests bind archive members, not Maven download baselines. Optional
root-owned baseline receipts are separate evidence. Explicit failure checks never replay.
"""
import base64
import contextlib
import datetime
import hashlib
import http.client
import ipaddress
import json
import os
import re
from pathlib import Path
from types import SimpleNamespace

CONFIG = Path('/etc/deployops/nexus-restore.json')
KINDS = ('network', 'load', 'volume', 'run', 'stop')


def require(ok):
    if not ok:
        raise ValueError('RESTORE_BOUNDARY_REJECTED')


def validate_job(job, request_id):
    require(type(job) is dict and set(job) == {
        'schema', 'action', 'requestId', 'backupRequestId', 'manifestSha256'})
    require(type(job['schema']) is int and job['schema'] == 1)
    require(type(job['requestId']) is int and job['requestId'] == request_id and request_id > 0)
    require(type(job['backupRequestId']) is int and 0 < job['backupRequestId'] <= 9223372036854775807)
    require(job['action'] == 'RESTORE' and type(job['manifestSha256']) is str
            and re.fullmatch(r'[0-9a-f]{64}', job['manifestSha256']))
    return job


def validate_config(value):
    require(type(value) is dict and set(value) == {'schema', 'enabled', 'portBase'})
    require(type(value['schema']) is int and value['schema'] == 1)
    require(type(value['enabled']) is bool and type(value['portBase']) is int)
    require(1024 <= value['portBase'] <= 30000)
    return value


def expectations(receipt, job, receipt_sha):
    require(type(receipt) is dict and set(receipt) == {
        'schema', 'backupRequestId', 'manifestSha256', 'sourceImageId', 'artifacts'})
    require(type(receipt['schema']) is int and receipt['schema'] == 1)
    require(type(receipt['backupRequestId']) is int
            and receipt['backupRequestId'] == job['backupRequestId'])
    require(receipt['manifestSha256'] == job['manifestSha256'])
    require(receipt['sourceImageId'] == 'sha256:617de9147a594bd1fb8d942cd56f1135ebb61cc622566269907e178377b3fdbd')
    require(type(receipt['artifacts']) is list and 1 <= len(receipt['artifacts']) <= 16)
    refs, result = set(), []
    for item in receipt['artifacts']:
        require(type(item) is dict and set(item) == {'ref', 'sha256'})
        require(type(item['ref']) is str and re.fullmatch(r'[A-Za-z0-9_-]{1,64}', item['ref']))
        require(item['ref'] not in refs and type(item['sha256']) is str
                and re.fullmatch(r'[0-9a-f]{64}', item['sha256']))
        refs.add(item['ref'])
        result.append(dict(item, baselineReceiptSha256=receipt_sha))
    return result


def projection(value):
    # Before/after original and existing clones: exclude volatile health counters.
    result = {key: value[key] for key in ('Id', 'Name', 'Image', 'Mounts')}
    result.update(running=value['State']['Running'], startedAt=value['State']['StartedAt'],
                  labels=value.get('Config', {}).get('Labels') or {})
    return result


def public_result(job, status, preserved=False, uncertain=True, error=None, clone=None, checks=None):
    clone = clone or {}
    request_id = job['requestId']
    return {
        'schema': 1, 'requestId': request_id, 'backupRequestId': job['backupRequestId'],
        'manifestSha256': job['manifestSha256'], 'status': status,
        'stage': 'DONE' if status in ('HEALTH_READY', 'FAILED') else 'RESTORING',
        'errorCode': error, 'cloneContainerId': clone.get('containerId'),
        'cloneImageId': clone.get('imageId'),
        'cloneName': 'nexus-restore-' + str(request_id) if clone else None,
        'cloneVolume': clone.get('volume'),
        'cloneNetwork': 'nexus-restore-net-' + str(request_id) if clone else None,
        'originalProjectionPreserved': preserved, 'mutationUncertain': uncertain,
        'expectedArtifacts': checks or [], 'cloneAccess': clone.get('access'),
        'completedAt': datetime.datetime.now(datetime.timezone.utc).isoformat()}


def private_ipv4(value):
    require(type(value) is str)
    address = ipaddress.IPv4Address(value)
    require(str(address) == value and any(address in ipaddress.IPv4Network(network)
        for network in ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16')))
    return address


def validate_access(access, clone_id):
    require(type(access) is dict and type(access.get('schema')) is int
            and access.get('requiresVerifiedRelay') is True
            and type(access.get('executionHost')) is str
            and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,100}', access['executionHost']))
    if access['schema'] == 1:
        require(set(access) == {'schema', 'executionHost', 'ports', 'requiresVerifiedRelay'})
        require(type(access['ports']) is list and 1 <= len(access['ports']) <= 3)
        inner, outer = set(), set()
        for port in access['ports']:
            require(type(port) is dict and set(port) == {'containerPort', 'hostIp', 'hostPort'})
            require(port['hostIp'] == '127.0.0.1')
            for field in ('containerPort', 'hostPort'):
                require(type(port[field]) is int and 1 <= port[field] <= 65535)
            require(port['containerPort'] not in inner and port['hostPort'] not in outer)
            inner.add(port['containerPort'])
            outer.add(port['hostPort'])
        require(8081 in inner)
    else:
        require(access['schema'] == 2 and set(access) == {'schema', 'transport', 'executionHost',
            'cloneContainerId', 'networkId', 'containerIpv4', 'containerPort', 'requiresVerifiedRelay'})
        require(access['transport'] == 'EXECUTION_HOST_BRIDGE_ACCESS'
                and access['cloneContainerId'] == clone_id and type(clone_id) is str
                and re.fullmatch(r'[0-9a-f]{64}', clone_id)
                and type(access['networkId']) is str and re.fullmatch(r'[0-9a-f]{64}', access['networkId'])
                and type(access['containerPort']) is int and access['containerPort'] == 8081)
        private_ipv4(access['containerIpv4'])


def loopback_connections(published, ports, base):
    require(type(published) is dict and set(published) == set(str(port) + '/tcp' for port in ports))
    connections = []
    for index, port in enumerate(ports):
        mapping = published[str(port) + '/tcp']
        require(type(mapping) is list and len(mapping) == 1 and type(mapping[0]) is dict
                and set(mapping[0]) == {'HostIp', 'HostPort'}
                and mapping[0].get('HostIp') == '127.0.0.1'
                and mapping[0].get('HostPort') == str(base + index))
        connections.append({'containerPort': port, 'hostIp': '127.0.0.1', 'hostPort': base + index})
    return connections


def bridge_snapshot(library, current, args, metadata, ports, base):
    require(current.get('Id') and re.fullmatch(r'[0-9a-f]{64}', current['Id'])
            and current['State']['Running'] is True
            and library.restore_identity(current, args, metadata, Path(args.new_key_directory) / 'secrets.json'))
    require(type(current['NetworkSettings']['Ports']) is dict and not current['NetworkSettings']['Ports'])
    require(current['HostConfig']['NetworkMode'] == args.network)
    loopback_connections(current['HostConfig']['PortBindings'], ports, base)
    networks = current['NetworkSettings']['Networks']
    require(type(networks) is dict and set(networks) == {args.network})
    endpoint = networks[args.network]
    observed = json.loads(library.call(['network', 'inspect', args.network]))
    require(type(observed) is list and len(observed) == 1)
    network = observed[0]
    require(type(network) is dict and type(network.get('Id')) is str
            and re.fullmatch(r'[0-9a-f]{64}', network['Id'])
            and network['Name'] == args.network and network['Driver'] == 'bridge'
            and network['Internal'] is True and network['ConfigOnly'] is False
            and network.get('Labels', {}).get('deployops.nexus.restore') == args.new_name
            and endpoint['NetworkID'] == network['Id']
            and type(network['Containers']) is dict and set(network['Containers']) == {current['Id']}
            and type(endpoint['EndpointID']) is str and re.fullmatch(r'[0-9a-f]{64}', endpoint['EndpointID']))
    member = network['Containers'][current['Id']]
    address = endpoint['IPAddress']
    private_ipv4(address)
    attached_address = ipaddress.IPv4Interface(member['IPv4Address'])
    require(str(attached_address) == member['IPv4Address'] and str(attached_address.ip) == address
            and member['Name'] == args.new_name and member['EndpointID'] == endpoint['EndpointID'])
    return {'projection': canonical_projection(projection(current)), 'network': network,
            'endpoints': networks, 'requestedBindings': current['HostConfig']['PortBindings']}, address


def clone_access(library, current, args, metadata, ports, base, host, clone_id):
    require(type(host) is str and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,100}', host))
    published = current['NetworkSettings']['Ports']
    if published:
        access = {'schema': 1, 'executionHost': host,
                  'ports': loopback_connections(published, ports, base), 'requiresVerifiedRelay': True}
    else:
        require(current.get('Id') == clone_id)
        before, address = bridge_snapshot(library, current, args, metadata, ports, base)
        connection = http.client.HTTPConnection(address, 8081, timeout=5)
        try:
            connection.request('GET', '/', headers={'Connection': 'close'})
            require(connection.getresponse().status == 200)
        finally:
            connection.close()
        after, observed_address = bridge_snapshot(library, library.inspect(current['Id']), args, metadata, ports, base)
        require(before == after and address == observed_address)
        access = {'schema': 2, 'transport': 'EXECUTION_HOST_BRIDGE_ACCESS', 'executionHost': host,
                  'cloneContainerId': current['Id'], 'networkId': before['network']['Id'],
                  'containerIpv4': address, 'containerPort': 8081, 'requiresVerifiedRelay': True}
    validate_access(access, clone_id)
    return access


def terminal_releases(value, job):
    template = public_result(job, 'FAILED')
    require(type(value) is dict and set(value) == set(template))
    require(type(value['schema']) is int and value['schema'] == 1)
    for field in ('requestId', 'backupRequestId'):
        require(type(value[field]) is int and value[field] == job[field])
    require(value['manifestSha256'] == job['manifestSha256'])
    require(type(value['mutationUncertain']) is bool
            and type(value['originalProjectionPreserved']) is bool)
    require(type(value['expectedArtifacts']) is list and len(value['expectedArtifacts']) <= 16)
    refs = set()
    for item in value['expectedArtifacts']:
        require(type(item) is dict and set(item) == {'ref', 'sha256', 'baselineReceiptSha256'})
        require(type(item['ref']) is str and re.fullmatch(r'[A-Za-z0-9_-]{1,64}', item['ref'])
                and item['ref'] not in refs)
        refs.add(item['ref'])
        for field in ('sha256', 'baselineReceiptSha256'):
            require(type(item[field]) is str and re.fullmatch(r'[0-9a-f]{64}', item[field]))
    require(type(value['completedAt']) is str
            and re.fullmatch(r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,6})?(Z|\+00:00)', value['completedAt']))
    datetime.datetime.strptime(value['completedAt'][:19], '%Y-%m-%dT%H:%M:%S')
    if value['status'] not in ('HEALTH_READY', 'FAILED'):
        return False
    require(value['stage'] == 'DONE')
    if value['status'] == 'HEALTH_READY':
        require(value['errorCode'] is None and type(value['cloneContainerId']) is str
                and re.fullmatch(r'[0-9a-f]{64}', value['cloneContainerId']))
        require(type(value['cloneImageId']) is str and re.fullmatch(r'sha256:[0-9a-f]{64}', value['cloneImageId']))
        require(value['cloneName'] == 'nexus-restore-' + str(job['requestId'])
                and value['cloneVolume'] == 'nexus-restore-data-' + str(job['requestId'])
                and value['cloneNetwork'] == 'nexus-restore-net-' + str(job['requestId']))
        validate_access(value['cloneAccess'], value['cloneContainerId'])
    else:
        require(value['errorCode'] in ('PREFLIGHT_REJECTED', 'RESTORE_FAILED'))
    return value['mutationUncertain'] is False and value['originalProjectionPreserved'] is True


def held_result(job, previous):
    # A restart changes the hold status, not the previously observed clone/checks.
    # Validate every retained public field; malformed/missing evidence uses defaults.
    terminal_releases(previous, job)
    require(previous['status'] in ('RUNNING', 'RECOVERY_REQUIRED', 'HEALTH_READY', 'FAILED'))
    require(previous['stage'] == ('DONE' if previous['status'] in ('HEALTH_READY', 'FAILED') else 'RESTORING'))
    require(previous['errorCode'] in (None, 'PREFLIGHT_REJECTED', 'RESTORE_FAILED', 'RESULT_UNCERTAIN'))
    clone_fields = ('cloneContainerId', 'cloneImageId', 'cloneName', 'cloneVolume', 'cloneNetwork', 'cloneAccess')
    if any(previous[field] is not None for field in clone_fields):
        # Reuse the complete HEALTH_READY clone identity/access validator without
        # treating its return value as proof that this held request may release.
        terminal_releases(dict(previous, status='HEALTH_READY', stage='DONE', errorCode=None), job)
    return dict(previous, status='RECOVERY_REQUIRED', stage='RESTORING',
                errorCode='RESULT_UNCERTAIN', mutationUncertain=True)


class RestoreStore:
    def __init__(self, broker, store):
        self.broker, self.store, self.base = broker, store, store.base

    def path(self, area, request_id, suffix=''):
        return self.base / area / ('restore-' + str(request_id) + suffix + '.json')

    def ids(self, area):
        for path in sorted((self.base / area).iterdir()):
            match = re.fullmatch(r'restore-([1-9][0-9]{0,18})\.json', path.name)
            if match and self.broker.positive_id(int(match[1])):
                yield int(match[1])

    def read(self, area, request_id, suffix=''):
        return self.broker.parse_unique(self.broker.read_file(
            self.path(area, request_id, suffix), 0, 0o640 if area == 'results' else 0o600,
            group=self.store.app_gid if area == 'results' else 0))

    def exists(self, area, request_id, suffix=''):
        return os.path.lexists(str(self.path(area, request_id, suffix)))

    def job(self, request_id):
        return validate_job(self.store.read_inbox(self.path('inbox', request_id)), request_id)

    def ledger(self, request_id):
        value = self.read('state', request_id, '.mutations')
        require(type(value) is dict and set(value) == {'schema', 'requestId', 'events'})
        require(type(value['schema']) is int and value['schema'] == 1)
        require(type(value['requestId']) is int and value['requestId'] == request_id)
        require(type(value['events']) is list and len(value['events']) <= 32)
        for sequence, event in enumerate(value['events'], 1):
            require(type(event) is dict and set(event) == {'sequence', 'kind', 'state'})
            require(type(event['sequence']) is int and event['sequence'] == sequence)
            require(event['kind'] in KINDS and event['state'] in ('inflight', 'completed'))
        return value

    def initialize(self, request_id, expected_job=None):
        job = self.job(request_id)
        require(expected_job is None or job == expected_job)
        require(not self.exists('state', request_id)
                and not self.exists('state', request_id, '.baseline')
                and not self.exists('results', request_id))
        if self.exists('state', request_id, '.mutations'):
            require(self.ledger(request_id)['events'] == [])
        else:
            self.store.write(self.path('state', request_id, '.mutations'),
                             {'schema': 1, 'requestId': request_id, 'events': []}, exclusive=True)

    def uncertain(self, request_id):
        try:
            return any(event['state'] != 'completed' for event in self.ledger(request_id)['events'])
        except Exception:
            return True

    def begin(self, request_id, kind):
        require(kind in KINDS and not self.uncertain(request_id))
        value = self.ledger(request_id)
        require(len(value['events']) < 32)
        sequence = len(value['events']) + 1
        value['events'].append({'sequence': sequence, 'kind': kind, 'state': 'inflight'})
        self.store.write(self.path('state', request_id, '.mutations'), value)
        return sequence

    def complete(self, request_id, sequence):
        value = self.ledger(request_id)
        require(type(sequence) is int and 1 <= sequence <= len(value['events']))
        require(value['events'][sequence - 1]['state'] == 'inflight')
        value['events'][sequence - 1]['state'] = 'completed'
        self.store.write(self.path('state', request_id, '.mutations'), value)

    def publish(self, value):
        # A held result is durable evidence, not a new event on each poll.
        try:
            previous = self.read('results', value['requestId'])
            terminal_releases(previous, {'requestId': value['requestId'],
                'backupRequestId': value['backupRequestId'], 'manifestSha256': value['manifestSha256']})
            comparable = lambda result: {key: item for key, item in result.items() if key != 'completedAt'}
            if type(previous) is dict and comparable(previous) == comparable(value):
                return
        except Exception:
            pass
        self.store.write(self.path('results', value['requestId']), value)

    def checks(self):
        for path in sorted((self.base / 'inbox').iterdir()):
            match = re.fullmatch(r'restore-([1-9][0-9]{0,18})-([1-9][0-9]{0,18})\.check\.json', path.name)
            if match and all(self.broker.positive_id(int(value)) for value in match.groups()):
                yield int(match[1]), int(match[2])

    def check_path(self, area, request_id, check_id, suffix=''):
        return self.base / area / ('restore-%s-%s%s.check.json' % (request_id, check_id, suffix))


def canonical_projection(value):
    require(type(value) is dict and set(value) == {'Id', 'Name', 'Image', 'Mounts', 'running', 'startedAt', 'labels'})
    require(type(value['Mounts']) is list)
    destinations = set()
    for mount in value['Mounts']:
        require(type(mount) is dict and type(mount.get('Destination')) is str
                and mount['Destination'] not in destinations)
        destinations.add(mount['Destination'])
    return dict(value, Mounts=sorted(value['Mounts'], key=lambda mount: mount['Destination']))


def verify_precontainer_failure(store, job, config, library, archived_previous=None):
    request_id = job['requestId']
    claim = validate_job(store.read('state', request_id), request_id)
    require(claim == job and store.job(request_id) == job)
    previous = store.read('results', request_id) if archived_previous is None else archived_previous
    require(previous['status'] == 'RECOVERY_REQUIRED' and previous['errorCode'] == 'RESULT_UNCERTAIN')
    previous = held_result(job, previous)
    require(all(previous[field] is None for field in
                ('cloneContainerId', 'cloneImageId', 'cloneName', 'cloneVolume', 'cloneNetwork', 'cloneAccess')))
    ledger = store.ledger(request_id)
    require([event['kind'] for event in ledger['events']] == ['network', 'load', 'volume']
            and all(event['state'] == 'completed' for event in ledger['events']))
    baseline = store.read('state', request_id, '.baseline')
    require(type(baseline) is dict and baseline)
    name, volume, network = ('nexus-restore-' + str(request_id),
                             'nexus-restore-data-' + str(request_id),
                             'nexus-restore-net-' + str(request_id))
    original = library.inspect(config['containerName'])
    require(original['Name'] == '/' + config['containerName'] and original['Image'] == config['imageId']
            and original['State']['Running'] is True and original['Id'] in baseline
            and any(m.get('Type') == 'volume' and m.get('Name') == config['volume']
                    and m.get('Destination') == '/nexus-data' for m in original['Mounts']))
    for cid, before in baseline.items():
        require(type(cid) is str and re.fullmatch(r'[0-9a-f]{64}', cid) and before['Id'] == cid)
        require(canonical_projection(projection(library.inspect(cid))) == canonical_projection(before))
    require(not library.call(['ps', '-aq', '--no-trunc', '--filter', 'name=^/' + name + '$']).strip()
            and not library.call(['ps', '-aq', '--no-trunc', '--filter', 'label=deployops.nexus.restore=' + name]).strip())
    observed_network = json.loads(library.call(['network', 'inspect', network]))
    observed_volume = json.loads(library.call(['volume', 'inspect', volume]))
    require(type(observed_network) is list and len(observed_network) == 1
            and type(observed_volume) is list and len(observed_volume) == 1)
    net, vol = observed_network[0], observed_volume[0]
    labels = {'deployops.nexus.restore': name}
    require(net['Name'] == network and type(net['Id']) is str and re.fullmatch(r'[0-9a-f]{64}', net['Id'])
            and net['Driver'] == 'bridge' and net['Internal'] is True
            and net['Labels'] == labels and net['Containers'] == {})
    require(vol['Name'] == volume and vol['Driver'] == 'local' and not vol.get('Options')
            and vol['Labels'] == labels)
    data = library.volume_path(volume)
    library.secure_path(str(data), directory=True, owner=0)
    require(next(data.iterdir(), None) is None)
    work = store.base / 'state' / ('restore-' + str(request_id) + '-work')
    library.secure_path(str(work), directory=True, mode=0o700, owner=0)
    require(not os.path.lexists(str(work / 'restore.result.json'))
            and {path.name for path in work.iterdir()} == {'keys'})
    key = work / 'keys' / 'secrets.json'
    library.validate_key(key)
    require({path.name for path in key.parent.iterdir()} == {'secrets.json'}
            and library.digest(key) == library.digest(Path(config['keyFile'])))
    return dict(claim=claim, previous=store.read('results', request_id), ledger=ledger,
                baseline=baseline, network=net, volume=vol)


def process_failure_check(broker, store, config, library, request_id, check_id):
    require(broker.positive_id(request_id) and broker.positive_id(check_id))
    claim_path = store.check_path('state/checks', request_id, check_id)
    result_path = store.check_path('results', request_id, check_id)
    evidence_path = store.check_path('state/checks', request_id, check_id, '.evidence')
    if broker.shutdown_requested() or os.path.lexists(str(result_path)):
        return False
    used = os.path.lexists(str(claim_path))
    result = dict(schema=1, requestId=request_id, checkId=check_id, backupRequestId=None,
                  manifestSha256=None, status='UNCONFIRMED', reasonCode='STATE_UNCONFIRMED',
                  originalProjectionPreserved=False, mutationUncertain=True,
                  completedAt=datetime.datetime.now(datetime.timezone.utc).isoformat())
    try:
        check = (broker.parse_unique(broker.read_file(claim_path, 0, 0o600, group=0)) if used else
                 store.store.read_inbox(store.check_path('inbox', request_id, check_id)))
        require(type(check) is dict and set(check) == {
            'schema', 'requestId', 'checkId', 'action', 'backupRequestId', 'manifestSha256'}
            and type(check['checkId']) is int and check['checkId'] == check_id
            and check['action'] == 'CHECK_RESTORE_FAILURE')
        job = validate_job(dict({key: value for key, value in check.items() if key not in ('checkId', 'action')},
                                action='RESTORE'), request_id)
        result.update(backupRequestId=job['backupRequestId'], manifestSha256=job['manifestSha256'])
        if not used:
            # Exclusive root token pins the original check; a partial run never restarts it.
            store.store.write(claim_path, check, exclusive=True)
        with library.budget(120):
            if used:
                # Only finish missing check publication; never write the primary or
                # recreate evidence, and never replay a restore or a daemon mutation.
                evidence = broker.parse_unique(broker.read_file(evidence_path, 0, 0o600, group=0))
                require(type(evidence) is dict and set(evidence) == {
                    'schema', 'requestId', 'checkId', 'claim', 'previous', 'ledger', 'baseline', 'network', 'volume'}
                    and type(evidence['schema']) is int and evidence['schema'] == 1
                    and type(evidence['requestId']) is int and evidence['requestId'] == request_id
                    and type(evidence['checkId']) is int and evidence['checkId'] == check_id)
                primary = store.read('results', request_id)
                require(terminal_releases(primary, job) and primary['status'] == 'FAILED'
                        and primary['errorCode'] == 'RESTORE_FAILED'
                        and primary['expectedArtifacts'] == evidence['previous']['expectedArtifacts']
                        and all(primary[field] is None for field in
                                ('cloneContainerId', 'cloneImageId', 'cloneName', 'cloneVolume', 'cloneNetwork', 'cloneAccess')))
                observed = verify_precontainer_failure(store, job, config, library, evidence['previous'])
                require(all(observed[field] == evidence[field] for field in
                            ('claim', 'ledger', 'baseline', 'network', 'volume')))
                require(verify_precontainer_failure(store, job, config, library, evidence['previous']) == observed)
            else:
                evidence = verify_precontainer_failure(store, job, config, library)
                require(not broker.shutdown_requested())
                require(verify_precontainer_failure(store, job, config, library) == evidence)
                store.store.write(evidence_path, dict(schema=1, requestId=request_id, checkId=check_id, **evidence), exclusive=True)
                store.publish(public_result(job, 'FAILED', True, False, error='RESTORE_FAILED',
                                            checks=evidence['previous']['expectedArtifacts']))
            require(not broker.shutdown_requested())
            result.update(status='VERIFIED_FAILED', reasonCode='PRECONTAINER_FAILURE_CONFIRMED',
                          originalProjectionPreserved=True, mutationUncertain=False)
    except Exception:
        pass  # No restore/recovery/cleanup invocation; unknown checks leave the hold.
    store.store.write(result_path, result)
    return True


def verify_existing_clone(store, job, config, restore_config, library):
    """Read-only proof; never enter RestoreExecutor or mutation-ledger callbacks."""
    request_id = job['requestId']
    claim = validate_job(store.read('state', request_id), request_id)
    require(claim == job and store.job(request_id) == job)
    ledger = store.ledger(request_id)
    require([event['kind'] for event in ledger['events']] == ['network', 'load', 'volume', 'run']
            and all(event['state'] == 'completed' for event in ledger['events']))
    baseline = store.read('state', request_id, '.baseline')
    require(type(baseline) is dict and baseline)
    original = library.inspect(config['containerName'])
    require(original['Name'] == '/' + config['containerName'] and original['Image'] == config['imageId']
            and original['State']['Running'] is True and original['Id'] in baseline
            and any(m.get('Type') == 'volume' and m.get('Name') == config['volume']
                    and m.get('Destination') == '/nexus-data' for m in original['Mounts']))
    for cid, before in baseline.items():
        require(type(cid) is str and re.fullmatch(r'[0-9a-f]{64}', cid) and before['Id'] == cid)
        require(canonical_projection(projection(library.inspect(cid))) == canonical_projection(before))
    backup = store.store.get_result(job['backupRequestId'])
    require(backup['status'] == 'COMPLETED' and backup['originalStateRestored'] is True
            and backup['artifactId'] == 'nexus-backup-' + str(job['backupRequestId'])
            and backup['manifestSha256'] == job['manifestSha256'])
    root, metadata = library.verify_artifact(str(Path(config['backupRoot']) / backup['artifactId']))
    require(library.digest(root / 'manifest.json') == job['manifestSha256'])
    ports = metadata['containerPorts']
    require(type(ports) is list and 1 <= len(ports) <= 3 and 8081 in ports
            and len(set(ports)) == len(ports)
            and all(type(port) is int and 1 <= port <= 65535 for port in ports))
    base = restore_config['portBase'] + 3 * request_id
    require(restore_config['enabled'] is True and base + len(ports) <= 65535)
    work = store.base / 'state' / ('restore-' + str(request_id) + '-work')
    private = store.broker.parse_unique(store.broker.read_file(work / 'restore.result.json', 0, 0o600, group=0))
    require(type(private) is dict and set(private) == {'status', 'containerId', 'imageId', 'volume',
        'ports', 'keySha256', 'artifactManifestSha256', 'originalVolumeTouched',
        'authenticationArtifactsRegistryVerified'})
    cid = private['containerId']
    require(type(cid) is str and re.fullmatch(r'[0-9a-f]{64}', cid) and cid not in baseline
            and private['status'] == 'LOCAL_NEXUS_RESTORE_HEALTH_READY_FUNCTIONAL_VALIDATION_PENDING'
            and private['imageId'] == metadata['imageId']
            and private['volume'] == 'nexus-restore-data-' + str(request_id)
            and private['ports'] == [[port, base + index] for index, port in enumerate(ports)]
            and type(private['keySha256']) is str and re.fullmatch(r'[0-9a-f]{64}', private['keySha256'])
            and private['artifactManifestSha256'] == job['manifestSha256']
            and private['originalVolumeTouched'] is False
            and private['authenticationArtifactsRegistryVerified'] is False)
    args = SimpleNamespace(new_name='nexus-restore-' + str(request_id), new_volume=private['volume'],
        network='nexus-restore-net-' + str(request_id), new_key_directory=str(work / 'keys'))
    volumes = json.loads(library.call(['volume', 'inspect', args.new_volume]))
    require(type(volumes) is list and len(volumes) == 1)
    volume = volumes[0]
    require(volume['Name'] == args.new_volume and volume['Driver'] == 'local' and not volume.get('Options')
            and volume.get('Labels') == {'deployops.nexus.restore': args.new_name})
    current = library.inspect(cid)
    require(current['Id'] == cid and current['State']['Running'] is True
            and library.restore_identity(current, args, metadata, work / 'keys' / 'secrets.json'))
    require(library.call(['exec', cid, 'curl', '--noproxy', '*', '-sS', '-o', '/dev/null', '--max-time', '3',
        '-w', '%{http_code}', 'http://127.0.0.1:8081/service/rest/v1/status']) == '200')
    access = clone_access(library, current, args, metadata, ports, base, os.uname().nodename, cid)
    require(access['schema'] == 2)
    endpoint, address = bridge_snapshot(library, library.inspect(cid), args, metadata, ports, base)
    require(access['containerIpv4'] == address)
    checks = []
    receipt = store.base / 'state' / 'artifact-baselines' / ('backup-' + str(job['backupRequestId']) + '.json')
    if os.path.lexists(str(receipt)):
        raw = store.broker.read_file(receipt, 0, 0o600, group=0)
        checks = expectations(store.broker.parse_unique(raw), job, hashlib.sha256(raw).hexdigest())
    # The backup broker lease does not prevent unrelated original-container writes.
    for original_id, before in baseline.items():
        require(canonical_projection(projection(library.inspect(original_id))) == canonical_projection(before))
    require(store.read('state', request_id) == claim and store.job(request_id) == job
            and store.ledger(request_id) == ledger and store.read('state', request_id, '.baseline') == baseline
            and store.broker.parse_unique(store.broker.read_file(work / 'restore.result.json', 0, 0o600, group=0)) == private)
    return dict(claim=claim, ledger=ledger, baseline=baseline, private=private, backup=backup,
        metadata=metadata, endpoint=endpoint, volume=volume,
        clone=dict(containerId=cid, imageId=metadata['imageId'], volume=args.new_volume, access=access), checks=checks)


def process_readiness_check(broker, store, config, restore_config, library, request_id, check_id):
    require(broker.positive_id(request_id) and broker.positive_id(check_id))
    token_path = store.check_path('state/checks', request_id, check_id)
    evidence_path = store.check_path('state/checks', request_id, check_id, '.readiness-evidence')
    result_path = store.check_path('results', request_id, check_id)
    if broker.shutdown_requested() or os.path.lexists(str(result_path)):
        return False
    primary_attempted = False
    result = dict(schema=2, requestId=request_id, checkId=check_id, backupRequestId=None,
        manifestSha256=None, status='UNCONFIRMED', reasonCode='STATE_UNCONFIRMED',
        originalProjectionPreserved=False, mutationUncertain=True,
        completedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
        evidenceSha256=None, primarySha256=None, primaryResult=None)
    try:
        check = store.store.read_inbox(store.check_path('inbox', request_id, check_id))
        require(type(check) is dict and set(check) == {'schema', 'requestId', 'checkId', 'action',
            'backupRequestId', 'manifestSha256'} and type(check['checkId']) is int
            and check['checkId'] == check_id and check['action'] == 'CHECK_EXISTING_CLONE_READINESS')
        job = validate_job(dict({key: value for key, value in check.items() if key not in ('action', 'checkId')},
                                action='RESTORE'), request_id)
        result.update(backupRequestId=job['backupRequestId'], manifestSha256=job['manifestSha256'])
        used = os.path.lexists(str(token_path))
        if used:
            require(broker.parse_unique(broker.read_file(token_path, 0, 0o600, group=0)) == check)
        else:
            require(all(other_request != request_id or other_check == check_id
                or os.path.lexists(str(store.check_path('results', other_request, other_check)))
                for other_request, other_check in store.checks()))
            store.store.write(token_path, check, exclusive=True)
        with library.budget(120):
            observed = verify_existing_clone(store, job, config, restore_config, library)
            raw = broker.read_file(store.path('results', request_id), 0, 0o640, group=store.store.app_gid)
            primary = broker.parse_unique(raw)
            if used:
                # A used token without immutable evidence cannot invent history.
                evidence_raw = broker.read_file(evidence_path, 0, 0o600, group=0, limit=65536)
                evidence = broker.parse_unique(evidence_raw)
                require(type(evidence) is dict and set(evidence) == {'schema', 'check', 'oldPrimaryBase64',
                    'oldPrimarySha256', 'observed', 'primary'} and type(evidence['schema']) is int
                    and evidence['schema'] == 1 and evidence['check'] == check)
                old_raw = base64.b64decode(evidence['oldPrimaryBase64'].encode('ascii'), validate=True)
                require(hashlib.sha256(old_raw).hexdigest() == evidence['oldPrimarySha256'])
                old = broker.parse_unique(old_raw)
                require(old['status'] == 'RECOVERY_REQUIRED' and old['errorCode'] == 'RESULT_UNCERTAIN')
                held_result(job, old)
                require(all(old[field] is None for field in ('cloneContainerId', 'cloneImageId', 'cloneName',
                    'cloneVolume', 'cloneNetwork', 'cloneAccess')))
                require(observed == evidence['observed'] and terminal_releases(evidence['primary'], job))
                expected = public_result(job, 'HEALTH_READY', True, False,
                    clone=observed['clone'], checks=observed['checks'])
                expected['completedAt'] = evidence['primary']['completedAt']
                require(expected == evidence['primary'])
                require(raw == old_raw or primary == evidence['primary'])
            else:
                require(primary['status'] == 'RECOVERY_REQUIRED' and primary['errorCode'] == 'RESULT_UNCERTAIN')
                held_result(job, primary)
                require(all(primary[field] is None for field in ('cloneContainerId', 'cloneImageId', 'cloneName',
                    'cloneVolume', 'cloneNetwork', 'cloneAccess')))
                evidence = dict(schema=1, check=check, oldPrimaryBase64=base64.b64encode(raw).decode('ascii'),
                    oldPrimarySha256=hashlib.sha256(raw).hexdigest(), observed=observed,
                    primary=public_result(job, 'HEALTH_READY', True, False,
                        clone=observed['clone'], checks=observed['checks']))
                # Full persisted baseline plus old bytes must remain bounded, but
                # can legitimately exceed the small standalone-result limit.
                require(len(json.dumps(evidence, sort_keys=True).encode('utf-8') + b'\n') <= 65536)
                store.store.write(evidence_path, evidence, exclusive=True)
                evidence_raw = broker.read_file(evidence_path, 0, 0o600, group=0, limit=65536)
                require(broker.parse_unique(evidence_raw) == evidence)
            require(verify_existing_clone(store, job, config, restore_config, library) == observed
                    and store.store.read_inbox(store.check_path('inbox', request_id, check_id)) == check
                    and broker.parse_unique(broker.read_file(token_path, 0, 0o600, group=0)) == check
                    and broker.read_file(evidence_path, 0, 0o600, group=0, limit=65536) == evidence_raw
                    and broker.read_file(store.path('results', request_id), 0, 0o640,
                        group=store.store.app_gid) == raw and not broker.shutdown_requested())
            candidate = dict(result, status='VERIFIED_HEALTH_READY', reasonCode='EXISTING_CLONE_ACCESS_CONFIRMED',
                originalProjectionPreserved=True, mutationUncertain=False,
                evidenceSha256=hashlib.sha256(evidence_raw).hexdigest(), primarySha256='0' * 64,
                primaryResult=evidence['primary'])
            require(len(json.dumps(candidate, sort_keys=True).encode('utf-8') + b'\n') <= 8192)
            # Flush/fsync immutable history first, then terminal authority, then check.
            primary_attempted = True
            if primary != evidence['primary']:
                store.publish(evidence['primary'])
            primary_raw = broker.read_file(store.path('results', request_id), 0, 0o640, group=store.store.app_gid)
            require(broker.parse_unique(primary_raw) == evidence['primary'])
            require(verify_existing_clone(store, job, config, restore_config, library) == observed
                    and store.store.read_inbox(store.check_path('inbox', request_id, check_id)) == check
                    and broker.read_file(evidence_path, 0, 0o600, group=0, limit=65536) == evidence_raw
                    and broker.read_file(store.path('results', request_id), 0, 0o640,
                        group=store.store.app_gid) == primary_raw and not broker.shutdown_requested())
            result.update(status='VERIFIED_HEALTH_READY', reasonCode='EXISTING_CLONE_ACCESS_CONFIRMED',
                originalProjectionPreserved=True, mutationUncertain=False,
                evidenceSha256=hashlib.sha256(evidence_raw).hexdigest(),
                primarySha256=hashlib.sha256(primary_raw).hexdigest(), primaryResult=evidence['primary'])
            store.store.write(result_path, result)
            return True
    except Exception:
        # A terminal primary may already be durable. Never downgrade it or emit an
        # UNCONFIRMED check which would abandon the matching active-check token.
        if primary_attempted:
            return True
        try:
            if store.read('results', request_id)['status'] == 'HEALTH_READY':
                return True
        except Exception:
            return True  # Unknown primary state: keep token/check unresolved.
        store.store.write(result_path, result)
        return True


class RestoreExecutor:
    def __init__(self, broker, store, config, restore_config, library):
        self.broker, self.store, self.config = broker, store, config
        self.restore_config, self.lib = restore_config, library

    def snapshot(self):
        original = self.lib.inspect(self.config['containerName'])
        require(original['Name'] == '/' + self.config['containerName']
                and original['Image'] == self.config['imageId'])
        require(any(m.get('Type') == 'volume' and m.get('Name') == self.config['volume']
                    and m.get('Destination') == '/nexus-data' for m in original['Mounts']))
        existing = self.lib.call(['ps', '-aq', '--no-trunc', '--filter', 'label=deployops.nexus.restore']).splitlines()
        ids = {original['Id']}
        for cid in existing:
            require(re.fullmatch(r'[0-9a-f]{64}', cid))
            ids.add(cid)
        return {cid: projection(self.lib.inspect(cid)) for cid in sorted(ids)}

    def run(self, job):
        request_id, backup_id = job['requestId'], job['backupRequestId']
        baseline, clone, checks = None, None, []
        preserved = False
        namespace = self.lib.call.__globals__
        prior = namespace['MUTATION_OBSERVER']
        try:
            require(not self.broker.shutdown_requested())
            # This ledger was durably initialized before the exclusive claim.
            namespace['MUTATION_OBSERVER'] = SimpleNamespace(
                begin=lambda kind: self.store.begin(request_id, kind),
                complete=lambda sequence: self.store.complete(request_id, sequence))
            with self.lib.budget(self.lib.WORK_SECONDS):
                # Persist preservation evidence before even the backup-result read.
                observed = self.snapshot()
                self.store.store.write(self.store.path('state', request_id, '.baseline'), observed, exclusive=True)
                baseline = observed
                backup = self.store.store.get_result(backup_id)
                require(backup['status'] == 'COMPLETED'
                        and backup['originalStateRestored'] is True
                        and backup['artifactId'] == 'nexus-backup-' + str(backup_id)
                        and backup['manifestSha256'] == job['manifestSha256'])
                artifact = Path(self.config['backupRoot']) / backup['artifactId']
                root, meta = self.lib.verify_artifact(str(artifact))
                require(self.lib.digest(root / 'manifest.json') == job['manifestSha256'])
                ports = meta['containerPorts']
                require(type(ports) is list and 1 <= len(ports) <= 3
                        and all(type(port) is int and 1 <= port <= 65535 for port in ports)
                        and len(set(ports)) == len(ports) and 8081 in ports)
                base = self.restore_config['portBase'] + 3 * request_id
                require(base + len(ports) <= 65535)
                network = 'nexus-restore-net-' + str(request_id)
                name, volume = 'nexus-restore-' + str(request_id), 'nexus-restore-data-' + str(request_id)
                require(name != self.config['containerName'] and volume != self.config['volume'])
                require(name not in self.lib.call(['ps', '-a', '--format', '{{.Names}}']).splitlines()
                        and volume not in self.lib.call(['volume', 'ls', '--format', '{{.Name}}']).splitlines()
                        and network not in self.lib.call(['network', 'ls', '--format', '{{.Name}}']).splitlines())
                receipt_path = self.store.base / 'state' / 'artifact-baselines' / ('backup-' + str(backup_id) + '.json')
                if os.path.lexists(str(receipt_path)):
                    raw = self.broker.read_file(receipt_path, 0, 0o600, group=0)
                    checks = expectations(self.broker.parse_unique(raw), job, hashlib.sha256(raw).hexdigest())
                work = self.store.base / 'state' / ('restore-' + str(request_id) + '-work')
                self.lib.new_path(str(work))
                work.mkdir(mode=0o700)
                os.chown(str(work), 0, 0)
                self.lib.call(['network', 'create', '--driver', 'bridge', '--internal',
                               '--label', 'deployops.nexus.restore=nexus-restore-' + str(request_id), network])
                args = SimpleNamespace(artifact=str(artifact), new_name='nexus-restore-' + str(request_id),
                    new_volume='nexus-restore-data-' + str(request_id), network=network,
                    new_key_directory=str(work / 'keys'), result=str(work / 'restore.result.json'),
                    ports=','.join('%s:%s' % (port, base + index) for index, port in enumerate(ports)))
                # Local socket binding is not a daemon port-availability proof.
                # The reviewed library's docker run response is authoritative.
                with contextlib.redirect_stdout(self.broker.io.StringIO()):
                    self.lib.restore(args)
                private = self.broker.parse_unique(self.broker.read_file(Path(args.result), 0, 0o600))
                require(private['status'] == 'LOCAL_NEXUS_RESTORE_HEALTH_READY_FUNCTIONAL_VALIDATION_PENDING'
                        and private['artifactManifestSha256'] == job['manifestSha256']
                        and private['originalVolumeTouched'] is False
                        and private['authenticationArtifactsRegistryVerified'] is False)
                cid = private['containerId']
                require(type(cid) is str and re.fullmatch(r'[0-9a-f]{64}', cid))
                current = self.lib.inspect(cid)
                require(current['State']['Running'] is True
                        and self.lib.restore_identity(current, args, meta, Path(args.new_key_directory) / 'secrets.json'))
                host = os.uname().nodename
                clone = {'containerId': cid, 'imageId': meta['imageId'], 'volume': args.new_volume,
                         'access': clone_access(self.lib, current, args, meta, ports, base, host, cid)}
            with self.lib.budget(120, independent=True):
                preserved = all(canonical_projection(projection(self.lib.inspect(cid))) == canonical_projection(value)
                                for cid, value in baseline.items())
            require(preserved and not self.store.uncertain(request_id))
            return public_result(job, 'HEALTH_READY', True, False, clone=clone, checks=checks)
        except Exception:
            # No deletion/replay; independent bounded preservation observation.
            if baseline is not None:
                try:
                    with self.lib.budget(120, independent=True):
                        preserved = all(canonical_projection(projection(self.lib.inspect(cid))) == canonical_projection(value)
                                        for cid, value in baseline.items())
                except Exception:
                    preserved = False
            uncertain = self.store.uncertain(request_id) or not preserved
            return public_result(job, 'RECOVERY_REQUIRED' if uncertain else 'FAILED', preserved,
                                 uncertain, 'RESULT_UNCERTAIN' if uncertain else 'RESTORE_FAILED', clone, checks)
        finally:
            namespace['MUTATION_OBSERVER'] = prior


def legacy_completed_backup_verified(broker, store, config, library, request_id):
    """Read-only compatibility for authoritative legacy completion, never recovery.

    An existing ledger is always authoritative (including malformed/inflight).
    No synthetic ledger, claim deletion, result update, or Docker mutation.
    """
    try:
        require(not os.path.lexists(str(store.mutation_path(request_id))))
        require(store.claim_exists(request_id))
        claim = broker.parse_unique(broker.read_file(
            store.base / 'state' / (str(request_id) + '.json'), 0, 0o600, group=0))
        require(type(claim) is dict and set(claim) == {'schema', 'requestId'}
                and type(claim['schema']) is int and claim['schema'] == 1
                and type(claim['requestId']) is int and claim['requestId'] == request_id)
        result = broker.validate_result(broker.parse_unique(broker.read_file(
            store.base / 'results' / (str(request_id) + '.json'), 0, 0o640, group=store.app_gid)), request_id)
        require(result['status'] == 'COMPLETED' and result['stage'] == 'DONE'
                and result['originalStateRestored'] is True and result['errorCode'] is None
                and result['artifactId'] == 'nexus-backup-' + str(request_id))
        baseline = broker.parse_unique(broker.read_file(
            store.base / 'state' / (str(request_id) + '.baseline.json'), 0, 0o600, group=0))
        require(type(baseline) is dict and set(baseline) == {
            'schema', 'requestId', 'containerId', 'imageId', 'projectionSha256',
            'configSha256', 'originalRunning'})
        require(type(baseline['schema']) is int and baseline['schema'] == 1
                and type(baseline['requestId']) is int and baseline['requestId'] == request_id
                and baseline['originalRunning'] is True and baseline['imageId'] == config['imageId'])
        require(all(type(baseline[key]) is str and re.fullmatch(r'[0-9a-f]{64}', baseline[key])
                    for key in ('containerId', 'projectionSha256', 'configSha256')))
        # Full immutable archive verification is bounded and remains mandatory.
        with library.budget(library.WORK_SECONDS):
            root, meta = library.verify_artifact(str(Path(config['backupRoot']) / result['artifactId']))
            require(library.digest(root / 'manifest.json') == result['manifestSha256'])
            require(meta['sourceContainerId'] == baseline['containerId']
                    and meta['sourceName'] == config['containerName']
                    and meta['sourceVolume'] == config['volume']
                    and meta['sourceKeyPath'] == config['keyFile']
                    and meta['imageId'] == baseline['imageId'] and meta['originalRunning'] is True
                    and broker.fingerprint(meta['originalProjection']) == baseline['projectionSha256'])
            completion = broker.parse_unique(broker.read_file(root / 'backup.result.json', 0, 0o600, group=0))
            require(type(completion) is dict and set(completion) == {
                'status', 'containerId', 'originalRunning', 'manifestSha256', 'operationalRestoreVerified'})
            require(completion['status'] == 'LOCAL_NEXUS_BACKUP_COMPLETE_ORIGINAL_STATE_RESTORED'
                    and completion['containerId'] == baseline['containerId']
                    and completion['originalRunning'] is True
                    and completion['manifestSha256'] == result['manifestSha256']
                    and completion['operationalRestoreVerified'] is False)
            args = SimpleNamespace(container_id=baseline['containerId'], container_name=config['containerName'],
                image_id=config['imageId'], volume=config['volume'], key_file=config['keyFile'],
                heap=config['heap'], all_storage_in_data_volume=True)
            current, _, _, _ = library.source_contract(args)
            require(current['State']['Running'] is True
                    and library.projection(current) == meta['originalProjection'])
            require(library.digest(Path(config['keyFile'])) == library.digest(root / 'keys.json'))
            library.wait_health(baseline['containerId'])
        return True
    except Exception:
        return False


def run_queue(broker, backup_store, config, restore_config, library):
    store = RestoreStore(broker, backup_store)
    for request_id, check_id in store.checks():
        try:
            check = backup_store.read_inbox(store.check_path('inbox', request_id, check_id))
            require(type(check) is dict and set(check) == {'schema', 'requestId', 'checkId', 'action',
                'backupRequestId', 'manifestSha256'} and type(check['checkId']) is int and check['checkId'] == check_id)
            validate_job(dict({key: value for key, value in check.items() if key not in ('action', 'checkId')},
                              action='RESTORE'), request_id)
            require(check['action'] in ('CHECK_RESTORE_FAILURE', 'CHECK_EXISTING_CLONE_READINESS'))
        except Exception:
            return True  # Malformed action never reaches either check loader.
        if check['action'] == 'CHECK_EXISTING_CLONE_READINESS':
            processed = process_readiness_check(broker, store, config, restore_config, library, request_id, check_id)
        else:
            processed = process_failure_check(broker, store, config, library, request_id, check_id)
        if processed:
            return True
    # Missing legacy ledger or an unresolved operation holds across restart.
    for request_id in store.ids('state'):
        try:
            job = validate_job(store.read('state', request_id), request_id)
            previous = store.read('results', request_id)
            done = terminal_releases(previous, job)
            if done and not store.uncertain(request_id):
                continue
        except Exception:
            pass
        try:
            job = validate_job(store.read('state', request_id), request_id)
            try:
                held = held_result(job, store.read('results', request_id))
            except Exception:
                held = public_result(job, 'RECOVERY_REQUIRED', error='RESULT_UNCERTAIN')
            store.publish(held)
        except Exception:
            pass
        return True
    if broker.shutdown_requested() or not restore_config['enabled']:
        return False
    pending = [request_id for request_id in store.ids('inbox') if not store.exists('state', request_id)]
    if not pending:
        return False
    # Existing backup uncertainty takes precedence; never change original recovery.
    for request_id in backup_store.ids('state'):
        try:
            old = backup_store.get_result(request_id)
            if broker.releases_maintenance(old) and (not backup_store.mutation_uncertain(request_id)
                    or legacy_completed_backup_verified(broker, backup_store, config, library, request_id)):
                continue
        except Exception:
            pass
        return False
    for request_id in pending:
        if not store.exists('state', request_id):
            job = store.job(request_id)
            store.initialize(request_id, job)
            if broker.shutdown_requested():
                return True  # Empty preclaim ledger is safely adoptable next start.
            backup_store.write(store.path('state', request_id), job, exclusive=True)
            store.publish(public_result(job, 'RUNNING', error=None))
            store.publish(RestoreExecutor(broker, store, config, restore_config, library).run(job))
            return True
    return False
