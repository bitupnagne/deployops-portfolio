package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.ApprovalAction;
import com.portfolio.deployops.domain.ApprovalRecord;
import com.portfolio.deployops.domain.DeployEnvironment;
import com.portfolio.deployops.domain.DeployRequest;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.domain.UserRole;
import com.portfolio.deployops.repository.ApprovalRecordRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class DeploymentDutySeparationServiceTest {

    private final ApprovalRecordRepository repository = mock(ApprovalRecordRepository.class);
    private final DeploymentDutySeparationService service = new DeploymentDutySeparationService(repository);

    @Test
    void testDeploymentDoesNotRequireSeparateOperator() {
        DeployRequest request = request(DeployEnvironment.TEST, user(1L));
        assertThatCode(() -> service.assertCanOperate(request, details(1L))).doesNotThrowAnyException();
    }

    @Test
    void prodRequesterCannotExecuteOwnRequest() {
        DeployRequest request = request(DeployEnvironment.PROD, user(1L));
        assertThatThrownBy(() -> service.assertCanOperate(request, details(1L)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("요청자");
    }

    @Test
    void prodApproverCannotExecuteApprovedRequest() {
        DeployRequest request = request(DeployEnvironment.PROD, user(1L));
        when(repository.findByDeployRequestId(10L)).thenReturn(List.of(ApprovalRecord.builder()
            .approver(user(2L)).action(ApprovalAction.APPROVED).build()));

        assertThatThrownBy(() -> service.assertCanOperate(request, details(2L)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("승인자");
    }

    @Test
    void independentOperatorCanExecuteProdRequest() {
        DeployRequest request = request(DeployEnvironment.PROD, user(1L));
        when(repository.findByDeployRequestId(10L)).thenReturn(List.of(ApprovalRecord.builder()
            .approver(user(2L)).action(ApprovalAction.APPROVED).build()));

        assertThatCode(() -> service.assertCanOperate(request, details(3L))).doesNotThrowAnyException();
    }

    private DeployRequest request(DeployEnvironment environment, User requester) {
        return DeployRequest.builder().id(10L).environment(environment).requestedBy(requester).build();
    }

    private SecurityUserDetails details(Long id) {
        return new SecurityUserDetails(user(id));
    }

    private User user(Long id) {
        return User.builder().id(id).loginId("user" + id).name("user" + id)
            .password("secret").role(UserRole.OPERATOR).active(true).build();
    }
}
