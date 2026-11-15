package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

class EffectiveApprovalPolicyTest {
    private User user(long id) {
        return User.builder().id(id).name("approver-"+id).loginId("user-"+id).role(UserRole.APPROVER).active(true).build();
    }
    private ApprovalPolicy policy(long id,PolicyType type,int required,User... users) {
        return ApprovalPolicy.builder().id(id).name("policy-"+id).policyType(type).allowSelfApproval(true)
            .steps(List.of(ApprovalStep.builder().id(id*10).stepOrder(3).requiredCount(required).approvers(List.of(users)).build())).build();
    }
    private ServerGroup group(long id,Long policyId) { return ServerGroup.builder().id(id).approvalPolicyId(policyId).build(); }

    @Test void projectFirstThenSortedGroupsPreservesAnyAllAndStepAndOrigins() {
        var project=policy(1,PolicyType.ANY_ONE,1,user(1),user(2));
        var all=policy(2,PolicyType.ALL,1,user(2),user(3));
        var staged=policy(3,PolicyType.STEP,2,user(4),user(5));
        staged.setAllowSelfApproval(false);
        var result=EffectiveApprovalPolicy.compose(project,List.of(group(90,3L),group(10,2L)),Map.of(2L,all,3L,staged)::get);
        assertThat(result.getPolicyType()).isEqualTo(PolicyType.STEP);
        assertThat(result.isAllowSelfApproval()).isFalse();
        assertThat(result.getSteps()).extracting(ApprovalStep::getRequiredCount).containsExactly(1,2,2);
        assertThat(result.getSteps()).extracting(ApprovalStep::getStepOrder).containsExactly(1,2,3);
        assertThat(result.getSteps()).extracting(ApprovalStep::getSourcePolicyId).containsExactly(1L,2L,3L);
        assertThat(result.getSteps().get(2).getSourceStepId()).isEqualTo(30L);
        assertThat(result.getSteps().get(2).getSourceStepOrder()).isEqualTo(3);
        assertThat(result.getSourceProjectPolicyId()).isEqualTo(1L);
        assertThat(result.getRequiredPolicies()).containsExactly(project,all,staged);
    }

    @Test void nullGroupPolicyPreservesLegacyProjectObjectExactly() {
        var project=policy(1,PolicyType.ANY_ONE,1,user(1));
        assertThat(EffectiveApprovalPolicy.compose(project,List.of(group(1,null)),id -> {throw new AssertionError();})).isSameAs(project);
    }

    @Test void identicalPolicyIsDeduplicatedButConflictingSameIdFailsClosed() {
        var policy=policy(1,PolicyType.STEP,1,user(1));
        assertThat(EffectiveApprovalPolicy.compose(policy,List.of(group(1,1L),group(2,1L)),id -> policy).getSteps()).hasSize(1);
        var changed=policy(1,PolicyType.STEP,1,user(2));
        assertThatThrownBy(() -> EffectiveApprovalPolicy.compose(policy,List.of(group(1,1L)),id -> changed))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("조회 도중 변경");
    }

    @Test void emptyInactiveAndImpossibleStepFailInsteadOfReducingApproval() {
        var inactive=user(1);inactive.setActive(false);
        for(var bad:List.of(policy(1,PolicyType.ANY_ONE,1,inactive),policy(1,PolicyType.ALL,1,inactive),
                             policy(1,PolicyType.STEP,2,user(1)))) {
            assertThatThrownBy(() -> EffectiveApprovalPolicy.compose(null,List.of(group(1,1L)),id -> bad))
                .isInstanceOf(ResponseStatusException.class);
        }
    }

    @Test void denyingSelfApprovalCannotLeaveOnlyRequesterAbleToMeetStep() {
        var original=policy(1,PolicyType.STEP,2,user(1),user(2));original.setAllowSelfApproval(false);
        var effective=EffectiveApprovalPolicy.compose(null,List.of(group(1,1L)),id -> original);
        assertThatThrownBy(() -> EffectiveApprovalPolicy.assertRequesterFeasible(effective,1L))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("요청자를 제외");
        assertThatCode(() -> EffectiveApprovalPolicy.assertRequesterFeasible(effective,99L)).doesNotThrowAnyException();
    }

    @Test void activeUniqueAllMembersDoNotCountDuplicateAssignmentsTwice() {
        var same=user(1);
        var original=policy(1,PolicyType.ALL,1,same,same,user(2));
        var result=EffectiveApprovalPolicy.compose(null,List.of(group(1,1L)),id -> original);
        assertThat(result.getSteps().getFirst().getRequiredCount()).isEqualTo(2);
        assertThat(result.getSteps().getFirst().getApprovers()).hasSize(2);
    }
}
