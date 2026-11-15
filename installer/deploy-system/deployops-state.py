#!/usr/bin/env python3
"""Capture/restore Deploy TLS and immutable agent transfer state while the app is stopped."""
from __future__ import print_function
import argparse
import grp
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import pwd
import re
import stat
import subprocess
import sys
import tarfile
import tempfile

SERVICE = "deploy-system"


def regular_path(path, directory=False):
    path = Path(path).absolute()
    for part in (path,) + tuple(path.parents):
        if part.is_symlink():
            raise ValueError("State paths must not contain symbolic links")
    if path.exists() and not (path.is_dir() if directory else path.is_file()):
        raise ValueError("State input type is invalid")
    return path


def roots(home, etc=Path("/etc/deploy-system")):
    home = regular_path(home, directory=True)
    return {"tls": Path(etc) / "tls", "tls-health.json": Path(etc) / "tls-health.json",
            "agent-data": home / "data", "agent-artifacts": home / "data/agent-artifacts"}


def identity(path):
    info = path.lstat()
    if not (stat.S_ISDIR(info.st_mode) or stat.S_ISREG(info.st_mode)) or stat.S_ISLNK(info.st_mode):
        raise ValueError("Only regular files/directories may be backed up")
    if info.st_mode & 0o022 or info.st_mode & 0o7000:
        raise ValueError("State permissions are not controlled")
    return (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_uid, info.st_gid, stat.S_IMODE(info.st_mode))


def snapshot(archive, home, etc=Path("/etc/deploy-system")):
    archive = regular_path(archive)
    if archive.exists():
        raise ValueError("Existing state archive is preserved")
    mapping = roots(home, etc)
    entries = []
    for name, path in sorted(mapping.items()):
        regular_path(path, directory=name != "tls-health.json")
        if not path.exists():
            continue
        paths = [path] + sorted(path.rglob("*")) if path.is_dir() and name != "agent-data" else [path]
        for source in paths:
            name_in_archive = name if source == path else name + "/" + source.relative_to(path).as_posix()
            if name in ("tls", "tls-health.json") and source.lstat().st_uid != 0:
                raise ValueError("TLS backup state must be root-owned")
            entries.append((name_in_archive, source, identity(source)))
    descriptor = os.open(str(archive), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(descriptor, "wb") as output:
            with tarfile.open(fileobj=output, mode="w:gz", compresslevel=1) as target:
                for name, source, before in entries:
                    if identity(source) != before:
                        raise ValueError("State changed before capture")
                    metadata = target.gettarinfo(str(source), arcname=name)
                    if source.is_file():
                        with source.open("rb") as stream:
                            target.addfile(metadata, stream)
                    else:
                        target.addfile(metadata)
                    if identity(source) != before:
                        raise ValueError("State changed during capture")
            output.flush()
            os.fsync(output.fileno())
    except Exception:
        # Partial archive is retained under a failure-specific name, never advertised as a backup.
        if archive.exists():
            os.replace(str(archive), str(archive) + ".FAILED")
        raise
    return {"schema": 1, "stateEntries": len(entries), "includes": [name for name, path in mapping.items() if path.exists()]}


def destination(name, mapping):
    value = PurePosixPath(name)
    if not name or name.startswith("/") or "\\" in name or any(not re.match(r"^[A-Za-z0-9_.-]+$", part) or part in (".", "..") for part in name.split("/")):
        raise ValueError("Unsafe state archive path")
    base = value.parts[0]
    if base not in mapping or (base in ("tls-health.json", "agent-data") and len(value.parts) != 1):
        raise ValueError("Unexpected state archive member")
    return mapping[base].joinpath(*value.parts[1:])


def restore(archive, home, etc=Path("/etc/deploy-system")):
    archive = regular_path(archive)
    if archive.stat().st_uid != 0 or stat.S_IMODE(archive.stat().st_mode) & 0o077:
        raise ValueError("State archive must be root-owned and private")
    mapping = roots(home, etc)
    with tarfile.open(str(archive), "r:gz") as source:
        members = source.getmembers()
        names = set()
        planned = []
        for member in members:
            target = destination(member.name, mapping)
            if member.name in names or not (member.isfile() or member.isdir()) or member.issym() or member.islnk():
                raise ValueError("Duplicate/link/special state archive member refused")
            names.add(member.name)
            if member.name == "tls-health.json" and not member.isfile():
                raise ValueError("TLS health settings must be a regular file")
            if member.name in ("tls", "agent-artifacts", "agent-data") and not member.isdir():
                raise ValueError("State root must be a directory")
            if member.mode & 0o7022:
                raise ValueError("Unsafe state archive permissions")
            # Names map to the installed host accounts, including when numeric IDs changed on migration.
            uid = pwd.getpwnam(member.uname).pw_uid if member.uname else member.uid
            gid = grp.getgrnam(member.gname).gr_gid if member.gname else member.gid
            if member.name.split("/")[0] in ("tls", "tls-health.json") and uid != 0:
                raise ValueError("TLS state must remain root-owned")
            regular_path(target, directory=member.isdir())
            if member.isfile():
                count = 0
                with source.extractfile(member) as stream:
                    for block in iter(lambda: stream.read(1024 * 1024), b""):
                        count += len(block)
                if count != member.size:
                    raise ValueError("Truncated state member")
            planned.append((member, target, uid, gid))
        while source.fileobj.read(1024 * 1024):
            pass  # Consume the gzip footer before the first destination change.
        # Legacy archives contain TLS children but not /etc/deploy-system itself.
        # Match install-tls-keystore.py's root:application-group 0750 contract;
        # mkdir under the maintenance umask (077) would prevent Java traversal.
        tls_roots = [(member, gid) for member, target, uid, gid in planned if member.name == "tls"]
        if tls_roots:
            tls_parent = regular_path(etc, directory=True)
            for ancestor in (tls_parent,) + tuple(tls_parent.parents):
                if not ancestor.exists():
                    if ancestor == tls_parent:
                        continue
                    raise ValueError("TLS parent ancestry must already exist")
                info = ancestor.lstat()
                sticky = info.st_uid == 0 and bool(info.st_mode & stat.S_ISVTX)
                if not stat.S_ISDIR(info.st_mode) or info.st_uid != 0 or (info.st_mode & 0o022 and not sticky):
                    raise ValueError("TLS parent ancestry must be root-controlled")
            tls_parent.mkdir(mode=0o750, exist_ok=True)
            os.chown(str(tls_parent), 0, tls_roots[0][1])
            os.chmod(str(tls_parent), 0o750)
        # Validate all names, types and owners before changing the destination.
        for member, target, uid, gid in planned:
            target.parent.mkdir(parents=True, exist_ok=True)
            regular_path(target.parent, directory=True)
            if member.isdir():
                target.mkdir(exist_ok=True)
                os.chown(str(target), uid, gid)
                os.chmod(str(target), member.mode)
                continue
            descriptor, temporary = tempfile.mkstemp(prefix=".restore-state-", dir=str(target.parent))
            try:
                with os.fdopen(descriptor, "wb") as output, source.extractfile(member) as stream:
                    while True:
                        block = stream.read(1024 * 1024)
                        if not block:
                            break
                        output.write(block)
                    output.flush()
                    os.fsync(output.fileno())
                    os.fchown(output.fileno(), uid, gid)
                    os.fchmod(output.fileno(), member.mode)
                os.replace(temporary, str(target))
            finally:
                if os.path.exists(temporary):
                    os.unlink(temporary)
    return {"schema": 1, "restoredEntries": len(planned)}


def require_stopped_service():
    status = subprocess.run(["systemctl", "show", "--property=ActiveState", "--value", SERVICE],
                            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=10)
    if status.returncode != 0 or status.stdout.strip() not in (b"inactive", b"failed"):
        raise ValueError("Stop Deploy and confirm its inactive/failed state before state capture/restore")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["capture", "restore"])
    parser.add_argument("--archive", required=True)
    parser.add_argument("--app-home", default="/opt/deploy-system")
    args = parser.parse_args()
    if os.geteuid() != 0:
        raise ValueError("Run state backup/restore as root")
    require_stopped_service()
    result = snapshot(args.archive, args.app_home) if args.mode == "capture" else restore(args.archive, args.app_home)
    print(json.dumps(result, separators=(",", ":")))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        message = str(error) if isinstance(error, ValueError) else type(error).__name__
        print("Deploy state operation failed: " + message, file=sys.stderr)
        sys.exit(1)
