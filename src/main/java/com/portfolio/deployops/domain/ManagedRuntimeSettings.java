package com.portfolio.deployops.domain;

/** 승인 계획에 고정하는 값. 암호문과 환경 변수 내용은 포함하지 않는다. */
public record ManagedRuntimeSettings(Long componentId, String mode, Long revisionId,
                                     String recordFingerprint, boolean persistentLogs, int tmpMiB, @tools.jackson.databind.annotation.JsonDeserialize(using=com.portfolio.deployops.domain.StrictStopGraceDeserializer.class) Integer stopGraceSeconds) {
    public ManagedRuntimeSettings {
        stopGraceSeconds = StopGrace.seconds(stopGraceSeconds);
    }
    public ManagedRuntimeSettings(Long componentId, String mode, Long revisionId, String recordFingerprint,
                                  boolean persistentLogs, int tmpMiB) {
        this(componentId, mode, revisionId, recordFingerprint, persistentLogs, tmpMiB, null);
    }
}
