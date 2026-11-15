package com.portfolio.deployops.init;

import com.portfolio.deployops.domain.ApprovalPolicy;
import com.portfolio.deployops.domain.BuildToolType;
import com.portfolio.deployops.domain.HealthCheckMode;
import com.portfolio.deployops.domain.PipelineType;
import com.portfolio.deployops.domain.PolicyType;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.Server;
import com.portfolio.deployops.domain.ServerEnvironment;
import com.portfolio.deployops.domain.ServerGroup;
import com.portfolio.deployops.domain.ServerStatus;
import com.portfolio.deployops.domain.ServerType;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.domain.UserRole;
import com.portfolio.deployops.repository.ApprovalPolicyRepository;
import com.portfolio.deployops.repository.ProjectRepository;
import com.portfolio.deployops.repository.ServerGroupRepository;
import com.portfolio.deployops.repository.ServerRepository;
import com.portfolio.deployops.repository.UserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
@org.springframework.core.annotation.Order(0)
public class DataInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final ServerGroupRepository serverGroupRepository;
    private final ServerRepository serverRepository;
    private final ApprovalPolicyRepository approvalPolicyRepository;
    private final PasswordEncoder passwordEncoder;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    // 초기 부트스트랩 관리자 비밀번호 — 운영에서는 app.bootstrap.admin-password 로 반드시 재정의할 것.
    @org.springframework.beans.factory.annotation.Value("${app.bootstrap.admin-password:${BOOTSTRAP_ADMIN_PASSWORD:}}")
    private String bootstrapAdminPassword;

    @org.springframework.beans.factory.annotation.Value("${app.bootstrap.sample-data-enabled:${BOOTSTRAP_SAMPLE_DATA_ENABLED:false}}")
    private boolean sampleDataEnabled;

    @org.springframework.beans.factory.annotation.Value("${app.public-demo.enabled:false}")
    private boolean publicDemo;

    @org.springframework.beans.factory.annotation.Value("${app.public-demo.username:${PORTFOLIO_DEMO_USERNAME:}}")
    private String publicDemoUsername;
    @org.springframework.beans.factory.annotation.Value("${app.public-demo.password:${PORTFOLIO_DEMO_PASSWORD:}}")
    private String publicDemoPassword;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (publicDemo) {
            PublicDemoBootstrapGuard.requireIsolatedDatabase(jdbc, publicDemoUsername, publicDemoPassword);
        }
        if (userRepository.count() > 0) {
            return;
        }

        log.info("=== Initial data bootstrap start ===");
        if (bootstrapAdminPassword == null || bootstrapAdminPassword.isBlank()) {
            throw new IllegalStateException("초기 관리자 비밀번호를 BOOTSTRAP_ADMIN_PASSWORD로 설정해 주세요.");
        }

        userRepository.save(User.builder()
            .loginId("admin")
            .password(passwordEncoder.encode(bootstrapAdminPassword))
            .name("관리자")
            .role(UserRole.SUPER_ADMIN)
            .forcePasswordChange(true)   // 최초 로그인 후 비밀번호 변경 유도(P0)
            .build());

        ApprovalPolicy policy = approvalPolicyRepository.save(ApprovalPolicy.builder()
            .name("1단계 승인(운영)")
            .policyType(PolicyType.ANY_ONE)
            .allowSelfApproval(false)
            .description("운영 결재자 1명 승인")
            .build());

        if (!sampleDataEnabled) {
            log.info("=== Initial data bootstrap complete (core only) ===");
            return;
        }

        ServerGroup prodWasGroup = serverGroupRepository.save(ServerGroup.builder()
            .name("PROD-WAS Group")
            .environment(ServerEnvironment.PROD)
            .rollingIntervalSec(30)
            .description("Production WAS rolling deployment targets")
            .build());

        ServerGroup prodWebGroup = serverGroupRepository.save(ServerGroup.builder()
            .name("PROD-WEB Group")
            .environment(ServerEnvironment.PROD)
            .rollingIntervalSec(30)
            .description("Production WEB rolling deployment targets")
            .build());

        ServerGroup testWasGroup = serverGroupRepository.save(ServerGroup.builder()
            .name("TEST-WAS Group")
            .environment(ServerEnvironment.TEST)
            .rollingIntervalSec(10)
            .description("Test WAS deployment targets")
            .build());

        ServerGroup testWebGroup = serverGroupRepository.save(ServerGroup.builder()
            .name("TEST-WEB Group")
            .environment(ServerEnvironment.TEST)
            .rollingIntervalSec(10)
            .description("Test WEB deployment targets")
            .build());

        serverRepository.save(Server.builder()
            .name("deploy01")
            .ipAddress("192.0.2.10")
            .environment(ServerEnvironment.PROD)
            .serverType(ServerType.DEPLOY)
            .sshPort(22)
            .deployAccount("deploy")
            .deployPath("/opt/deploy-system")
            .orderIndex(1)
            .status(ServerStatus.NORMAL)
            .memo("MariaDB 11.4 + Java 21 + deploy-system")
            .build());

        serverRepository.save(Server.builder()
            .name("gitlab01")
            .ipAddress("192.0.2.20")
            .environment(ServerEnvironment.PROD)
            .serverType(ServerType.GITLAB)
            .sshPort(22)
            .deployAccount("deploy")
            .deployPath("/etc/gitlab")
            .orderIndex(1)
            .status(ServerStatus.NORMAL)
            .memo("Rocky 8.10 host install")
            .build());

        serverRepository.save(Server.builder()
            .name("nexus01")
            .ipAddress("192.0.2.30")
            .environment(ServerEnvironment.PROD)
            .serverType(ServerType.NEXUS)
            .sshPort(22)
            .deployAccount("deploy")
            .deployPath("/opt/sonatype/nexus")
            .orderIndex(1)
            .status(ServerStatus.NORMAL)
            .memo("Rocky 8.10 host install")
            .build());

        serverRepository.save(Server.builder()
            .name("test-was1")
            .ipAddress("192.0.2.111")
            .environment(ServerEnvironment.TEST)
            .serverType(ServerType.WAS)
            .serverGroup(testWasGroup)
            .sshPort(22)
            .deployAccount("deploy")
            .deployPath("/opt/apps/payment-api")
            .orderIndex(1)
            .status(ServerStatus.NORMAL)
            .build());

        serverRepository.save(Server.builder()
            .name("test-web1")
            .ipAddress("192.0.2.121")
            .environment(ServerEnvironment.TEST)
            .serverType(ServerType.WEB)
            .serverGroup(testWebGroup)
            .sshPort(22)
            .deployAccount("deploy")
            .deployPath("/opt/apps/payment-web")
            .orderIndex(1)
            .status(ServerStatus.NORMAL)
            .build());

        serverRepository.save(Server.builder()
            .name("prod-was1")
            .ipAddress("192.0.2.211")
            .environment(ServerEnvironment.PROD)
            .serverType(ServerType.WAS)
            .serverGroup(prodWasGroup)
            .sshPort(22)
            .deployAccount("deploy")
            .deployPath("/opt/apps/payment-api")
            .orderIndex(1)
            .status(ServerStatus.NORMAL)
            .build());

        serverRepository.save(Server.builder()
            .name("prod-was2")
            .ipAddress("192.0.2.212")
            .environment(ServerEnvironment.PROD)
            .serverType(ServerType.WAS)
            .serverGroup(prodWasGroup)
            .sshPort(22)
            .deployAccount("deploy")
            .deployPath("/opt/apps/payment-api")
            .orderIndex(2)
            .status(ServerStatus.NORMAL)
            .build());

        serverRepository.save(Server.builder()
            .name("prod-web1")
            .ipAddress("192.0.2.221")
            .environment(ServerEnvironment.PROD)
            .serverType(ServerType.WEB)
            .serverGroup(prodWebGroup)
            .sshPort(22)
            .deployAccount("deploy")
            .deployPath("/opt/apps/payment-web")
            .orderIndex(1)
            .status(ServerStatus.NORMAL)
            .build());

        serverRepository.save(Server.builder()
            .name("prod-web2")
            .ipAddress("192.0.2.222")
            .environment(ServerEnvironment.PROD)
            .serverType(ServerType.WEB)
            .serverGroup(prodWebGroup)
            .sshPort(22)
            .deployAccount("deploy")
            .deployPath("/opt/apps/payment-web")
            .orderIndex(2)
            .status(ServerStatus.NORMAL)
            .build());

        Project paymentApi = projectRepository.save(Project.builder()
            .name("payment-api")
            .description("결제 API 서비스")
            .gitlabRepoUrl("https://gitlab.example.com/backend/payment-api")
            .gitlabProjectId(101L)
            .defaultBranch("main")
            .pipelineType(PipelineType.DOCKER)
            .buildTool(BuildToolType.MAVEN)
            .buildFilePath("pom.xml")
            .buildCommand("./mvnw -U clean package -DskipTests")
            .artifactPath("target/payment-api.jar")
            .dockerfilePath("Dockerfile")
            .dockerContextPath(".")
            .imageRepository("nexus.example.com:18443/payment/payment-api")
            .imageTagPattern("${branch}-${shortSha}-${pipelineId}")
            .nexusMavenReleaseRepository("maven-releases")
            .nexusMavenSnapshotRepository("maven-snapshots")
            .nexusDockerRepository("docker-hosted")
            .healthCheckMode(HealthCheckMode.SSH_LOCAL_CURL)
            .healthCheckPath("/actuator/health")
            .healthCheckPort(8080)
            .remoteDeployScriptPath("/usr/local/deploy/bin/deploy-was-app.sh")
            .approvalPolicy(policy)
            .active(true)
            .build());
        projectRepository.replaceServerGroupMappings(paymentApi.getId(), List.of(prodWasGroup.getId(), testWasGroup.getId()));
        paymentApi.setServerGroups(List.of(prodWasGroup, testWasGroup));

        Project paymentWeb = projectRepository.save(Project.builder()
            .name("payment-web")
            .description("결제 WEB 서비스")
            .gitlabRepoUrl("https://gitlab.example.com/frontend/payment-web")
            .gitlabProjectId(102L)
            .defaultBranch("main")
            .pipelineType(PipelineType.DOCKER)
            .buildTool(BuildToolType.GRADLE)
            .buildFilePath("build.gradle")
            .buildCommand("./gradlew clean build -x test")
            .artifactPath("build/libs/payment-web.jar")
            .dockerfilePath("Dockerfile")
            .dockerContextPath(".")
            .imageRepository("nexus.example.com:18443/payment/payment-web")
            .imageTagPattern("${branch}-${shortSha}-${pipelineId}")
            .nexusMavenReleaseRepository("maven-releases")
            .nexusMavenSnapshotRepository("maven-snapshots")
            .nexusDockerRepository("docker-hosted")
            .healthCheckMode(HealthCheckMode.SSH_LOCAL_CURL)
            .healthCheckPath("/health")
            .healthCheckPort(8080)
            .remoteDeployScriptPath("/usr/local/deploy/bin/deploy-web-app.sh")
            .approvalPolicy(policy)
            .active(true)
            .build());
        projectRepository.replaceServerGroupMappings(paymentWeb.getId(), List.of(prodWebGroup.getId(), testWebGroup.getId()));
        paymentWeb.setServerGroups(List.of(prodWebGroup, testWebGroup));

        log.info("=== Initial data bootstrap complete ===");
    }
}
