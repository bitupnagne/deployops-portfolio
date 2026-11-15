package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.deployops.domain.PolicyType;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.dto.PolicyForm;
import com.portfolio.deployops.repository.ApprovalPolicyRepository;
import com.portfolio.deployops.repository.ApprovalStepRepository;
import com.portfolio.deployops.repository.ReferenceRepository;
import com.portfolio.deployops.repository.UserRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class PolicyServiceTest {
    @Test
    void cannotDeletePolicyStillReferencedByServerGroup() {
        when(approvalPolicyRepository.findById(91L)).thenReturn(java.util.Optional.of(
            com.portfolio.deployops.domain.ApprovalPolicy.builder().id(91L).name("그룹 승인").build()));
        when(referenceRepository.findServerGroupNamesByPolicyId(91L)).thenReturn(List.of("운영 웹 그룹"));
        assertThatThrownBy(() -> service.delete(91L)).isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("운영 웹 그룹").hasMessageContaining("409");
        verify(approvalPolicyRepository,never()).deleteById(91L);
    }

    private final ApprovalPolicyRepository approvalPolicyRepository = mock(ApprovalPolicyRepository.class);
    private final ApprovalStepRepository approvalStepRepository = mock(ApprovalStepRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final ReferenceRepository referenceRepository = mock(ReferenceRepository.class);
    private final PolicyService service = new PolicyService(
        approvalPolicyRepository, approvalStepRepository, userRepository, referenceRepository);

    @Test
    void rejectsPolicyWithoutApprovalSteps() {
        PolicyForm form = form(PolicyType.STEP);
        form.setSteps(List.of());

        assertBadRequest(() -> service.create(form), "승인 단계와 승인자를");
        verify(approvalPolicyRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsDuplicateStepOrder() {
        PolicyForm form = form(PolicyType.STEP,
            step(1, 1, 10L), step(1, 1, 11L));

        assertBadRequest(() -> service.create(form), "승인 단계 순서가 중복");
    }

    @Test
    void rejectsRequiredCountGreaterThanAssignedApprovers() {
        PolicyForm form = form(PolicyType.STEP, step(1, 2, 10L));

        assertBadRequest(() -> service.create(form), "필수 승인 수는");
    }

    @Test
    void rejectsSameApproverAssignedToMultipleSteps() {
        PolicyForm form = form(PolicyType.STEP,
            step(1, 1, 10L), step(2, 1, 10L));

        assertBadRequest(() -> service.create(form), "여러 단계에 중복");
    }

    @Test
    void rejectsInactiveApprover() {
        PolicyForm form = form(PolicyType.STEP, step(1, 1, 10L));
        when(userRepository.findAllByIds(List.of(10L)))
            .thenReturn(List.of(User.builder().id(10L).name("휴면 사용자").active(false).build()));

        assertBadRequest(() -> service.create(form), "비활성 사용자는 승인자로");
    }

    @Test
    void rejectsMissingApprover() {
        PolicyForm form = form(PolicyType.STEP, step(1, 1, 99L));
        when(userRepository.findAllByIds(List.of(99L))).thenReturn(List.of());

        assertBadRequest(() -> service.create(form), "존재하지 않는 승인자");
    }

    @Test
    void anyOneRejectsRequiredCountOtherThanOne() {
        PolicyForm form = form(PolicyType.ANY_ONE, step(1, 2, 10L, 11L));

        assertBadRequest(() -> service.create(form), "필수 승인 수는 1명");
    }

    private void assertBadRequest(Runnable action, String message) {
        assertThatThrownBy(action::run)
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("400")
            .hasMessageContaining(message);
    }

    private PolicyForm form(PolicyType type, PolicyForm.StepForm... steps) {
        PolicyForm form = new PolicyForm();
        form.setName("운영 승인");
        form.setPolicyType(type);
        form.setSteps(List.of(steps));
        return form;
    }

    private PolicyForm.StepForm step(int order, int requiredCount, Long... approverIds) {
        PolicyForm.StepForm step = new PolicyForm.StepForm();
        step.setStepOrder(order);
        step.setRequiredCount(requiredCount);
        step.setApproverIds(List.of(approverIds));
        return step;
    }
}
