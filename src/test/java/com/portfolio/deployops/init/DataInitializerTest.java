package com.portfolio.deployops.init;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.repository.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.password.PasswordEncoder;

class DataInitializerTest {
    private final UserRepository users = mock(UserRepository.class);
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final ServerGroupRepository groups = mock(ServerGroupRepository.class);
    private final ServerRepository servers = mock(ServerRepository.class);
    private final ApprovalPolicyRepository policies = mock(ApprovalPolicyRepository.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);
    private final org.springframework.jdbc.core.JdbcTemplate jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withUserConfiguration(DataInitializer.class)
        .withBean(UserRepository.class, () -> users)
        .withBean(ProjectRepository.class, () -> projects)
        .withBean(ServerGroupRepository.class, () -> groups)
        .withBean(ServerRepository.class, () -> servers)
        .withBean(ApprovalPolicyRepository.class, () -> policies)
        .withBean(PasswordEncoder.class, () -> passwords)
        .withBean(org.springframework.jdbc.core.JdbcTemplate.class, () -> jdbc)
        .withPropertyValues("app.bootstrap.admin-password=initial-test-passphrase");

    @Test
    void emptyDatabaseWithoutSampleSettingCreatesOnlyCoreData() {
        assertCoreOnly(context);
    }

    @Test
    void publicDemoRejectsAnotherDatabaseBeforeAnyCoreBootstrapWrite() {
        when(jdbc.queryForObject("SELECT DATABASE()", String.class)).thenReturn("unrelated");
        context.withPropertyValues("app.public-demo.enabled=true", "app.public-demo.username=visitor",
                "app.public-demo.password=" + java.util.UUID.randomUUID()).run(ctx -> {
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    ctx.getBean(DataInitializer.class).run(new DefaultApplicationArguments()))
                    .isInstanceOf(IllegalStateException.class);
        });
        verifyNoInteractions(users, passwords, projects, groups, servers, policies);
    }

    @Test
    void explicitFalseCreatesOnlyCoreData() {
        assertCoreOnly(context.withPropertyValues("app.bootstrap.sample-data-enabled=false"));
    }

    @Test
    void emptyDatabaseRequiresAnExplicitInitialPassword() {
        context.withPropertyValues("app.bootstrap.admin-password=").run(ctx -> {
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                ctx.getBean(DataInitializer.class).run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BOOTSTRAP_ADMIN_PASSWORD");
        });
        verify(users).count();
        verifyNoMoreInteractions(users);
        verifyNoInteractions(passwords, projects, groups, servers, policies);
    }

    private void assertCoreOnly(ApplicationContextRunner runner) {
        when(passwords.encode("initial-test-passphrase")).thenReturn("encoded-test-password");
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            ctx.getBean(DataInitializer.class).run(new DefaultApplicationArguments());
        });
        ArgumentCaptor<User> admin = ArgumentCaptor.forClass(User.class);
        verify(users).save(admin.capture());
        assertThat(admin.getValue().getLoginId()).isEqualTo("admin");
        assertThat(admin.getValue().getRole()).isEqualTo(UserRole.SUPER_ADMIN);
        assertThat(admin.getValue().getPassword()).isEqualTo("encoded-test-password");
        assertThat(admin.getValue().isForcePasswordChange()).isTrue();
        ArgumentCaptor<ApprovalPolicy> policy = ArgumentCaptor.forClass(ApprovalPolicy.class);
        verify(policies).save(policy.capture());
        assertThat(policy.getValue().getPolicyType()).isEqualTo(PolicyType.ANY_ONE);
        assertThat(policy.getValue().isAllowSelfApproval()).isFalse();
        verifyNoInteractions(projects, groups, servers);
        verify(users).count();
        verifyNoMoreInteractions(users, policies);
    }

    @Test
    void explicitOptInRetainsSampleGroupsServersProjectsAndMappings() {
        AtomicLong groupIds = new AtomicLong();
        AtomicLong projectIds = new AtomicLong();
        when(groups.save(any(ServerGroup.class))).thenAnswer(invocation -> {
            ServerGroup group = invocation.getArgument(0);
            group.setId(groupIds.incrementAndGet());
            return group;
        });
        when(projects.save(any(Project.class))).thenAnswer(invocation -> {
            Project project = invocation.getArgument(0);
            project.setId(projectIds.incrementAndGet());
            return project;
        });
        when(policies.save(any(ApprovalPolicy.class))).thenAnswer(invocation -> invocation.getArgument(0));
        context.withPropertyValues("app.bootstrap.sample-data-enabled=true").run(ctx ->
            ctx.getBean(DataInitializer.class).run(new DefaultApplicationArguments()));

        verify(users).save(any(User.class));
        verify(policies).save(any(ApprovalPolicy.class));
        verify(groups, times(4)).save(any(ServerGroup.class));
        ArgumentCaptor<Server> savedServers = ArgumentCaptor.forClass(Server.class);
        verify(servers, times(9)).save(savedServers.capture());
        assertThat(savedServers.getAllValues()).extracting(Server::getName)
            .containsExactly("deploy01", "gitlab01", "nexus01", "test-was1", "test-web1",
                "prod-was1", "prod-was2", "prod-web1", "prod-web2");
        ArgumentCaptor<Project> savedProjects = ArgumentCaptor.forClass(Project.class);
        verify(projects, times(2)).save(savedProjects.capture());
        assertThat(savedProjects.getAllValues()).extracting(Project::getName)
            .containsExactly("payment-api", "payment-web");
        verify(projects).replaceServerGroupMappings(1L, java.util.List.of(1L, 3L));
        verify(projects).replaceServerGroupMappings(2L, java.util.List.of(2L, 4L));
        verify(projects, times(2)).replaceServerGroupMappings(anyLong(), anyList());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void existingUsersReturnBeforeAnyBootstrapWriteRegardlessOfOptIn(boolean samples) {
        when(users.count()).thenReturn(1L);
        context.withPropertyValues("app.bootstrap.sample-data-enabled=" + samples).run(ctx ->
            ctx.getBean(DataInitializer.class).run(new DefaultApplicationArguments()));
        verify(users).count();
        verifyNoMoreInteractions(users);
        verifyNoInteractions(projects, groups, servers, policies, passwords);
    }
}
