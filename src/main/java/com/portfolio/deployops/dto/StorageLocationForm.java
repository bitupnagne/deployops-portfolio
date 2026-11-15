package com.portfolio.deployops.dto;

public record StorageLocationForm(String storageKey, String kind, String rootPath,
    String mountTarget, String mountSource, String fsType, boolean enabled, String reason) { }
