package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.deployops.dto.ApiResponse;
import com.portfolio.deployops.service.SystemSettingService;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import static org.mockito.Mockito.mock;

/**
 * 연동 테스트 API 의 권한을 고정한다.
 *
 * <p>이 API 는 받은 값으로 <b>배포서버가 바깥으로 요청을 건다.</b> 배포서버는 개발망과 운영망
 * 양쪽에 닿는 자리라, 임의의 주소를 넣어 응답을 떠볼 수 있으면 내부망을 훑는 수단이 된다.
 * {@code /test/deploy-ssh} 는 <b>지정한 실행파일을 실제로 돌린다.</b>
 *
 * <p>화면(<code>/deploy/integration</code>)은 SUPER_ADMIN 전용이었지만
 * <b>API 는 아무나 부를 수 있었다</b> — SecurityConfig 매처에 이 경로가 없어
 * {@code anyRequest().authenticated()} 로 흘러내렸다. 화면이 막고 있으니 막힌 것처럼 보였을 뿐,
 * {@code curl} 한 줄이면 로그인한 개발자도 닿았다.
 *
 * <p>인가는 <b>메서드에 붙어 있어 URL 테스트로는 잡히지 않으므로</b> 여기서 따로 지킨다.
 * 막히는 쪽은 {@link AccessDeniedException} 이 나야 하고, 통과하는 쪽은 메서드 본문까지 가야 한다.
 * 통과 확인은 <b>주소를 비워</b> 호출한다 — 권한만 보려는 것이지 진짜로 바깥에 요청을 걸 이유는 없다.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationApiAuthorizationTest.MethodSecurityConfig.class)
class IntegrationApiAuthorizationTest {

    @Autowired
    private IntegrationApiController controller;

    @EnableMethodSecurity
    @Configuration
    static class MethodSecurityConfig {
        @Bean
        IntegrationApiController integrationApiController() {
            return new IntegrationApiController(mock(SystemSettingService.class));
        }
    }

    /** 주소가 비면 바깥으로 나가기 전에 되돌아온다 — 권한을 통과했다는 뜻. */
    private void reachesMethodBody(java.util.function.Supplier<Object> call) {
        assertThatCode(call::get).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("최고 관리자는 전부 부를 수 있다")
    @WithMockUser(roles = "SUPER_ADMIN")
    void superAdminReachesEveryTest() {
        reachesMethodBody(() -> controller.testGitlab(Map.of()));
        reachesMethodBody(() -> controller.testNexus(Map.of()));
        reachesMethodBody(() -> controller.testOffice(Map.of()));
        reachesMethodBody(() -> controller.testDeployPublicUrl(Map.of()));
    }

    @Test
    @DisplayName("프로젝트 관리자는 전역 연동 점검을 호출할 수 없다")
    @WithMockUser(roles = "PROJECT_MANAGER")
    void projectManagerReachesNothing() {
        assertThatThrownBy(() -> controller.testGitlab(Map.of()))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.testNexus(Map.of()))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.testDeploySsh(Map.of()))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.testOffice(Map.of()))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.testDeployPublicUrl(Map.of()))
            .isInstanceOf(AccessDeniedException.class);
    }

    /*
      회귀 방지의 핵심. 예전에는 이 역할로 전부 통과했다 —
      임의 주소로 내부망을 떠보고, ssh 자리에 아무 실행파일이나 넣어 돌릴 수 있었다.
    */
    @Test
    @DisplayName("개발자는 하나도 부를 수 없다")
    @WithMockUser(roles = "DEVELOPER")
    void developerReachesNothing() {
        assertThatThrownBy(() -> controller.testGitlab(Map.of()))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.testNexus(Map.of()))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.testDeploySsh(Map.of()))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.testOffice(Map.of()))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.testDeployPublicUrl(Map.of()))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("운영자·승인자도 인프라 점검은 못 부른다")
    @WithMockUser(roles = "OPERATOR")
    void operatorReachesNothing() {
        assertThatThrownBy(() -> controller.testNexus(Map.of()))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.testDeploySsh(Map.of()))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("미인증은 당연히 막힌다")
    @WithAnonymousUser
    void anonymousReachesNothing() {
        assertThatThrownBy(() -> controller.testGitlab(Map.of()))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.testDeploySsh(Map.of()))
            .isInstanceOf(AccessDeniedException.class);
    }

    /*
      ssh 점검은 <지정한 실행파일을 실제로 돌린다>. 그래서 통과 경로를 테스트에서 부르지 않는다 —
      막히는 쪽만 확인한다. 이 엔드포인트가 위험한 이유 자체가 여기에 있다.
    */
    @Test
    @DisplayName("ssh 점검은 최고 관리자 외에는 메서드 본문에 닿지 않는다")
    @WithMockUser(roles = "APPROVER")
    void sshCheckNeverRunsForNonAdmin() {
        assertThatThrownBy(() -> controller.testDeploySsh(
            Map.of("sshBinary", "/bin/echo", "keyPath", "/tmp/x")))
            .isInstanceOf(AccessDeniedException.class);
    }

    /*
      통과한 호출이 <메서드 본문까지 갔다>는 것을 응답으로 확인한다.
      권한에서 막힌 것과 본문에서 되돌아온 것을 구분하지 못하면
      이 테스트는 "예외만 안 나면 통과" 가 되어 아무것도 지키지 못한다.
    */
    @Test
    @DisplayName("통과한 호출은 주소가 비었다는 안내로 되돌아온다")
    @WithMockUser(roles = "SUPER_ADMIN")
    void allowedCallStopsBeforeGoingOut() {
        ApiResponse<?> result = controller.testGitlab(Map.of());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("URL_REQUIRED");
    }
}
