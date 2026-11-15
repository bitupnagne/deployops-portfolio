package com.portfolio.deployops.domain;

public enum ProjectPermissionType {
    PROJECT_VIEW,
    DEPLOY_REQUEST,
    MR_REVIEW,
    MR_APPROVE,
    MR_MERGE,
    TEST_APPROVE,
    PROD_APPROVE,
    TEST_EXECUTE,
    PROD_EXECUTE,
    ROLLBACK_EXECUTE,
    CI_VARIABLE_MANAGE,
    RUNTIME_CONFIG_MANAGE,
    RUNTIME_LOG_READ
}
