package com.portfolio.deployops.controller;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.dto.NexusBackupRequestForm;
import com.portfolio.deployops.service.NexusBackupService;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.*;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes=NexusBackupAuthorizationTest.Config.class)
class NexusBackupAuthorizationTest {
    @Configuration @EnableMethodSecurity static class Config {
        @Bean NexusBackupService service(){return mock(NexusBackupService.class);}
        @Bean NexusBackupApiController controller(NexusBackupService s){return new NexusBackupApiController(s);}
    }
    @Autowired NexusBackupApiController controller;
    @Autowired NexusBackupService service;
    final SecurityUserDetails actor=new SecurityUserDetails(User.builder().id(12L).build());
    @BeforeEach void clear(){clearInvocations(service);}
    @Test @WithMockUser(roles="DEVELOPER") void developerAllEndpointsDenied(){denyAll();}
    @Test @WithMockUser(roles="PROJECT_MANAGER") void managerAllEndpointsDenied(){denyAll();}
    @Test @WithAnonymousUser void anonymousDenied(){denyAll();}
    void denyAll(){assertThatThrownBy(()->controller.list()).isInstanceOf(AccessDeniedException.class);assertThatThrownBy(()->controller.detail(1L)).isInstanceOf(AccessDeniedException.class);assertThatThrownBy(()->controller.create(new NexusBackupRequestForm("유지보수",true),actor)).isInstanceOf(AccessDeniedException.class);assertThatThrownBy(()->controller.approve(1L,Map.of("externalWritersStopped",true),actor)).isInstanceOf(AccessDeniedException.class);assertThatThrownBy(()->controller.reject(1L,Map.of("reason","반려"),actor)).isInstanceOf(AccessDeniedException.class);verifyNoInteractions(service);}
    @Test @WithMockUser(roles="OPERATOR") void operatorRequestsButCannotApprove(){controller.list();assertThat(controller.create(new NexusBackupRequestForm("유지보수",true),actor).getStatusCode().value()).isEqualTo(201);assertThatThrownBy(()->controller.approve(1L,Map.of("externalWritersStopped",true),actor)).isInstanceOf(AccessDeniedException.class);assertThatThrownBy(()->controller.reject(1L,Map.of("reason","반려"),actor)).isInstanceOf(AccessDeniedException.class);verify(service).create("유지보수",true,12L);}
    @Test @WithMockUser(roles="APPROVER") void approverReadsAndApproves202ButCannotRequest(){controller.list();assertThat(controller.approve(1L,Map.of("externalWritersStopped",true),actor).getStatusCode().value()).isEqualTo(202);controller.reject(1L,Map.of("reason","유지보수 창 없음"),actor);assertThatThrownBy(()->controller.create(new NexusBackupRequestForm("유지보수",true),actor)).isInstanceOf(AccessDeniedException.class);verify(service).reject(1L,12L,"유지보수 창 없음");}
    @Test @WithMockUser(roles="SUPER_ADMIN") void adminStillUsesServiceDutySeparation(){controller.create(new NexusBackupRequestForm("유지보수",true),actor);controller.approve(1L,Map.of("externalWritersStopped",true),actor);verify(service).approve(1L,12L,true);}
}
