package com.portfolio.deployops.domain;

/** Credentials are deliberately absent: the operating system mounts NAS before registration. */
public record ServerStorageLocation(Long serverId, String storageKey, String kind, String rootPath,
    String mountTarget, String mountSource, String fsType, boolean enabled) { }
