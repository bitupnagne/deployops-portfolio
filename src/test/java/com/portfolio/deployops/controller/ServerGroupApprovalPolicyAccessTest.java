package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.repository.*;
import com.portfolio.deployops.service.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class ServerGroupApprovalPolicyAccessTest {
    final ServerGroupRepository groups = mock(ServerGroupRepository.class);
    final ApprovalPolicyRepository policies = mock(ApprovalPolicyRepository.class);
    final ProjectRepository projects = mock(ProjectRepository.class);
    final AuditLogService audits = mock(AuditLogService.class);
    final ServerGroupService service = new ServerGroupService(groups, mock(ServerRepository.class), projects);
    ServerGroup existing;
    MockMvc mvc;
    @BeforeEach void setup() {
        service.setApprovalPolicyRepository(policies);
        existing = ServerGroup.builder().id(8L).name("그룹").environment(ServerEnvironment.TEST).approvalPolicyId(4L).build();
        when(groups.findByIdForUpdate(8L)).thenReturn(Optional.of(existing));
        when(groups.save(any())).thenAnswer(call -> { ServerGroup g=call.getArgument(0); if(g.getId()==null)g.setId(9L); return g; });
        when(policies.findById(4L)).thenReturn(Optional.of(ApprovalPolicy.builder().id(4L).name("추가 승인").build()));
        mvc=MockMvcBuilders.standaloneSetup(new ServerGroupApiController(service,audits))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        actor("OPERATOR");
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    void actor(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("qa", "n/a",
            List.of(new SimpleGrantedAuthority("ROLE_"+role))));
    }
    String body(String field) { return "{\"name\":\"이름 수정\",\"environment\":\"TEST\",\"rollingIntervalSec\":12"+field+"}"; }

    @Test void operatorCanEditOrdinaryFieldsOnlyWithExistingPolicyRetained() throws Exception {
        mvc.perform(put("/api/v1/server-groups/8").contentType("application/json").content(body(",\"approvalPolicyId\":4")))
            .andExpect(status().isOk());
        assertThat(existing.getApprovalPolicyId()).isEqualTo(4L);
        assertThat(existing.getName()).isEqualTo("이름 수정");
        assertThat(existing.getRollingIntervalSec()).isEqualTo(12);
        verify(groups).save(existing);
        verifyNoInteractions(projects);
    }
    @Test void forgedCreateAndChangeRemovalOrOmittedOldUiCannotAlterGroupApproval() throws Exception {
        mvc.perform(post("/api/v1/server-groups").contentType("application/json").content(body(",\"approvalPolicyId\":4")))
            .andExpect(status().isForbidden());
        for(String field:List.of(",\"approvalPolicyId\":5",",\"approvalPolicyId\":null","")) {
            mvc.perform(put("/api/v1/server-groups/8").contentType("application/json").content(body(field)))
                .andExpect(status().isForbidden());
        }
        assertThat(existing.getApprovalPolicyId()).isEqualTo(4L);
        assertThat(existing.getName()).isEqualTo("그룹");
        verify(groups,never()).save(any()); verifyNoInteractions(audits,policies);
    }
    @Test void superAdminCanSetAndRemoveAdditionalPolicyWithoutChangingProjectPolicy() throws Exception {
        actor("SUPER_ADMIN");
        mvc.perform(post("/api/v1/server-groups").contentType("application/json").content(body(",\"approvalPolicyId\":4")))
            .andExpect(status().isCreated());
        mvc.perform(put("/api/v1/server-groups/8").contentType("application/json").content(body(",\"approvalPolicyId\":null")))
            .andExpect(status().isOk());
        assertThat(existing.getApprovalPolicyId()).isNull();
        verifyNoInteractions(projects);
    }
    @Test void unknownPolicyRejectedAndExistingPolicyCheckedBeforeSaving() throws Exception {
        actor("SUPER_ADMIN");
        mvc.perform(put("/api/v1/server-groups/8").contentType("application/json").content(body(",\"approvalPolicyId\":99")))
            .andExpect(status().isBadRequest());
        verify(groups,never()).save(any()); assertThat(existing.getApprovalPolicyId()).isEqualTo(4L);
    }
    @Test void operatorCannotListAllChoicesButAssignedNameMapExcludesUnassignedPolicies() {
        assertThatThrownBy(service::approvalPolicyChoices).isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(403));
        verifyNoInteractions(policies);
        when(policies.findAll()).thenReturn(List.of(ApprovalPolicy.builder().id(4L).name("추가 승인").build(),
            ApprovalPolicy.builder().id(99L).name("다른 정책").build()));
        assertThat(service.approvalPolicyNames(List.of(existing))).containsOnlyKeys(4L);
    }
    @Test void operatorCanCreateGroupWithoutAdditionalApproval() throws Exception {
        mvc.perform(post("/api/v1/server-groups").contentType("application/json").content(body("")))
            .andExpect(status().isCreated());
        verifyNoInteractions(policies,projects);
    }
    @Test void operatorCannotRemoveApprovalByDeletingAnUnmappedGroup() throws Exception {
        when(groups.findById(8L)).thenReturn(Optional.of(existing));
        mvc.perform(delete("/api/v1/server-groups/8")).andExpect(status().isForbidden());
        verify(groups,never()).delete(any());
        verifyNoInteractions(audits,projects);
    }
}
