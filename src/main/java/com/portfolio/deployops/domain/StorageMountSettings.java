package com.portfolio.deployops.domain;

/** Resolved per-host mount contract, frozen with the approval snapshot. */
public record StorageMountSettings(Long serverId, Long componentId, String storageKey, String kind,
    String rootPath, String relativePath, String containerPath, boolean readOnly,
    String mountTarget, String mountSource, String fsType) { }
