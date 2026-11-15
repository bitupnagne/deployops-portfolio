package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.portfolio.deployops.domain.ApprovalPolicy;
import com.portfolio.deployops.domain.HealthCheckMode;
import com.portfolio.deployops.domain.PipelineType;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.ServerGroup;
import com.portfolio.deployops.dto.ProjectReadiness;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class ProjectReadinessServiceTest {

    @Mock
    private GitlabService gitlabService;

    @Mock
    private NexusMetadataService nexusMetadataService;

    @InjectMocks
    private ProjectReadinessService service;

    @BeforeEach
    void registryEndpointIsReachableInBaselineCases() {
        lenient().when(nexusMetadataService.probeRegistryEndpoint()).thenReturn(java.util.Map.of(
            "state", "WARN",
            "detail", "Registry endpoint is reachable; push permission is not verified."));
    }

    private static Project.ProjectBuilder deployable() {
        return Project.builder()
            .id(1L)
            .name("worksystem-server")
            .pipelineType(PipelineType.DOCKER)
            .imageRepository("192.0.2.10:5000/worksystem/worksystem-server")
            .serverGroups(List.of(ServerGroup.builder().id(10L)
                .environment(com.portfolio.deployops.domain.ServerEnvironment.PROD)
                .servers(List.of(com.portfolio.deployops.domain.Server.builder().id(100L)
                    .serverType(com.portfolio.deployops.domain.ServerType.WAS).build())).build()))
            .approvalPolicy(ApprovalPolicy.builder().id(20L).build())
            .healthCheckMode(HealthCheckMode.SSH_LOCAL_CURL)
            .healthCheckPort(8080);
    }

    @Test
    void testOnlyActualTargetsStayDeployableWithProdSpecificWarning() {
        Project project = deployable().serverGroups(List.of(ServerGroup.builder().id(11L)
            .environment(com.portfolio.deployops.domain.ServerEnvironment.TEST)
            .servers(List.of(com.portfolio.deployops.domain.Server.builder().id(101L)
                .serverType(com.portfolio.deployops.domain.ServerType.WAS).build())).build())).build();
        ProjectReadiness readiness = service.evaluate(project);
        assertThat(readiness.isDeployable()).isTrue();
        assertThat(find(readiness, "PROD 대상 미설정").getLevel()).isEqualTo("WARNING");
        assertThat(find(readiness, "서버 그룹 매핑").getLevel()).isEqualTo("DONE");
    }

    private static ProjectReadiness.Item find(ProjectReadiness readiness, String title) {
        return readiness.getItems().stream()
            .filter(i -> i.getTitle().equals(title))
            .findFirst()
            .orElseThrow(() -> new AssertionError("항목이 없다: " + title));
    }

    @Test
    void everythingSetMeansDeployable() {
        ProjectReadiness readiness = service.evaluate(deployable().build());

        assertThat(readiness.isDeployable()).isTrue();
        assertThat(readiness.getBlockingCount()).isZero();
    }

    @Test
    void brokenNexusRegistryBlocksDeploymentReadiness() {
        when(nexusMetadataService.probeRegistryEndpoint()).thenReturn(java.util.Map.of(
            "state", "BROKEN",
            "detail", "Nexus 사용 조건 동의가 완료되지 않아 Docker Registry가 요청을 차단합니다."));

        ProjectReadiness readiness = service.evaluate(deployable().build());

        assertThat(find(readiness, "Nexus Docker Registry").getLevel()).isEqualTo("BLOCKER");
        assertThat(find(readiness, "Nexus Docker Registry").getWhy()).contains("사용 조건 동의가 완료되지 않아");
        assertThat(readiness.isDeployable()).isFalse();
    }

    @Test
    void reachableRegistryWithUnverifiedPushPermissionsIsShownAsWarning() {
        when(nexusMetadataService.probeRegistryEndpoint()).thenReturn(java.util.Map.of(
            "state", "WARN",
            "detail", "Registry v2 endpoint에 연결됐으나 push 권한은 별도 확인이 필요합니다."));

        ProjectReadiness readiness = service.evaluate(deployable().build());

        assertThat(find(readiness, "Nexus Docker Registry 권한 확인").getLevel()).isEqualTo("WARNING");
        assertThat(readiness.isDeployable()).isTrue();
    }

    /*
      핵심 회귀 방지.

      예전에는 파이프라인 타입이 DOCKER 일 때만 이미지 저장소를 봤다. 그런데 배포 경로는
      타입을 보지 않는다 — RemoteDeployService 가 서버마다 imageRef 를 먼저 구하고,
      저장소가 비어 있으면 "프로젝트 이미지 저장소가 비어 있습니다" 로 죽는다.
      정적 배포조차 이미지 안에서 산출물을 꺼내 보내는 구조다.

      즉 JAR·정적 프로젝트는 초록불을 보고 배포를 눌렀다가 실행 단계에서 처음 막혔다.
      이 판정이 없애려던 경험을 이 판정이 만들고 있었다.
    */
    @ParameterizedTest
    @EnumSource(PipelineType.class)
    void missingImageRepositoryBlocksEveryPipelineType(PipelineType pipelineType) {
        Project project = deployable()
            .pipelineType(pipelineType)
            .imageRepository(null)
            .build();

        ProjectReadiness readiness = service.evaluate(project);

        assertThat(find(readiness, "이미지 저장소").getLevel()).isEqualTo("BLOCKER");
        assertThat(readiness.isDeployable()).isFalse();
    }

    @Test
    void blankImageRepositoryCountsAsMissing() {
        ProjectReadiness readiness = service.evaluate(deployable().imageRepository("   ").build());

        assertThat(find(readiness, "이미지 저장소").getLevel()).isEqualTo("BLOCKER");
    }

    @Test
    void noServerGroupMeansNowhereToDeploy() {
        ProjectReadiness readiness = service.evaluate(deployable().serverGroups(List.of()).build());

        assertThat(find(readiness, "서버 그룹 매핑").getLevel()).isEqualTo("BLOCKER");
        assertThat(readiness.isDeployable()).isFalse();
    }

    // 전역 실행 정책이 PROD 승인 대기를 요구할 수 있어도 프로젝트별 지정 승인자와
    // 자기 승인 제한은 정책이 없으면 적용되지 않는다.
    @Test
    void missingApprovalPolicyWarnsButStillDeployable() {
        ProjectReadiness readiness = service.evaluate(deployable().approvalPolicy(null).build());

        assertThat(find(readiness, "승인 정책").getLevel()).isEqualTo("WARNING");
        assertThat(find(readiness, "승인 정책").getWhy())
            .contains("실행 정책에 따르지만")
            .contains("자기 승인 제한은 적용되지 않습니다");
        assertThat(readiness.isDeployable()).isTrue();
    }

    @Test
    void healthCheckOffWarnsBecauseFailureLooksLikeSuccess() {
        ProjectReadiness readiness = service.evaluate(
            deployable().healthCheckMode(HealthCheckMode.NONE).build());

        assertThat(find(readiness, "헬스체크").getLevel()).isEqualTo("WARNING");
        assertThat(readiness.isDeployable()).isTrue();
    }

    @Test
    void healthCheckWithoutPortIsNotOn() {
        ProjectReadiness readiness = service.evaluate(deployable().healthCheckPort(null).build());

        assertThat(find(readiness, "헬스체크").getLevel()).isEqualTo("WARNING");
    }

    @Test
    void containerRunningHealthDoesNotRequireHttpPort() {
        ProjectReadiness readiness = service.evaluate(deployable()
            .healthCheckMode(HealthCheckMode.CONTAINER_RUNNING)
            .healthCheckPort(null)
            .build());

        assertThat(find(readiness, "헬스체크").getLevel()).isEqualTo("DONE");
    }

    /*
      CI 파일은 성격이 다르다 — 다른 항목은 배포를 누르면 막히지만, 이건 아무 일도 일어나지 않는다.
      빌드가 안 도니 이미지가 안 생기고, 배포 화면에는 "배포할 이미지가 없다" 로만 보인다.
    */
    @Test
    void missingCiFileBlocksWhenRepositoryCanBeRead() throws Exception {
        when(gitlabService.repositoryFileExists(
            eq("token"), isNull(), anyLong(), eq(".gitlab-ci.yml"), eq("main")))
            .thenReturn(false);

        ProjectReadiness readiness =
            service.evaluate(deployable().gitlabProjectId(100L).build(), "token");

        assertThat(find(readiness, "빌드 설정 파일 (.gitlab-ci.yml)").getLevel()).isEqualTo("BLOCKER");
        assertThat(readiness.isDeployable()).isFalse();
    }

    @Test
    void existingCiFileIsDone() throws Exception {
        when(gitlabService.repositoryFileExists(
            eq("token"), isNull(), anyLong(), eq(".gitlab-ci.yml"), eq("develop")))
            .thenReturn(true);

        ProjectReadiness readiness = service.evaluate(
            deployable().gitlabProjectId(100L).defaultBranch("develop").build(), "token");

        assertThat(find(readiness, "빌드 설정 파일 (.gitlab-ci.yml)").getLevel()).isEqualTo("DONE");
        assertThat(readiness.isDeployable()).isTrue();
    }

    /*
      있는지 없는지 모르면서 "없습니다" 라고 말하면 있는 CI 를 다시 만들게 된다.
      그래서 GitLab 을 못 읽으면 항목을 아예 넣지 않는다.
    */
    @Test
    void gitlabFailureBlocksReadinessInsteadOfReportingReady() throws Exception {
        when(gitlabService.repositoryFileExists(
            anyString(), isNull(), anyLong(), anyString(), anyString()))
            .thenThrow(new RuntimeException("GitLab 접속 실패"));

        ProjectReadiness readiness =
            service.evaluate(deployable().gitlabProjectId(100L).build(), "token");

        assertThat(find(readiness, "GitLab 연결 확인").getLevel()).isEqualTo("BLOCKER");
        assertThat(readiness.isDeployable()).isFalse();
    }

    @Test
    void withoutTokenGitlabIsNotContacted() {
        ProjectReadiness readiness = service.evaluate(deployable().gitlabProjectId(100L).build());

        assertThat(find(readiness, "GitLab 연결 확인").getLevel()).isEqualTo("BLOCKER");
        assertThat(readiness.isDeployable()).isFalse();
        verifyNoInteractions(gitlabService);
    }

    /** 항목마다 어느 탭에서 고치는지 알려 줘야 화면이 그 탭을 열어 준다. */
    @Test
    void everyItemCarriesReasonAndAnchor() {
        ProjectReadiness readiness = service.evaluate(deployable().imageRepository(null).build());

        assertThat(readiness.getItems()).isNotEmpty().allSatisfy(item -> {
            assertThat(item.getWhy()).isNotBlank();
            assertThat(item.getAnchor()).isNotBlank();
            assertThat(item.getLevel()).isIn("BLOCKER", "WARNING", "DONE");
        });
    }
}
