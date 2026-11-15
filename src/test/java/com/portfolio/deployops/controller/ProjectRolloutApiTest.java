package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.repository.ProjectRolloutRouteRepository;
import com.portfolio.deployops.service.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ProjectRolloutApiTest {
    final RolloutTrafficService traffic=mock(RolloutTrafficService.class);
    final ProjectPermissionService permissions=mock(ProjectPermissionService.class);
    final ProjectService projects=mock(ProjectService.class);
    final AuditLogService audit=mock(AuditLogService.class);
    final ProjectRolloutApiController controller=new ProjectRolloutApiController(traffic,permissions,projects,audit);
    final SecurityUserDetails user=new SecurityUserDetails(User.builder().id(1L).role(UserRole.SUPER_ADMIN).build());

    @Test void viewPermissionIsCheckedBeforeReadingRouteMetadata() {
        doThrow(new AccessDeniedException("denied")).when(permissions).assertPermission(user,2L,ProjectPermissionType.PROJECT_VIEW);
        assertThatThrownBy(() -> controller.get(2L,DeployEnvironment.PROD,user)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(traffic,projects);
    }
    @Test void saveBindsProjectAndEnvironmentOnServerAndAuditsExactPolicy() {
        var form=new ProjectRolloutApiController.SaveForm(List.of(new ProjectRolloutApiController.Selection(7L,"api")));
        controller.save(2L,DeployEnvironment.PROD,form,user);
        verify(permissions).assertPermission(user,2L,ProjectPermissionType.PROJECT_VIEW);
        verify(traffic).save(2L,DeployEnvironment.PROD,List.of(new ProjectRolloutRouteRepository.Binding(2L,DeployEnvironment.PROD,7L,"api")));
        verify(audit).recordCurrentRequired(eq("프로젝트 롤링 트래픽 연결 변경"),eq("Project"),eq("2"),contains("7:api"),eq(AuditResult.SUCCESS));
    }
    @Test void httpRejectsMalformedBindingsAndDevEnvironment() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(put("/api/v1/projects/2/rollout-routes?environment=PROD")
            .contentType("application/json").content("{\"bindings\":[{\"webServerId\":7,\"policyName\":\"../other\"}]}"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/projects/2/rollout-routes?environment=DEV")).andExpect(status().isBadRequest());
        verifyNoInteractions(traffic);
    }
}
