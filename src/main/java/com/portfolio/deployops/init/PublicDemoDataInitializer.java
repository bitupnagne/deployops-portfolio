package com.portfolio.deployops.init;

import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.repository.DeployRequestRepository;
import com.portfolio.deployops.repository.ProjectRepository;
import com.portfolio.deployops.repository.StatusHistoryRepository;
import com.portfolio.deployops.repository.UserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(100)
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.public-demo.enabled", havingValue = "true")
public class PublicDemoDataInitializer implements ApplicationRunner {
    // 공개 체험 계정이며 운영 관리자 자격증명과 무관하다. 모든 작업 경로는 데모 필터로 제한된다.
    @org.springframework.beans.factory.annotation.Value("${app.public-demo.username:${PORTFOLIO_DEMO_USERNAME:}}")
    private String loginId;
    @org.springframework.beans.factory.annotation.Value("${app.public-demo.password:${PORTFOLIO_DEMO_PASSWORD:}}")
    private String demoPassword;
    private final UserRepository users;
    private final ProjectRepository projects;
    private final DeployRequestRepository requests;
    private final StatusHistoryRepository histories;
    private final PasswordEncoder passwords;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        PublicDemoBootstrapGuard.requireIsolatedDatabase(jdbc, loginId, demoPassword);
        User visitor = users.findByLoginId(loginId.trim()).orElseGet(() -> users.save(User.builder()
            .loginId(loginId.trim()).password(passwords.encode(demoPassword)).name("포트폴리오 방문자")
            .role(UserRole.SUPER_ADMIN).forcePasswordChange(false).build()));
        List<Project> sampleProjects = projects.findAll();
        if (sampleProjects.isEmpty()) {
            throw new IllegalStateException("공개 데모는 빈 전용 DB와 BOOTSTRAP_SAMPLE_DATA_ENABLED=true로 시작해 주세요.");
        }
        DeployStatus[] statuses = {DeployStatus.COMPLETED, DeployStatus.TEST_CONFIRMED,
            DeployStatus.APPROVAL_PENDING, DeployStatus.REJECTED, DeployStatus.TEST_FAILED, DeployStatus.COMPLETED};
        String[] reasons = {"주문 API 응답 성능 개선", "관리 화면 접근성 개선", "정기 릴리스 승인 검토",
            "검토 의견 반영을 위한 반려 예시", "테스트 환경 상태 확인 실패 예시", "사용자 알림 기능 릴리스"};
        for (int i = 0; i < statuses.length; i++) {
            String number = "DEMO-" + String.format("%04d", i + 1);
            if (requests.existsByReqNo(number)) continue;
            DeployEnvironment environment = statuses[i] == DeployStatus.TEST_CONFIRMED || statuses[i] == DeployStatus.TEST_FAILED
                ? DeployEnvironment.TEST : DeployEnvironment.PROD;
            DeployRequest request = requests.save(DeployRequest.builder().reqNo(number)
                .project(sampleProjects.get(i % sampleProjects.size())).environment(environment)
                .branch("main").imageTag("demo-1.0." + i).commitSha("d".repeat(40))
                .reason("[가상 데모] " + reasons[i]).status(statuses[i]).requestedBy(visitor).build());
            for (DeployStatus step : timeline(statuses[i])) {
                histories.save(StatusHistory.builder().deployRequest(request).status(step).build());
            }
        }
    }

    private List<DeployStatus> timeline(DeployStatus status) {
        return switch (status) {
            case COMPLETED -> List.of(DeployStatus.APPROVAL_PENDING, DeployStatus.APPROVAL_COMPLETED,
                DeployStatus.PROD_DEPLOYING, DeployStatus.COMPLETED);
            case TEST_CONFIRMED -> List.of(DeployStatus.TEST_REQUESTED, DeployStatus.TEST_BUILDING,
                DeployStatus.TEST_DEPLOYING, DeployStatus.TEST_DEPLOYED, DeployStatus.TEST_CONFIRMED);
            case REJECTED -> List.of(DeployStatus.APPROVAL_PENDING, DeployStatus.REJECTED);
            case TEST_FAILED -> List.of(DeployStatus.TEST_REQUESTED, DeployStatus.TEST_BUILDING, DeployStatus.TEST_FAILED);
            default -> List.of(status);
        };
    }
}
