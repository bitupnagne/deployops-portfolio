package com.portfolio.deployops.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import java.sql.Connection;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class PublicDemoMigrationBoundaryTest {
    @Test void wrongDatabaseCannotMigrateOrRepairEvenWithRepairConfirmation() throws Exception {
        Flyway flyway = mock(Flyway.class, RETURNS_DEEP_STUBS);
        Connection connection = mock(Connection.class);
        when(flyway.getConfiguration().getDataSource().getConnection()).thenReturn(connection);
        when(connection.getCatalog()).thenReturn("unrelated");
        var strategy = new FlywayConfig().flywayMigrationStrategy(true,
                "I_ACKNOWLEDGE_FLYWAY_CHECKSUM_REWRITE", true, "visitor", java.util.UUID.randomUUID().toString());
        assertThatThrownBy(() -> strategy.migrate(flyway)).isInstanceOf(IllegalStateException.class);
        verify(flyway, never()).migrate();
        verify(flyway, never()).repair();
        verify(connection).close();
    }

    @Test void missingCredentialsFailBeforeAnyDatabaseConnection() {
        Flyway flyway = mock(Flyway.class);
        var strategy = new FlywayConfig().flywayMigrationStrategy(false, "", true, "visitor", "");
        assertThatThrownBy(() -> strategy.migrate(flyway)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(flyway);
    }

    @Test void isolatedDatabaseMigratesWithoutRepair() throws Exception {
        Flyway flyway = mock(Flyway.class, RETURNS_DEEP_STUBS);
        Connection connection = mock(Connection.class);
        when(flyway.getConfiguration().getDataSource().getConnection()).thenReturn(connection);
        when(connection.getCatalog()).thenReturn("deployops_demo");
        new FlywayConfig().flywayMigrationStrategy(false, "", true, "visitor", java.util.UUID.randomUUID().toString()).migrate(flyway);
        verify(flyway).migrate();
        verify(flyway, never()).repair();
        verify(connection).close();
    }
}
