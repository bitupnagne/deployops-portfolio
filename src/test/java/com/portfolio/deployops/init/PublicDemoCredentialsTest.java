package com.portfolio.deployops.init;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.repository.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

class PublicDemoCredentialsTest {
    private final UserRepository users = mock(UserRepository.class);
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final DeployRequestRepository requests = mock(DeployRequestRepository.class);
    private final StatusHistoryRepository histories = mock(StatusHistoryRepository.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);
    private final org.springframework.jdbc.core.JdbcTemplate jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
    private final PublicDemoDataInitializer bootstrap = new PublicDemoDataInitializer(users, projects, requests, histories, passwords, jdbc);

    @Test void missingOrShortSecretFailsBeforeAnyDatabaseWrite() {
        ReflectionTestUtils.setField(bootstrap, "loginId", "visitor");
        for (String password : List.of("", "short", " ".repeat(12))) {
            ReflectionTestUtils.setField(bootstrap, "demoPassword", password);
            assertThatThrownBy(() -> bootstrap.run(new DefaultApplicationArguments())).isInstanceOf(IllegalStateException.class);
        }
        verifyNoInteractions(users, projects, requests, histories, passwords, jdbc);
    }

    @Test void explicitSecretPreservesAnExistingAccount() {
        String password = UUID.randomUUID().toString();
        ReflectionTestUtils.setField(bootstrap, "loginId", "visitor");
        ReflectionTestUtils.setField(bootstrap, "demoPassword", password);
        when(jdbc.queryForObject("SELECT DATABASE()", String.class)).thenReturn("deployops_demo");
        when(users.findByLoginId("visitor")).thenReturn(Optional.of(new User()));
        when(projects.findAll()).thenReturn(List.of(new Project()));
        when(requests.existsByReqNo(any())).thenReturn(true);
        bootstrap.run(new DefaultApplicationArguments());
        verify(users, never()).save(any());
        verifyNoInteractions(passwords, histories);
    }

    @Test void anotherDatabaseCannotReceiveAPublicAdministrator() {
        ReflectionTestUtils.setField(bootstrap, "loginId", "visitor");
        ReflectionTestUtils.setField(bootstrap, "demoPassword", UUID.randomUUID().toString());
        when(jdbc.queryForObject("SELECT DATABASE()", String.class)).thenReturn("unrelated");
        assertThatThrownBy(() -> bootstrap.run(new DefaultApplicationArguments())).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(users, projects, requests, histories, passwords);
    }
}
