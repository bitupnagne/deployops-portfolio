package com.portfolio.deployops.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.deployops.domain.User;
import java.util.Map;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class UserCredentialPersistenceContractTest {

    private final Configuration configuration = configuration();

    @Test
    void staleProfileOrGitlabMappingSaveCannotOverwriteResetCredentials() {
        User stale = User.builder().id(7L).password("pre-reset-hash").forcePasswordChange(false).build();
        BoundSql sql = statement("update", stale);

        assertThat(sql.getSql()).doesNotContain("SET password", "password_changed_at", "force_password_change");
        assertThat(sql.getParameterMappings()).extracting(p -> p.getProperty())
            .doesNotContain("password", "passwordChangedAt", "forcePasswordChange");
    }

    @Test
    void teamMembershipWriteCannotSaveStaleCredentialsOrActiveRoleFields() {
        BoundSql sql = statement("updateTeam", Map.of("id", 7L, "teamId", 2L));

        assertThat(sql.getSql()).contains("SET team_id").doesNotContain("password", "active", "role", "gitlab");
        assertThat(sql.getParameterMappings()).extracting(p -> p.getProperty())
            .containsExactly("teamId", "id");
    }

    @Test
    void dedicatedCredentialWriteChangesNoOtherProfileAndLockQueryUsesForUpdate() {
        BoundSql sql = statement("updatePassword", User.builder().id(7L).password("new-hash").build());

        assertThat(sql.getParameterMappings()).extracting(p -> p.getProperty())
            .containsExactly("password", "forcePasswordChange", "passwordChangedAt", "id");
        assertThat(statement("findByIdForUpdate", 7L).getSql()).endsWith("FOR UPDATE");
    }

    private Configuration configuration() {
        Configuration config = new Configuration();
        config.addMapper(UserRepository.class);
        return config;
    }

    private BoundSql statement(String method, Object argument) {
        return configuration.getMappedStatement(UserRepository.class.getName() + "." + method)
            .getBoundSql(argument);
    }
}
