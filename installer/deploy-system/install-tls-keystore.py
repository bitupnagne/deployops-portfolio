#!/usr/bin/env python3
"""Install an externally supplied PKCS12 keystore referenced by embedded company JSON YAML."""
from __future__ import print_function

import argparse
import grp
import hashlib
import json
import os
import re
import ssl
import stat
import sys
import tempfile
import urllib.parse
import urllib.request
import zipfile

ENTRY = "BOOT-INF/classes/application-install.yml"
DESTINATION_ROOT = "/etc/deploy-system/tls"
HEALTH_SETTINGS = "/etc/deploy-system/tls-health.json"
LIMIT = 16 * 1024 * 1024


class Refused(Exception):
    pass


def controlled(path, regular=False, private=False):
    path = os.path.abspath(path)
    info = os.lstat(path)
    if stat.S_ISLNK(info.st_mode) or info.st_uid != 0:
        raise Refused("TLS input/output must be root-controlled without links")
    if regular and not stat.S_ISREG(info.st_mode):
        raise Refused("TLS input/output must be a regular file")
    if private and info.st_mode & 0o077:
        raise Refused("Keystore source must have root-only permissions (0600 or 0400)")
    if info.st_mode & 0o022:
        raise Refused("TLS input/output is writable by an untrusted account")
    parent = os.path.dirname(path)
    while True:
        ancestor = os.lstat(parent)
        sticky = ancestor.st_uid == 0 and ancestor.st_mode & stat.S_ISVTX
        if not stat.S_ISDIR(ancestor.st_mode) or stat.S_ISLNK(ancestor.st_mode) or ancestor.st_uid != 0 or (ancestor.st_mode & 0o022 and not sticky):
            raise Refused("TLS path ancestry is not root-controlled")
        next_parent = os.path.dirname(parent)
        if next_parent == parent:
            break
        parent = next_parent
    return path


def read_configuration(jar_path):
    # Installed application JARs are owned by the application account, mode0640.
    # They are trusted inputs of the existing root maintenance contract.
    jar_info = os.lstat(jar_path)
    if not stat.S_ISREG(jar_info.st_mode) or stat.S_ISLNK(jar_info.st_mode) or jar_info.st_mode & 0o022:
        raise Refused("Company JAR must be a regular non-writable-by-others file")
    cursor = os.path.dirname(os.path.abspath(jar_path))
    while True:
        info = os.lstat(cursor)
        sticky = info.st_uid == 0 and info.st_mode & stat.S_ISVTX
        if not stat.S_ISDIR(info.st_mode) or stat.S_ISLNK(info.st_mode) or info.st_uid not in (0, jar_info.st_uid) or (info.st_mode & 0o022 and not sticky):
            raise Refused("Company JAR ancestry is not controlled")
        parent = os.path.dirname(cursor)
        if parent == cursor:
            break
        cursor = parent
    with zipfile.ZipFile(jar_path, "r") as archive:
        if archive.namelist().count(ENTRY) != 1:
            raise Refused("Company embedded configuration is missing or duplicated")
        info = archive.getinfo(ENTRY)
        if info.file_size > 1024 * 1024:
            raise Refused("Company embedded configuration exceeds the supported bound")
        data = json.loads(archive.read(ENTRY).decode("utf-8-sig"))
    ssl_settings = data.get("server", {}).get("ssl", {})
    if not isinstance(ssl_settings, dict):
        raise Refused("server.ssl must be an object in company JSON-compatible YAML")
    return ssl_settings


def digest_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def ensure_directory(path, group):
    if os.path.lexists(path):
        controlled(path)
        if not stat.S_ISDIR(os.lstat(path).st_mode):
            raise Refused("TLS destination parent must be a directory")
    else:
        parent = os.path.dirname(path)
        controlled(parent)
        os.mkdir(path, 0o750)
    os.chown(path, 0, group)
    os.chmod(path, 0o750)


def install_keystore(jar_path, source, group, destination_root=DESTINATION_ROOT, check_only=False):
    settings = read_configuration(jar_path)
    enabled = settings.get("enabled", False)
    if not isinstance(enabled, bool):
        raise Refused("server.ssl.enabled must be a JSON boolean")
    if not enabled:
        if source:
            raise Refused("A TLS keystore source was supplied but embedded server.ssl.enabled is false")
        return {"mode": "DISABLED", "installed": False}
    store = settings.get("key-store")
    if not isinstance(store, str) or not store or any(c in store for c in ("\0", "\n", "\r", "${")):
        raise Refused("Configure server.ssl.key-store in the company embedded YAML")
    if store.startswith("classpath:"):
        if source:
            raise Refused("External source is incompatible with a classpath keystore")
        entry = store[len("classpath:"):].lstrip("/")
        if not entry or any(part in ("", ".", "..") for part in entry.split("/")):
            raise Refused("Classpath keystore path is invalid")
        with zipfile.ZipFile(jar_path, "r") as archive:
            if archive.namelist().count("BOOT-INF/classes/" + entry) != 1:
                raise Refused("The configured classpath keystore is absent or duplicated in the company JAR")
        return {"mode": "CLASSPATH", "installed": False}
    destination_root = os.path.abspath(destination_root)
    if not store.startswith("file:"):
        raise Refused("Use file:/etc/deploy-system/tls/NAME.p12 or an existing classpath keystore")
    target = store[5:]
    if not target.startswith("/") or os.path.normpath(target) != target or "\\" in target:
        raise Refused("TLS keystore file URI must contain a canonical absolute path")
    if os.path.dirname(target) != destination_root or not re.match(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,120}\.(p12|pfx)$", os.path.basename(target)):
        raise Refused("External keystore must be /etc/deploy-system/tls/NAME.p12 or NAME.pfx")
    if settings.get("key-store-type", "PKCS12").upper() != "PKCS12":
        raise Refused("External TLS bootstrap requires PKCS12")
    if not source:
        if not os.path.lexists(target):
            raise Refused("External TLS keystore is missing; provide DEPLOY_TLS_KEYSTORE_FILE before installing Deploy")
        controlled(target, regular=True)
        info = os.stat(target)
        if (group is not None and info.st_gid != group) or stat.S_IMODE(info.st_mode) != 0o640 or info.st_size < 1 or info.st_size > LIMIT:
            raise Refused("Existing TLS keystore must be root:application-group 0640 and nonempty")
        if not check_only:
            ensure_directory(os.path.dirname(destination_root), group)
            ensure_directory(destination_root, group)
        return {"mode": "EXTERNAL_FILE", "installed": False, "preserved": True}
    source = controlled(source, regular=True, private=True)
    size = os.stat(source).st_size
    if not 1 <= size <= LIMIT:
        raise Refused("Keystore source must be nonempty and within 16MiB")
    expected = digest_file(source)
    if os.path.lexists(target):
        controlled(target, regular=True)
        if digest_file(target) != expected:
            raise Refused("Existing TLS keystore differs; preserve it and use the documented certificate rotation procedure")
        if not check_only:
            ensure_directory(os.path.dirname(destination_root), group)
            ensure_directory(destination_root, group)
            os.chown(target, 0, group)
            os.chmod(target, 0o640)
        return {"mode": "EXTERNAL_FILE", "installed": False, "preserved": True}
    if check_only:
        return {"mode": "EXTERNAL_FILE", "installed": False, "preserved": False, "sourceReady": True}
    # Only these two known application parents are changed. /etc is never reowned.
    ensure_directory(os.path.dirname(destination_root), group)
    ensure_directory(destination_root, group)
    descriptor, temporary = tempfile.mkstemp(prefix=".keystore-", dir=destination_root)
    try:
        with os.fdopen(descriptor, "wb") as output:
            os.fchmod(output.fileno(), 0o640)
            os.fchown(output.fileno(), 0, group)
            with open(source, "rb") as incoming:
                for block in iter(lambda: incoming.read(1024 * 1024), b""):
                    output.write(block)
            output.flush()
            os.fsync(output.fileno())
        if digest_file(temporary) != expected:
            raise Refused("Keystore source changed during transfer")
        # Atomic no-replace publication: another installer cannot overwrite a keystore.
        os.link(temporary, target)
        os.unlink(temporary)
        directory = os.open(destination_root, os.O_RDONLY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
        return {"mode": "EXTERNAL_FILE", "installed": True, "preserved": False}
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def validate_health(jar_path, health_url, ca_file):
    settings = read_configuration(jar_path)
    enabled = settings.get("enabled", False)
    if not isinstance(enabled, bool):
        raise Refused("server.ssl.enabled must be a JSON boolean")
    if enabled and not health_url:
        raise Refused("TLS is enabled; set DEPLOY_HEALTHCHECK_URL to the company HTTPS /health address before installation")
    if health_url:
        if any(char.isspace() or ord(char) < 32 for char in health_url):
            raise Refused("Health URL must not contain whitespace")
        parsed = urllib.parse.urlsplit(health_url)
        if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path != "/health":
            raise Refused("Use a credential-free company HTTP(S) /health URL")
        if enabled and parsed.scheme != "https":
            raise Refused("TLS-enabled Deploy requires an HTTPS health URL; HTTP fallback is refused")
    if ca_file:
        controlled(ca_file, regular=True)
        if not health_url or urllib.parse.urlsplit(health_url).scheme != "https":
            raise Refused("TLS CA file requires an HTTPS health URL")
    return health_url or ""


def configure_health(jar_path, health_url, ca_file, group, settings_path=HEALTH_SETTINGS, tls_root=DESTINATION_ROOT):
    health_url = validate_health(jar_path, health_url, ca_file)
    settings = read_configuration(jar_path)
    parent = os.path.dirname(settings_path)
    ensure_directory(parent, group)
    installed_ca = None
    if ca_file:
        ensure_directory(tls_root, group)
        expected = digest_file(ca_file)
        installed_ca = os.path.join(tls_root, "health-ca-" + expected[:16] + ".pem")
        if os.path.lexists(installed_ca):
            controlled(installed_ca, regular=True)
            if digest_file(installed_ca) != expected:
                raise Refused("Existing health CA differs")
        else:
            descriptor, temporary = tempfile.mkstemp(prefix=".ca-", dir=tls_root)
            try:
                with os.fdopen(descriptor, "wb") as output, open(ca_file, "rb") as source:
                    os.fchmod(output.fileno(), 0o640)
                    os.fchown(output.fileno(), 0, group)
                    for block in iter(lambda: source.read(1024 * 1024), b""):
                        output.write(block)
                    output.flush()
                    os.fsync(output.fileno())
                if digest_file(temporary) != expected:
                    raise Refused("Health CA changed during copy")
                os.link(temporary, installed_ca)
            finally:
                if os.path.exists(temporary):
                    os.unlink(temporary)
    value = {"schema": 1, "embeddedTls": settings.get("enabled", False), "url": health_url or None, "caFile": installed_ca}
    if os.path.lexists(settings_path):
        controlled(settings_path, regular=True, private=True)
    descriptor, temporary = tempfile.mkstemp(prefix=".health-", dir=parent)
    try:
        with os.fdopen(descriptor, "w") as output:
            os.fchmod(output.fileno(), 0o600)
            json.dump(value, output, separators=(",", ":"))
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, settings_path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    return value


def health_settings(jar_path, port, settings_path=HEALTH_SETTINGS):
    enabled = read_configuration(jar_path).get("enabled", False)
    if os.path.lexists(settings_path):
        controlled(settings_path, regular=True, private=True)
        with open(settings_path, "r") as stream:
            value = json.load(stream)
        if value.get("schema") != 1 or value.get("embeddedTls") is not enabled:
            raise Refused("TLS health settings differ from this JAR; run the TLS setup before patching")
        url = value.get("url")
        ca_file = value.get("caFile")
        validate_health(jar_path, url, ca_file)
    else:
        if enabled:
            raise Refused("TLS health settings are missing; configure the company HTTPS URL and CA before patch/restore")
        url, ca_file = None, None
    return url or "http://127.0.0.1:{}/health".format(port), ca_file


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        raise Refused("Health redirects are refused")


def health_check(jar_path, port, settings_path=HEALTH_SETTINGS):
    url, ca_file = health_settings(jar_path, port, settings_path)
    context = ssl.create_default_context(cafile=ca_file)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=context), NoRedirect())
    with opener.open(url, timeout=5) as response:
        raw = response.read(16385)
    if len(raw) > 16384:
        raise Refused("Health body exceeds expected size")
    body = json.loads(raw.decode("utf-8"))
    if not isinstance(body, dict) or body.get("status") != "UP" or body.get("db") != "UP":
        raise Refused("Deploy application or database health is not UP")
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", required=True)
    parser.add_argument("--mode", choices=["install", "preflight", "validate-health", "check-health-body", "configure-health", "health-check"], default="install")
    parser.add_argument("--source")
    parser.add_argument("--app-group")
    parser.add_argument("--health-url")
    parser.add_argument("--ca-file")
    parser.add_argument("--port", type=int, default=9090)
    args = parser.parse_args()
    if os.geteuid() != 0:
        raise Refused("Run TLS bootstrap as root")
    if args.mode == "validate-health":
        print(validate_health(args.jar, args.health_url, args.ca_file))
    elif args.mode == "health-check":
        health_check(args.jar, args.port)
        print("Deploy application and database health are UP")
    elif args.mode == "check-health-body":
        raw = sys.stdin.buffer.read(16385)
        if len(raw) > 16384:
            raise Refused("Health body exceeds expected size")
        body = json.loads(raw.decode("utf-8"))
        if not isinstance(body, dict) or body.get("status") != "UP" or body.get("db") != "UP":
            raise Refused("Deploy application or database health is not UP")
    else:
        if args.mode != "preflight" and not args.app_group:
            raise Refused("Application group is required")
        group = grp.getgrnam(args.app_group).gr_gid if args.app_group else None
        if args.mode == "configure-health":
            configure_health(args.jar, args.health_url, args.ca_file, group)
            result = {"healthSettingsSaved": True}
        else:
            result = install_keystore(args.jar, args.source, group, check_only=args.mode == "preflight")
        print(json.dumps(result, separators=(",", ":")))


if __name__ == "__main__":
    try:
        main()
    except Refused as error:
        print("TLS bootstrap: " + str(error), file=sys.stderr)
        sys.exit(1)
    except Exception as error:
        # Never log private configuration, password, keystore bytes or exception arguments.
        print("TLS bootstrap failed ({})".format(type(error).__name__), file=sys.stderr)
        sys.exit(1)
