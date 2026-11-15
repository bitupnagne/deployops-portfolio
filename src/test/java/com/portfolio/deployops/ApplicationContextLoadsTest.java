package com.portfolio.deployops;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.deployops.init.DataInitializer;
import com.portfolio.deployops.service.StuckDeploymentReconciler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 애플리케이션 컨텍스트가 <b>실제로 조립되는지</b> 확인한다.
 *
 * <p>이 저장소의 테스트는 오랫동안 전부 Mockito 단위 테스트였다. 각 클래스는 잘 도는데
 * <b>그것들을 하나로 묶었을 때 뜨는지는 아무도 확인하지 않았다.</b> 빈 하나를 지우거나
 * 생성자 인자를 바꾸면 컴파일은 통과하고 단위 테스트도 전부 초록인데
 * <b>기동에서 죽는다</b> — 그리고 그 사실을 폐쇄망에 설치하고 나서 알게 된다.
 *
 * <p>여기서 잡는 것은 배선이다: 빈 정의 누락, 순환 참조, 생성자 인자 불일치,
 * {@code @MapperScan} 이 못 찾는 매퍼, 설정 프로퍼티 바인딩 실패.
 *
 * <h2>DB 없이 도는 이유</h2>
 * 배선을 보는 데 실제 데이터는 필요 없고, CI 에 MariaDB 10.3 을 띄울 수 없다
 * (그것까지 하려면 Testcontainers 가 필요한데 <b>오프라인 빌드</b> 전제와 어긋난다).
 * 그래서 DB 에 <b>닿지 않은 채로</b> 컨텍스트만 세운다.
 *
 * <ul>
 *   <li>Flyway 는 끈다 — 기동 시점에 접속해서 마이그레이션을 돌린다.</li>
 *   <li>Hikari 는 {@code initialization-fail-timeout=-1} 로 둔다.
 *       기본값이면 풀을 만들 때 연결을 시도하고 실패하면 기동을 멈춘다.</li>
 *   <li>{@link DataInitializer} 는 {@code ApplicationRunner} 라 컨텍스트가 뜬 <b>뒤에</b>
 *       실행되며 시드를 넣으려 DB 에 붙는다. 배선 확인과 무관하므로 대역으로 바꾼다.</li>
 *   <li>{@link StuckDeploymentReconciler} 도 같은 이유다 —
 *       {@code ApplicationReadyEvent} 를 받아 <b>기동 직후</b> 멈춘 배포를 조회한다.
 *       이 리스너에서 난 예외는 그대로 올라와 컨텍스트 로딩을 실패시킨다.</li>
 * </ul>
 *
 * <p>둘 다 <b>대역으로 바꾼다는 것 자체가 사실</b>이다 — 이 앱은 기동하면서 DB 를 두 번 만진다.
 * 그 경로가 도는지는 여기서 확인하지 않는다.
 *
 * <p><b>따라서 이 테스트가 초록이어도 MyBatis SQL 과 Flyway 스키마는 검증되지 않는다.</b>
 * 그 둘은 여전히 실 DB 가 필요하다({@code SchemaPortabilityTest} 가 문자열 수준에서만 지킨다).
 * 여기서 지키는 것은 "빈이 다 있고 서로 맞물리는가" 까지다.
 */
@SpringBootTest(properties = {
    "spring.flyway.enabled=false",
    // 실제로 붙지 않는다. 드라이버가 URL 형식만 인정하면 된다.
    "spring.datasource.url=jdbc:mariadb://localhost:65533/deployops_context_test",
    "spring.datasource.username=ctx",
    "spring.datasource.password=ctx",
    "spring.datasource.hikari.initialization-fail-timeout=-1",
    "spring.datasource.hikari.connection-timeout=250",
    // 시크릿은 운영에서 env 로 들어온다. 비워 두면 암호화 빈이 뜨지 못할 수 있으므로 넣어 준다.
    "app.crypto.secret-key=0123456789abcdef0123456789abcdef",
    "app.crypto.key-id=ctx-test",
    "app.sso.shared-secret=context-test-secret",
})
class ApplicationContextLoadsTest {

    /** ApplicationRunner 라 컨텍스트가 뜬 뒤 DB 로 간다 — 배선 확인과는 상관없다. */
    @MockitoBean
    private DataInitializer dataInitializer;

    /** ApplicationReadyEvent 를 받아 멈춘 배포를 조회한다. 여기서 난 예외는 로딩을 실패시킨다. */
    @MockitoBean
    private StuckDeploymentReconciler stuckDeploymentReconciler;

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("컨텍스트가 뜬다 — 빈 배선에 구멍이 없다")
    void contextLoads() {
        assertThat(context).isNotNull();
    }

    /*
      "떴다" 만으로는 약하다. 자동설정이 조용히 빠져도 컨텍스트는 뜨기 때문이다.
      실제로 요청을 받는 층이 다 올라왔는지 이름으로 확인한다.
    */
    @Test
    @DisplayName("웹·보안·MyBatis 층이 모두 올라와 있다")
    void theLayersThatServeRequestsAreAllPresent() {
        assertThat(context.getBeanNamesForType(
            org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class))
            .isNotEmpty();
        assertThat(context.containsBean("springSecurityFilterChain")).isTrue();
        assertThat(context.getBeanNamesForType(org.apache.ibatis.session.SqlSessionFactory.class))
            .isNotEmpty();
    }

    /*
      컨트롤러·서비스·매퍼가 <전부> 살아 있는지 본다.
      하나라도 빠지면 그 화면만 죽는데, 단위 테스트로는 절대 드러나지 않는다.
    */
    @Test
    @DisplayName("컨트롤러·서비스·매퍼가 빠짐없이 등록된다")
    void everyControllerServiceAndMapperIsRegistered() {
        /*
          지금 소스에 있는 수(@RestController 22 · @Service 41)를 하한으로 둔다.
          22 다 — GlobalExceptionHandler 는 @RestControllerAdvice 라 여기 안 센다.
          하나라도 <빠지면> 실패하고, 새로 <늘리는> 것은 그대로 통과한다 —
          지키려는 것이 "줄지 않는 것" 이므로 이 방향이 맞다.
          느슨하게 잡아 두면 빈이 사라져도 초록이라 아무것도 지키지 못한다.
        */
        assertThat(context.getBeanNamesForAnnotation(
            org.springframework.web.bind.annotation.RestController.class))
            .hasSizeGreaterThanOrEqualTo(22);
        assertThat(context.getBeanNamesForAnnotation(org.springframework.stereotype.Service.class))
            .hasSizeGreaterThanOrEqualTo(41);
        // @MapperScan 이 리포지터리를 못 찾으면 여기서 0 이 된다.
        assertThat(context.getBeanNamesForType(
            com.portfolio.deployops.repository.ProjectRepository.class))
            .isNotEmpty();
    }
}
