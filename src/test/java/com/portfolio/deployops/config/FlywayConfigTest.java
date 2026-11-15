package com.portfolio.deployops.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class FlywayConfigTest {

    private final FlywayConfig config = new FlywayConfig();

    @Test
    void defaultPathMigratesWithoutRepairingChecksums() {
        Flyway flyway = mock(Flyway.class);

        config.flywayMigrationStrategy(false, "", false, "", "").migrate(flyway);

        verify(flyway, never()).repair();
        verify(flyway).migrate();
    }

    @Test
    void repairRequiresTheExplicitOneTimeConfirmation() {
        Flyway flyway = mock(Flyway.class);

        assertThatThrownBy(() -> config.flywayMigrationStrategy(true, "wrong", false, "", "").migrate(flyway))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("일회성 확인 문구");

        verify(flyway, never()).repair();
        verify(flyway, never()).migrate();
    }

    @Test
    void explicitlyConfirmedRepairRunsBeforeMigration() {
        Flyway flyway = mock(Flyway.class);

        config.flywayMigrationStrategy(
            true,
            "I_ACKNOWLEDGE_FLYWAY_CHECKSUM_REWRITE", false, "", ""
        ).migrate(flyway);

        InOrder order = inOrder(flyway);
        order.verify(flyway).repair();
        order.verify(flyway).migrate();
    }
}
