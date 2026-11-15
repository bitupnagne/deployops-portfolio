#!/usr/libexec/platform-python
"""Rocky 8 curl variant replacement; audit and apply the same DNF transaction.

API contracts: dnf.readthedocs.io/en/latest/{api_base,api_transaction,api_callback}.html
No repository configuration or plugins are loaded. --plan-only never applies RPMs.
"""
import argparse
import hashlib
import json
import os
import platform
import re
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = 'offline-curl-variants'
VARIANTS = {'curl-minimal': 'curl', 'libcurl-minimal': 'libcurl'}


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def evr(package):
    return '{}:{}-{}'.format(package.epoch or '0', package.version, package.release)


def identity(package):
    return package.name, evr(package), package.arch


def load_lock(directory):
    lock = {}
    for line in (directory / 'PACKAGES.lock').read_text().splitlines():
        fields = line.split('|')
        require(len(fields) == 5, 'Invalid RPM lock row')
        name, version, arch, sha, filename = fields
        require(re.match(r'^[A-Za-z0-9._+-]+$', name) and name not in lock,
                'Invalid or duplicate locked package')
        require(re.match(r'^\d+:[A-Za-z0-9.+~_-]+$', version) and arch in ('x86_64', 'noarch'),
                'Invalid locked EVR/architecture')
        require(re.match(r'^[0-9a-f]{64}$', sha) and re.match(r'^[A-Za-z0-9._+~-]+\.rpm$', filename),
                'Invalid locked RPM hash/path')
        lock[name] = (version, arch, sha, filename)
    for name in ('curl', 'libcurl'):
        require(name in lock and lock[name][1] == 'x86_64', 'Locked native curl pair required')
    require(lock['curl'][0] == lock['libcurl'][0], 'Locked curl/libcurl EVR must match')
    return lock


def audit_transaction(transaction, installed, lock, compare):
    incoming = list(transaction.install_set)
    outgoing = list(transaction.remove_set)
    by_name_arch = {}
    for package in incoming:
        require(package.name in lock and package.reponame == REPO, 'Unapproved transaction install source')
        row = lock[package.name]
        require((evr(package), package.arch) == row[:2], 'Transaction install differs from locked EVR/arch')
        key = (package.name, package.arch)
        require(key not in by_name_arch, 'Multiple incoming package versions')
        by_name_arch[key] = package
    minimal = [package for package in installed if package.name in VARIANTS]
    require(minimal, 'No installed curl minimal variant to replace')
    require(len({package.name for package in minimal}) == len(minimal)
            and all(package.arch == 'x86_64' for package in minimal), 'Unsupported minimal variant multiplicity/arch')
    removed = {identity(package) for package in outgoing}
    require(all(identity(package) in removed for package in minimal), 'Variant replacement does not remove exact installed variants')
    allowed_minimal = {identity(package) for package in minimal}
    for package in outgoing:
        if identity(package) in allowed_minimal:
            full = VARIANTS[package.name]
            require(compare(lock[full][0], evr(package)) >= 0, 'Minimal variant downgrade is forbidden')
            continue
        replacement = by_name_arch.get((package.name, package.arch))
        require(replacement is not None and compare(evr(replacement), evr(package)) >= 0,
                'Unrelated removal, architecture change or downgrade is forbidden')
    for package in incoming:
        for previous in installed:
            if (previous.name, previous.arch) == (package.name, package.arch):
                require(compare(evr(package), evr(previous)) >= 0, 'Installed package downgrade is forbidden')
    remaining = {identity(package) for package in installed if identity(package) not in removed}
    remaining.update(identity(package) for package in incoming)
    for full in ('curl', 'libcurl'):
        require((full, lock[full][0], lock[full][1]) in remaining, 'Result must contain exact locked full curl pair')
    return incoming, outgoing


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository', required=True)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--apply', action='store_true')
    mode.add_argument('--plan-only', action='store_true')
    args = parser.parse_args()
    require(os.geteuid() == 0 and platform.machine() == 'x86_64', 'Native Rocky root context required')
    directory = Path(args.repository)
    require(re.match(r'^/[A-Za-z0-9._/-]+$', args.repository) and directory.is_dir()
            and not directory.is_symlink(), 'Safe local repository directory required')
    directory = directory.resolve()
    lock = load_lock(directory)
    import dnf
    import dnf.callback
    import rpm

    def compare(left, right):
        def label(value):
            epoch, rest = value.split(':', 1)
            version, release = rest.rsplit('-', 1)
            return epoch, version, release
        return rpm.labelCompare(label(left), label(right))

    class Errors(dnf.callback.TransactionProgress):
        failed = False
        tail = ''

        def error(self, message):
            self.failed = True
            print('RPM transaction error: {}'.format(message), file=sys.stderr)

        def scriptout(self, messages):
            if messages:
                text = messages.decode('utf-8', 'replace') if isinstance(messages, bytes) else str(messages)
                combined = self.tail + text
                if re.search(r'scriptlet (?:failed|failure)|Error in .*scriptlet|error: %[a-z]+|Invalid cross-device link|libsemanage\..*Error|semodule.*failed', combined, re.I):
                    self.failed = True
                self.tail = combined[-4096:]
                print(text, end='' if text.endswith('\n') else '\n')

    with tempfile.TemporaryDirectory(prefix='target-curl-variants-') as cache, dnf.Base() as base:
        base.conf.cachedir = cache
        if args.plan_only:
            # Planning reads the existing RPM keyring only; unavailable keys fail
            # signature validation. DNF reason/history/cache state is disposable.
            base.conf.persistdir = os.path.join(cache, 'persist')
            base.conf.logdir = os.path.join(cache, 'logs')
            base.conf.history_record = False
            os.makedirs(base.conf.persistdir, mode=0o700)
            os.makedirs(base.conf.logdir, mode=0o700)
        base.conf.keepcache = True
        base.conf.plugins = False
        authenticate = os.environ.get("OFFLINE_VERIFY_AUTHENTICITY", "0") == "1"
        base.conf.gpgcheck = authenticate
        base.conf.localpkg_gpgcheck = authenticate
        base.conf.install_weak_deps = False
        base.conf.clean_requirements_on_remove = False
        base.conf.best = True
        base.conf.substitutions['releasever'] = '8.10'
        base.conf.substitutions['basearch'] = 'x86_64'
        repository = base.repos.add_new_repo(REPO, base.conf, baseurl=[directory.as_uri()])
        repository.gpgcheck = authenticate
        repository.skip_if_unavailable = False
        repository.module_hotfixes = True
        base.fill_sack()
        installed = list(base.sack.query().installed())
        minimal = [package for package in installed if package.name in VARIANTS]
        require(minimal, 'Minimal variants disappeared before planning')
        for package in minimal:
            base.remove('{}-{}.{}'.format(package.name, evr(package), package.arch))
        for name in ('curl', 'libcurl'):
            version, arch = lock[name][:2]
            base.install('{}-{}.{}'.format(name, version, arch), reponame=REPO, strict=True)
        base.resolve(allow_erasing=False)
        incoming, outgoing = audit_transaction(base.transaction, installed, lock, compare)
        for package in incoming:
            path = Path(package.localPkg())
            expected = directory / lock[package.name][3]
            require(path == expected and path.is_file() and not path.is_symlink(), 'RPM payload path differs from locked file')
            if authenticate:
                with path.open('rb') as stream:
                    digest = hashlib.sha256()
                    for chunk in iter(lambda: stream.read(1048576), b''):
                        digest.update(chunk)
                require(digest.hexdigest() == lock[package.name][2], 'RPM payload SHA differs from lock')
                result, message = base.package_signature_check(package)
                require(result == 0, 'RPM signature rejected: {}'.format(message))

        print(json.dumps({'mode': 'apply' if args.apply else 'plan-only',
                          'install': sorted(identity(package) for package in incoming),
                          'remove': sorted(identity(package) for package in outgoing)}, sort_keys=True))
        if args.plan_only:
            return
        display = Errors()
        base.do_transaction(display=display)
        require(not display.failed, 'RPM callback error; variant replacement incomplete')
    for full in ('curl', 'libcurl'):
        actual = subprocess.check_output(['rpm', '-q', '--qf', '%{EPOCHNUM}:%{VERSION}-%{RELEASE}|%{ARCH}', full]).decode()
        require(actual == '|'.join(lock[full][:2]), 'Installed full curl pair differs from lock')
    for minimal in VARIANTS:
        require(subprocess.call(['rpm', '-q', minimal], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL) == 1,
                'Minimal curl variant remains or RPM query failed')
    print('TARGET_CURL_VARIANTS_LOCKED_TRANSACTION_PASS')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('Target curl variant replacement refused: {}'.format(error), file=sys.stderr)
        sys.exit(1)
