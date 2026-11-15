package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.portfolio.deployops.domain.DeployEnvironment;
import com.portfolio.deployops.service.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes=ProjectRolloutAuthorizationTest.Config.class)
class ProjectRolloutAuthorizationTest {
    @Configuration @EnableMethodSecurity static class Config {
        @Bean RolloutTrafficService traffic(){return mock(RolloutTrafficService.class);}
        @Bean ProjectRolloutApiController controller(RolloutTrafficService traffic){
            return new ProjectRolloutApiController(traffic,mock(ProjectPermissionService.class),
                mock(ProjectService.class),mock(AuditLogService.class));
        }
    }
    @Autowired ProjectRolloutApiController controller;
    @Autowired RolloutTrafficService traffic;
    @org.junit.jupiter.api.BeforeEach void clear(){clearInvocations(traffic);}
    @Test @WithMockUser(roles="OPERATOR") void operatorCannotReplaceProjectTrafficOwnership(){
        assertThatThrownBy(() -> controller.save(1L,DeployEnvironment.PROD,
            new ProjectRolloutApiController.SaveForm(List.of()),null)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(traffic);
    }
    @Test @WithMockUser(roles="SUPER_ADMIN") void adminPassesMethodBoundary(){
        controller.save(1L,DeployEnvironment.PROD,new ProjectRolloutApiController.SaveForm(List.of()),null);
        verify(traffic).save(1L,DeployEnvironment.PROD,List.of());
    }
}
