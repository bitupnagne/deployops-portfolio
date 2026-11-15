package com.portfolio.deployops.controller;

import com.portfolio.deployops.service.AgentQueueService;
import com.portfolio.deployops.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentManagementApiControllerTest {
    final AgentQueueService queue=mock(AgentQueueService.class);
    final AuditLogService audit=mock(AuditLogService.class);
    final AgentManagementApiController controller=new AgentManagementApiController(queue,audit);

    @Test void approvalPassesTheExactIdentityTheAdministratorReviewedToStrictServiceMethod() {
        var response=controller.approve(7,new AgentManagementApiController.RegistrationApproval("binding7","machine7","WAS"));
        assertTrue(response.isSuccess());
        verify(queue).approve(7,"binding7","machine7","WAS");
        verify(queue,never()).approve(anyLong());
        verify(audit).recordCurrentRequired(eq("AGENT_APPROVE"),eq("Server"),eq("7"),anyString(),any());
    }
    @Test void missingReviewedIdentityCannotReachTheLegacyApprovalMethod() {
        assertEquals(400,assertThrows(ResponseStatusException.class,()->controller.approve(7,null)).getStatusCode().value());
        verifyNoInteractions(queue,audit);
    }
    @Test void readonlyResumeRequiresAdministratorAndAuditsWithoutClaimingBackupSuccess() throws Exception {
        var method=AgentManagementApiController.class.getMethod("resumeQuery",long.class,String.class,AgentManagementApiController.QueryResolution.class);
        assertEquals("hasRole('SUPER_ADMIN')",method.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class).value());
        assertNotNull(method.getAnnotation(org.springframework.transaction.annotation.Transactional.class));
        assertTrue(controller.resumeQuery(7,"task7",new AgentManagementApiController.QueryResolution("확인",true)).isSuccess());
        verify(queue).resolveReadOnlyQuery(7,"task7","확인",true);
        verify(audit).recordCurrentRequired(eq("AGENT_READ_ONLY_QUERY_RESUME"),eq("Server"),eq("7"),contains("성공 처리·백업 재실행 아님"),eq(com.portfolio.deployops.domain.AuditResult.SUCCESS));
    }
    @Test void missingOrRejectedReadonlyResumeCannotBeAuditedAsSuccess() {
        assertThrows(ResponseStatusException.class,()->controller.resumeQuery(7,"task7",null));
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT)).when(queue).resolveReadOnlyQuery(7,"task7","확인",true);
        assertThrows(ResponseStatusException.class,()->controller.resumeQuery(7,"task7",new AgentManagementApiController.QueryResolution("확인",true)));
        verifyNoInteractions(audit);
    }
}
