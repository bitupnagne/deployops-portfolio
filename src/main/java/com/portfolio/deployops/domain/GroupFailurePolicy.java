package com.portfolio.deployops.domain;

/** A group stops on failure or restores the targets already changed by this execution. */
public enum GroupFailurePolicy { STOP, ROLLBACK }
