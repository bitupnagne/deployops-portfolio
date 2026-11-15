package com.portfolio.deployops.service;

/**
 * Shared retention contract for image histories exposed by DeployOps and Nexus cleanup.
 * Any history in this recent window may be selected by an operator for rollback.
 */
public final class ImageHistoryRetentionPolicy {

    public static final int RECENT_ROLLBACK_HISTORY_LIMIT = 30;

    private ImageHistoryRetentionPolicy() {
    }
}
