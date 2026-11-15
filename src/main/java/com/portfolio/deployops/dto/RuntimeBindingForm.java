package com.portfolio.deployops.dto;

public record RuntimeBindingForm(Long componentId, String mode, Long revisionId,
                                 boolean persistentLogs, int tmpMiB, String reason, @tools.jackson.databind.annotation.JsonDeserialize(using=com.portfolio.deployops.domain.StrictStopGraceDeserializer.class) Integer stopGraceSeconds) {
    public RuntimeBindingForm(Long componentId, String mode, Long revisionId, boolean persistentLogs,
                              int tmpMiB, String reason) {
        this(componentId, mode, revisionId, persistentLogs, tmpMiB, reason, null);
    }
}
