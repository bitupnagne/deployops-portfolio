package com.portfolio.deployops.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddedYamlBuildPhaseTest {
    @Test
    void privateInstallationConfigIsCopiedOnlyAfterTests() {
        // prepare-package includes production credentials only after this lifecycle phase.
        // A previous embedded build requires clean before tests; do not load its configuration.
        assertThat(getClass().getClassLoader().getResource("application-install.yml"))
                .as("Run a clean build: private installation YAML must be absent during tests")
                .isNull();
        assertThat(getClass().getClassLoader().getResource("deploy-maintenance/v1/deploy-system/maintenance-payload.json"))
                .as("Maintenance payload is copied during prepare-package, after tests")
                .isNull();
    }
}
