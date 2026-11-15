package com.portfolio.deployops.dto;

import java.util.List;

public record StorageBindingForm(Long componentId, List<Mount> mounts, String reason) {
    public record Mount(String storageKey, String relativePath, String containerPath, boolean readOnly) { }
}
