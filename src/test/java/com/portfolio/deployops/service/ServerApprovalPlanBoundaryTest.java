package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.repository.ServerRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ServerApprovalPlanBoundaryTest {
    final ServerRepository repository = mock(ServerRepository.class);
    final ServerApprovalBoundaryService service = new ServerApprovalBoundaryService(repository);
    Server target(String ip) {
        return Server.builder().id(7L).ipAddress(ip).environment(ServerEnvironment.TEST)
            .serverType(ServerType.WAS).sshPort(2022).deployAccount("deploy").build();
    }
    Server protectedAlias(String ip) {
        return Server.builder().id(10L).ipAddress(ip).environment(ServerEnvironment.PROD)
            .serverType(ServerType.WAS).sshPort(22).deployAccount("other")
            .serverGroup(ServerGroup.builder().id(4L).approvalPolicyId(3L).build()).build();
    }
    void rejects(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
            ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }
    @Test void existingCrossEnvironmentAliasCannotOmitProtectedGroupEvenWithDifferentPortAndAccount() {
        when(repository.findAll()).thenReturn(List.of(protectedAlias("::ffff:198.18.21.3")));
        rejects(() -> service.assertSelectedTargetsCovered(List.of(target("198.18.21.3")), List.of(2L)));
    }
    @Test void selectedProtectedGroupIsNotSilentlyReplacedOrDuplicated() {
        when(repository.findAll()).thenReturn(List.of(protectedAlias("198.18.21.3")));
        assertThatCode(() -> service.assertSelectedTargetsCovered(List.of(target("198.18.21.3")), List.of(2L,4L)))
            .doesNotThrowAnyException();
        verify(repository).findAll();
    }
    @Test void onlyActualScopedTargetsAreCheckedAndDistinctHostDoesNotAcquireUnrelatedPolicy() {
        when(repository.findAll()).thenReturn(List.of(protectedAlias("198.18.21.3")));
        assertThatCode(() -> service.assertSelectedTargetsCovered(List.of(target("198.18.21.4")), List.of(2L)))
            .doesNotThrowAnyException();
        rejects(() -> service.assertSelectedTargetsCovered(List.of(target("198.18.21.4"),target("198.18.21.3")), List.of(2L)));
    }
    @Test void dnsAmbiguityFailsClosedInsteadOfResolvingMutableDnsDuringApproval() {
        when(repository.findAll()).thenReturn(List.of(protectedAlias("legacy.internal")));
        rejects(() -> service.assertSelectedTargetsCovered(List.of(target("198.18.21.3")), List.of(2L)));
        when(repository.findAll()).thenReturn(List.of(protectedAlias("198.18.21.3")));
        rejects(() -> service.assertSelectedTargetsCovered(List.of(target("legacy.internal")), List.of(2L)));
    }
    @Test void loopbackAliasIsProtectedAndEmptyScopeDoesNotInspectUnrelatedInfrastructure() {
        service.assertSelectedTargetsCovered(List.of(), List.of(2L));
        verifyNoInteractions(repository);
        when(repository.findAll()).thenReturn(List.of(protectedAlias("127.12.0.1")));
        rejects(() -> service.assertSelectedTargetsCovered(List.of(target("::1")), List.of(2L)));
    }
}
