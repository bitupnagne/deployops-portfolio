package com.portfolio.deployops.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import com.portfolio.deployops.service.SystemSettingService;
import com.portfolio.deployops.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@link SecurityConfig} 의 <b>인가 경계</b>를 실제 필터 체인에 태워 확인한다.
 *
 * <p>왜 필요한가 — 이 저장소의 테스트는 전부 Mockito 단위 테스트였다. 그래서
 * "누가 어디에 닿을 수 있는가" 를 검증하는 것이 하나도 없었다. 인가 규칙은 URL 문자열
 * 매칭이라 <b>컴파일러가 지켜 주지 않는다</b>. 경로 한 줄을 오타 내거나, 새 API 를 만들고
 * 매처에 추가하는 것을 잊으면 규칙이 조용히 사라지고 {@code anyRequest().authenticated()}
 * 로 흘러내린다 — 로그인만 하면 남의 서버 목록이 열린다는 뜻이다. 화면에서 눌러 보기
 * 전까지 아무도 모른다.
 *
 * <p>매처는 <b>순서가 의미를 가진다</b>. 위에 걸린 규칙이 이긴다. 그래서 규칙을 옮기는
 * 리팩터링이 가장 위험한데, 이 테스트가 그 순서까지 같이 고정한다.
 *
 * <p>컨트롤러는 일부러 진짜를 쓰지 않는다. 여기서 보려는 것은 컨트롤러의 동작이 아니라
 * <b>요청이 컨트롤러까지 닿는가</b>이므로, 모든 경로를 받아 200 을 돌려주는 것 하나만 둔다.
 * 200 이면 통과한 것이고, 302(로그인으로)나 403 이면 막힌 것이다.
 */
/*
  Boot 의 @WebMvcTest 슬라이스를 쓰지 않는다. Spring Boot 4 에서 그 애너테이션이 별도
  모듈로 빠졌는데, 이 저장소는 폐쇄망 설치를 전제로 <오프라인 빌드>가 되어야 한다.
  의존성을 하나 더 받아 오는 대신 필터 체인을 직접 세운다 — 자동설정이 끼지 않으므로
  무엇이 켜져 있는지도 분명해진다.
*/
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = SecurityAuthorizationBoundaryTest.TestApp.class)
class SecurityAuthorizationBoundaryTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        // springSecurity() 가 springSecurityFilterChain 을 MockMvc 앞에 끼운다.
        // 이것이 있어야 인가가 <실제로> 돌고, 없으면 전부 통과해 테스트가 무의미해진다.
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @EnableWebMvc
    @Configuration
    @Import(SecurityConfig.class)
    static class TestApp {

        /* SecurityConfig 가 생성자로 받는 것들. 인가 경계만 보므로 동작은 필요 없다. */
        @Bean
        UserDetailsServiceImpl userDetailsService() {
            return Mockito.mock(UserDetailsServiceImpl.class);
        }

        @Bean
        LoginAttemptService loginAttemptService() {
            return Mockito.mock(LoginAttemptService.class);
        }

        @Bean
        SystemSettingService systemSettingService() {
            return Mockito.mock(SystemSettingService.class);
        }

        @Bean
        UserService userService() {
            return Mockito.mock(UserService.class);
        }

        @Bean
        PassThroughController passThroughController() {
            return new PassThroughController();
        }
    }

    /** 인가를 통과한 요청만 여기까지 온다. */
    @RestController
    static class PassThroughController {
        @RequestMapping("/**")
        String any() {
            return "ok";
        }

        @PostMapping("/**")
        String anyPost() {
            return "ok";
        }
    }

    private void allowed(String path) throws Exception {
        mvc.perform(get(path).with(csrf())).andExpect(status().isOk());
    }

    private void denied(String path) throws Exception {
        mvc.perform(get(path).with(csrf())).andExpect(status().isForbidden());
    }

    @Nested
    @DisplayName("로그인하지 않은 요청")
    class Anonymous {

        /*
          여기 있는 것들은 인증 없이 열려 있어야 한다. 하나라도 막히면
          로그인 화면 자체가 안 뜨거나(css/js), L4 헬스체크가 서버를 죽은 것으로 보거나,
          GitLab 웹훅이 전부 302 를 받아 빌드 결과가 영영 안 들어온다.
        */
        @Test
        @WithAnonymousUser
        void loginPageAndStaticAssetsAndHealthAreOpen() throws Exception {
            allowed("/login");
            allowed("/health");
            allowed("/css/styles.css");
            allowed("/js/app.js");
            allowed("/img/suite-mark.svg");
            allowed("/fonts/PretendardVariable.woff2");
        }

        /** 웹훅은 시크릿(X-Gitlab-Token)으로 서비스 단에서 검증하므로 인가는 열어 둔다. */
        @Test
        @WithAnonymousUser
        void gitlabWebhookIsOpenAndCsrfExempt() throws Exception {
            mvc.perform(post("/api/v1/gitlab/webhook")).andExpect(status().isOk());
        }

        /** OTP 는 비밀번호만 통과한 상태(미인증)에서 들어온다. */
        @Test
        @WithAnonymousUser
        void otpStepIsReachableBeforeAuthentication() throws Exception {
            allowed("/login/otp/verify");
        }

        /*
          나머지는 전부 막혀야 한다. anyRequest().authenticated() 로 흘러내리는지 확인하는
          자리이기도 하다 — 새 경로를 permitAll 목록에 실수로 넣으면 여기서 걸린다.
        */
        @Test
        @WithAnonymousUser
        void everythingElseRedirectsToLogin() throws Exception {
            mvc.perform(get("/deploy/dashboard")).andExpect(status().is3xxRedirection());
            mvc.perform(get("/deploy/servers")).andExpect(status().is3xxRedirection());
            mvc.perform(get("/api/v1/projects")).andExpect(status().is3xxRedirection());
            mvc.perform(get("/api/v1/servers/1")).andExpect(status().is3xxRedirection());
            mvc.perform(get("/api/v1/users")).andExpect(status().is3xxRedirection());
        }
    }

    @Nested
    @DisplayName("관리 기능은 SUPER_ADMIN 만")
    class SuperAdminOnly {

        @Test
        @WithMockUser(roles = "SUPER_ADMIN")
        void projectPermissionManagementRequiresCsrfForMutation() throws Exception {
            allowed("/api/v1/projects/10/permissions");
            allowed("/api/v1/projects/10/permissions/2");
            allowed("/api/v1/users/2/project-permissions");
            mvc.perform(put("/api/v1/projects/10/permissions/2")).andExpect(status().isForbidden());
            mvc.perform(put("/api/v1/projects/10/permissions/2").with(csrf())).andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = "PROJECT_MANAGER")
        void projectManagerCannotGrantProjectRightsToThemselvesOrOthers() throws Exception {
            denied("/api/v1/projects/10/permissions");
            denied("/api/v1/projects/10/permissions/2");
            denied("/api/v1/users/2/project-permissions");
            mvc.perform(put("/api/v1/projects/10/permissions/2").with(csrf())).andExpect(status().isForbidden());
        }

        /*
          사용자·역할·팀·승인정책·시스템설정은 이 시스템에서 가장 무거운 권한이다.
          시스템 설정 경로로는 GitLab 토큰·Nexus 자격증명이 드나든다.
          여기가 새면 남의 계정 역할을 올리거나 시크릿을 바꿔 배포를 우회할 수 있다.
        */
        @Test
        @WithMockUser(roles = "SUPER_ADMIN")
        void superAdminReachesAdminSurface() throws Exception {
            allowed("/deploy/users");
            allowed("/deploy/teams");
            allowed("/deploy/policies");
            allowed("/deploy/integration");
            allowed("/deploy/exec-policy");
            allowed("/deploy/ai-settings");
            allowed("/deploy/security");
            allowed("/api/v1/users/1");
            allowed("/api/v1/teams/1");
            allowed("/api/v1/policies/1");
            allowed("/api/v1/settings/gitlab.token");
            allowed("/api/v1/office-notifications/outbox");
            mvc.perform(post("/api/v1/users/2/reset-password").with(csrf()))
                .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = "DEVELOPER")
        void developerCannot() throws Exception {
            denied("/deploy/users");
            denied("/api/v1/users/1");
            denied("/api/v1/settings/gitlab.token");
            denied("/api/v1/office-notifications/outbox");
            mvc.perform(post("/api/v1/users/2/reset-password").with(csrf()))
                .andExpect(status().isForbidden());
        }

        /** 운영자도 안 된다 — 배포를 실행할 수 있다고 계정을 만질 수 있는 것은 아니다. */
        @Test
        @WithMockUser(roles = "OPERATOR")
        void operatorCannot() throws Exception {
            denied("/deploy/users");
            denied("/api/v1/users/1");
            denied("/api/v1/policies/1");
        }

        @Test
        @WithMockUser(roles = "APPROVER")
        void approverCannot() throws Exception {
            denied("/api/v1/settings/gitlab.token");
        }

        /** 프로젝트 관리자는 조회·MR 검토 역할이며 전역 설정 권한은 없다. */
        @Test
        @WithMockUser(roles = "PROJECT_MANAGER")
        void projectManagerCannot() throws Exception {
            denied("/api/v1/users/1");
            denied("/api/v1/policies/1");
        }

        @Test
        @WithMockUser(roles = "PROJECT_MANAGER")
        void managerCannotRedirectSharedGitlabOrChangeDeploymentConfiguration() throws Exception {
            mvc.perform(post("/api/v1/projects").with(csrf())).andExpect(status().isForbidden());
            mvc.perform(put("/api/v1/projects/10").with(csrf())).andExpect(status().isForbidden());
            mvc.perform(delete("/api/v1/projects/10").with(csrf())).andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/projects/10/components").with(csrf())).andExpect(status().isForbidden());
            mvc.perform(put("/api/v1/projects/10/components/2").with(csrf())).andExpect(status().isForbidden());
            mvc.perform(delete("/api/v1/projects/10/components/2").with(csrf())).andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/projects/10/gitlab/test").with(csrf())).andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/integration/test/gitlab").with(csrf())).andExpect(status().isForbidden());
            denied("/api/v1/gitlab/projects");
            denied("/api/v1/gitlab/projects/999/branches");
            denied("/api/v1/nexus/repositories");
        }

        @Test
        @WithMockUser(roles = "PROJECT_MANAGER")
        void managerProjectScopedReadAndMrOperationsStillReachServicePermissions() throws Exception {
            allowed("/api/v1/projects/10/readiness");
            allowed("/api/v1/projects/10/components");
            allowed("/api/v1/gitlab/deploy-projects/10/branches");
            allowed("/api/v1/gitlab/deploy-projects/10/merge-requests");
            allowed("/api/v1/nexus/deploy-projects/10/image-status");
            mvc.perform(post("/api/v1/gitlab/deploy-projects/10/ci-file").with(csrf()))
                .andExpect(status().isOk());
            mvc.perform(post("/api/v1/gitlab/deploy-projects/10/merge-requests/5/approve").with(csrf()))
                .andExpect(status().isOk());
            mvc.perform(post("/api/v1/operator/deploy").with(csrf())).andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = "SUPER_ADMIN")
        void adminCanReachConfigurationWithCsrfAndScopedOperations() throws Exception {
            mvc.perform(post("/api/v1/projects").with(csrf())).andExpect(status().isOk());
            mvc.perform(put("/api/v1/projects/10").with(csrf())).andExpect(status().isOk());
            mvc.perform(delete("/api/v1/projects/10").with(csrf())).andExpect(status().isOk());
            mvc.perform(post("/api/v1/projects/10/components").with(csrf())).andExpect(status().isOk());
            mvc.perform(put("/api/v1/projects/10/components/2").with(csrf())).andExpect(status().isOk());
            mvc.perform(delete("/api/v1/projects/10/components/2").with(csrf())).andExpect(status().isOk());
            mvc.perform(post("/api/v1/projects/10/gitlab/test").with(csrf())).andExpect(status().isOk());
            mvc.perform(post("/api/v1/integration/test/gitlab").with(csrf())).andExpect(status().isOk());
            allowed("/api/v1/gitlab/projects");
            allowed("/api/v1/gitlab/projects/999/branches");
            allowed("/api/v1/nexus/repositories");
            mvc.perform(put("/api/v1/projects/10")).andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = "OPERATOR")
        void operatingRoleCannotChangeProjectConfiguration() throws Exception {
            mvc.perform(put("/api/v1/projects/10").with(csrf())).andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/projects/10/components").with(csrf())).andExpect(status().isForbidden());
            denied("/api/v1/gitlab/projects");
        }
    }

    @Nested
    @DisplayName("Nexus 운영 화면과 정리 요청은 역할을 분리한다")
    class NexusOperations {

        @Test
        @WithMockUser(roles = "OPERATOR")
        void operatorCanReachNexusOperationsSurface() throws Exception {
            allowed("/deploy/nexus-operations");
            allowed("/api/v1/nexus/storage");
            allowed("/api/v1/nexus/cleanup-requests");
            mvc.perform(post("/api/v1/nexus/cleanup-requests").with(csrf()))
                .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = "APPROVER")
        void approverCanReachDecisionSurfaceWithoutSeeingIntegrationSecrets() throws Exception {
            allowed("/deploy/nexus-operations");
            allowed("/api/v1/nexus/storage");
            allowed("/api/v1/nexus/cleanup-requests");
            mvc.perform(post("/api/v1/nexus/cleanup-requests/1/approve").with(csrf()))
                .andExpect(status().isOk());
            denied("/deploy/integration");
            denied("/api/v1/settings/nexus.password");
        }

        @Test
        @WithMockUser(roles = "DEVELOPER")
        void developerCannotReachGlobalNexusOperations() throws Exception {
            denied("/deploy/nexus-operations");
            denied("/api/v1/nexus/storage");
            denied("/api/v1/nexus/cleanup-requests");
        }
    }

    /*
      승인 API 도 운영 실행과 같은 자리에 있다 — HTTP 레벨은 일부러 인증만 본다.

      승인권자는 두 종류다. 전역 APPROVER/SUPER_ADMIN 과, 그 프로젝트에
      TEST_APPROVE/PROD_APPROVE 를 받은 사용자. 후자는 역할이 DEVELOPER 일 수 있어서
      URL 매처의 hasAnyRole 로는 구분할 수 없다. 게다가 판정에는 <요청 건이 어느
      프로젝트의 어느 환경인가>가 필요한데, 그 정보는 URL 에 없다.

      ★ 여기가 200 인 것을 보고 "구멍이네" 하며 hasRole 을 도로 붙이면,
        프로젝트 스코프 승인자가 ApprovalService 에 닿기도 전에 403 이 되어
        기능이 통째로 죽는다(실제로 한 번 그렇게 되어 있었다).
        진짜 경계는 ApprovalService.assertApprovalPermission 이고,
        "누가 승인할 수 있는가" 는 ApprovalServiceTest 가 고정한다.
    */
    @Nested
    @DisplayName("승인 API 는 HTTP 레벨에서 인증만 본다 — 실제 경계는 서비스 계층")
    class Approvals {

        @Test
        @WithMockUser(roles = "APPROVER")
        void approverPassesHttpLayer() throws Exception {
            allowed("/api/v1/approvals/1");
        }

        @Test
        @WithMockUser(roles = "SUPER_ADMIN")
        void superAdminPassesHttpLayer() throws Exception {
            allowed("/api/v1/approvals/1");
        }

        /*
          프로젝트 승인 권한을 받은 개발자가 여기서 막히면 안 된다. 역할만 보고 자를 수
          없으므로 통과시키고, 권한이 없는 개발자는 서비스 계층이 403 으로 돌려보낸다.
        */
        @Test
        @WithMockUser(roles = "DEVELOPER")
        void developerPassesHttpLayerAndIsJudgedByTheService() throws Exception {
            allowed("/api/v1/approvals/1");
        }

        @Test
        @WithMockUser(roles = "OPERATOR")
        void operatorPassesHttpLayerAndIsJudgedByTheService() throws Exception {
            allowed("/api/v1/approvals/1");
        }

        /** 인증 자체가 없으면 서비스 계층까지 갈 것도 없다. */
        @Test
        @WithAnonymousUser
        void anonymousStillBlocked() throws Exception {
            mvc.perform(get("/api/v1/approvals/1")).andExpect(status().is3xxRedirection());
        }
    }

    @Nested
    @DisplayName("인프라 관리는 운영자와 최고 관리자")
    class Infrastructure {

        @Test
        @WithMockUser(roles = "OPERATOR")
        void operatorCan() throws Exception {
            allowed("/deploy/servers");
            allowed("/deploy/server-groups");
            allowed("/api/v1/servers/1");
            allowed("/api/v1/server-groups/1");
            allowed("/api/v1/nexus/storage");
        }

        @Test
        @WithMockUser(roles = "SUPER_ADMIN")
        void superAdminCan() throws Exception {
            allowed("/api/v1/servers/1");
            allowed("/api/v1/nexus/storage");
        }

        /*
          서버 목록에는 접속 주소·포트·계정이 들어 있다. 폐쇄망이라도
          로그인한 아무나 인프라 지도를 통째로 볼 이유는 없다.
        */
        @Test
        @WithMockUser(roles = "DEVELOPER")
        void developerCannot() throws Exception {
            denied("/deploy/servers");
            denied("/api/v1/servers/1");
            denied("/api/v1/server-groups/1");
            denied("/api/v1/nexus/storage");
        }

        @Test
        @WithMockUser(roles = "PROJECT_MANAGER")
        void projectManagerCannot() throws Exception {
            denied("/api/v1/servers/1");
        }
    }

    /*
      운영 실행은 역할이 아니라 <프로젝트 단위 실행 권한>으로 통제한다.
      그래서 HTTP 레벨은 일부러 인증만 요구하고, 컨트롤러가 assertExecutePermission 으로 막는다.
      이 테스트는 그 설계가 <의도된 것>임을 고정한다 — 여기가 200 인 것을 보고
      "구멍이네" 하며 hasRole 을 붙이면 프로젝트 권한 모델이 무력해진다.
    */
    @Nested
    @DisplayName("운영 실행 API 는 HTTP 레벨에서 인증만 본다")
    class OperatorExecution {

        @Test
        @WithMockUser(roles = "DEVELOPER")
        void authenticatedUserPassesHttpLayer() throws Exception {
            allowed("/api/v1/operator/deploy");
        }

        @Test
        @WithAnonymousUser
        void anonymousStillBlocked() throws Exception {
            mvc.perform(get("/api/v1/operator/deploy")).andExpect(status().is3xxRedirection());
        }
    }

    /*
      CSRF 토큰 없는 상태 변경은 막혀야 한다. 세션 쿠키만 있으면 되는 요청이 열려 있으면
      사내 다른 페이지에서 배포를 걸 수 있다.
    */
    @Nested
    @DisplayName("CI 변수 관리 HTTP 인증과 CSRF 경계")
    class CiVariables {
        private static final String PATH = "/api/v1/gitlab/deploy-projects/3/ci-variables";

        @Test
        @WithAnonymousUser
        void anonymousCannotReadOrMutateEvenWithCsrf() throws Exception {
            mvc.perform(get(PATH)).andExpect(status().is3xxRedirection());
            mvc.perform(put(PATH + "/CI_BUILD_IMAGE").with(csrf())).andExpect(status().is3xxRedirection());
            mvc.perform(delete(PATH + "/CI_BUILD_IMAGE").with(csrf())).andExpect(status().is3xxRedirection());
        }

        @Test
        @WithMockUser(roles = "PROJECT_MANAGER")
        void authenticatedMutationsStillRequireCsrf() throws Exception {
            mvc.perform(put(PATH + "/CI_BUILD_IMAGE")).andExpect(status().isForbidden());
            mvc.perform(delete(PATH + "/CI_BUILD_IMAGE")).andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = "PROJECT_MANAGER")
        void projectPermissionIsJudgedByServiceAfterAuthenticationAndCsrf() throws Exception {
            mvc.perform(get(PATH)).andExpect(status().isOk());
            mvc.perform(put(PATH + "/CI_BUILD_IMAGE").with(csrf())).andExpect(status().isOk());
            mvc.perform(delete(PATH + "/CI_BUILD_IMAGE").with(csrf())).andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("CSRF")
    class Csrf {

        @Test
        @WithMockUser(roles = "SUPER_ADMIN")
        void stateChangingRequestWithoutTokenIsRejected() throws Exception {
            mvc.perform(post("/api/v1/users")).andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = "SUPER_ADMIN")
        void stateChangingRequestWithTokenPasses() throws Exception {
            mvc.perform(post("/api/v1/users").with(csrf())).andExpect(status().isOk());
        }
    }
}
