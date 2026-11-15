package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.HealthCheckMode;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.dto.ProjectReadiness;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 프로젝트가 배포 가능한 상태인지 판정한다.
 *
 * <p>판정 기준은 <b>실제 배포 경로가 읽는 것</b>에서 가져왔다. 화면에 칸이 있다는 이유로
 * 항목을 늘리지 않는다 — 그러면 다시 "46개가 전부 중요해 보이는" 자리로 돌아간다.
 *
 * <ul>
 *   <li>이미지 저장소 — {@code RemoteDeployService} 가 비어 있으면 예외를 던진다.</li>
 *   <li>서버 그룹 매핑 — 대상 서버가 0대면 배포가 시작되지 못한다.</li>
 *   <li>승인 정책 — 없어도 배포는 된다. PROD 승인 대기 여부는 전역 실행 정책이
 *       결정하지만, 프로젝트 정책이 없으면 지정 승인자와 자기 승인 제한을 적용하지 못한다.</li>
 *   <li>헬스체크 — 꺼 두면 컨테이너가 떴는지 확인하지 않고 성공으로 본다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectReadinessService {

    private final GitlabService gitlabService;
    private final NexusMetadataService nexusMetadataService;

    private static final String BLOCKER = "BLOCKER";
    private static final String WARNING = "WARNING";
    private static final String DONE = "DONE";

    public ProjectReadiness evaluate(Project project) {
        return evaluate(project, null);
    }

    /**
     * @param gitlabToken 있으면 저장소에 CI 파일이 있는지까지 본다. 없으면 그 항목은 건너뛴다.
     */
    public ProjectReadiness evaluate(Project project, String gitlabToken) {
        List<ProjectReadiness.Item> items = new ArrayList<>();

        /*
          이미지는 파이프라인 타입과 무관하게 <언제나> 필요하다.

          예전에는 "JAR·정적은 이미지를 쓰지 않는다" 고 보고 DOCKER 일 때만 검사했다.
          배포 경로는 그렇지 않다 — RemoteDeployService 는 서버마다 imageRef 를 먼저 구하고,
          이미지 저장소가 비어 있으면 타입을 보지 않고 "프로젝트 이미지 저장소가 비어 있습니다" 로 죽는다.
          정적 배포도 이미지 안에서 산출물을 꺼내 보내는 구조라 이미지가 있어야 한다.

          그래서 예전 판정은 JAR·정적 프로젝트에게 <준비 완료라고 거짓말>을 했다.
          화면에서 초록불을 보고 배포를 눌렀다가 실행 단계에서 처음 막히는 것이
          이 판정이 없애려던 바로 그 경험이다.
        */
        boolean hasImage = notBlank(project.getImageRepository());
        items.add(item(hasImage ? DONE : BLOCKER,
            "이미지 저장소",
            "배포할 때 어느 이미지를 내려받을지 정하는 값입니다. 비어 있으면 배포가 시작되자마자 멈춥니다.",
            "cicd"));

        // findByIdWithDetails 로 읽어 온 프로젝트면 이미 채워져 있다.
        boolean hasGroups = project.getServerGroups() != null && !project.getServerGroups().isEmpty();
        items.add(item(hasGroups ? DONE : BLOCKER,
            "서버 그룹 매핑",
            "어느 서버로 나갈지 정합니다. 매핑이 없으면 대상 서버가 0대라 배포할 곳이 없습니다.",
            "servers"));

        if (hasGroups && EffectiveDeployTargets.resolve(project,
                com.portfolio.deployops.domain.DeployEnvironment.PROD,
                com.portfolio.deployops.domain.DeployScope.ALL).isEmpty()) {
            items.add(item(WARNING, "PROD 대상 미설정",
                "TEST 전용 프로젝트로 사용할 수 있습니다. PROD 요청 전에 운영 WAS 또는 WEB 실대상을 매핑하세요.", "servers"));
        }
        boolean hasPolicy = project.getApprovalPolicy() != null;
        items.add(item(hasPolicy ? DONE : WARNING,
            "승인 정책",
            hasPolicy
                ? "배포 요청이 이 정책을 따라 승인을 받습니다."
                : "프로젝트 승인 정책이 없습니다. PROD 승인 대기 여부는 실행 정책에 따르지만, "
                    + "지정 승인자와 자기 승인 제한은 적용되지 않습니다. 운영 프로젝트에는 정책을 지정하세요.",
            "policy"));

        boolean healthOn = project.getHealthCheckMode() != null
            && project.getHealthCheckMode() != HealthCheckMode.NONE
            && (project.getHealthCheckMode() == HealthCheckMode.CONTAINER_RUNNING
                || project.getHealthCheckPort() != null);
        items.add(item(healthOn ? DONE : WARNING,
            "헬스체크",
            healthOn
                ? "배포 후 실제로 떴는지 확인합니다."
                : "꺼져 있으면 컨테이너가 뜨지 않아도 배포를 성공으로 봅니다.",
            "cicd"));

        Map<String, Object> registryProbe = nexusMetadataService.probeRegistryEndpoint();
        if (registryProbe != null) {
            String registryState = String.valueOf(registryProbe.getOrDefault("state", "BROKEN"));
            String registryDetail = String.valueOf(registryProbe.getOrDefault(
                "detail", "Nexus Docker Registry 연결 상태를 확인하지 못했습니다."));
            if ("BROKEN".equals(registryState) || "UNCONFIGURED".equals(registryState)) {
                items.add(item(BLOCKER,
                    "Nexus Docker Registry",
                    registryDetail,
                    "cicd"));
            } else if ("WARN".equals(registryState)) {
                items.add(item(WARNING,
                    "Nexus Docker Registry 권한 확인",
                    registryDetail,
                    "cicd"));
            }
        }

        /*
          CI 파일이 없으면 이미지가 <영영> 만들어지지 않는다.

          다른 항목과 성격이 다르다 — 나머지는 배포 요청을 눌렀을 때 막히지만,
          이건 아무 일도 일어나지 않는다. 빌드가 돌지 않으니 이미지가 안 생기고,
          배포 화면에서는 "배포할 이미지가 없다" 로만 보인다.
          프로젝트를 처음 만든 사람이 가장 오래 헤매는 자리다.

          GitLab 을 못 읽으면 CI가 없다고 단정하지 않고 연결 확인 blocker를 표시한다.
          확인 불가 상태를 준비 완료로 통과시키면 실제 실행 시점에 처음 실패하기 때문이다.
        */
        if (project.getGitlabProjectId() != null && gitlabToken == null) {
            items.add(item(BLOCKER,
                "GitLab 연결 확인",
                "GitLab token이 없어 CI 파일과 Runner 준비 상태를 확인할 수 없습니다.",
                "cicd"));
        } else if (gitlabToken != null && project.getGitlabProjectId() != null) {
            String branch = project.getDefaultBranch() == null || project.getDefaultBranch().isBlank()
                ? "main" : project.getDefaultBranch();
            try {
                boolean hasCi = gitlabService.repositoryFileExists(
                    gitlabToken, null, project.getGitlabProjectId(), ".gitlab-ci.yml", branch);
                items.add(item(hasCi ? DONE : BLOCKER,
                    "빌드 설정 파일 (.gitlab-ci.yml)",
                    hasCi
                        ? "커밋을 올리면 이 파일을 따라 빌드가 돕니다."
                        : "저장소에 이 파일이 없어 빌드가 돌지 않습니다. 빌드가 없으면 배포할 이미지도 만들어지지 않습니다.",
                    "cicd"));
            } catch (Exception e) {
                log.warn("배포 준비 판정 중 CI 파일을 확인하지 못했다 — {}", e.toString());
                items.add(item(BLOCKER,
                    "GitLab 연결 확인",
                    "GitLab 저장소의 CI 파일을 확인하지 못했습니다. 연결과 token 권한을 확인하세요.",
                    "cicd"));
            }
        }

        int blocking = (int) items.stream().filter(i -> BLOCKER.equals(i.getLevel())).count();
        return ProjectReadiness.builder()
            .deployable(blocking == 0)
            .blockingCount(blocking)
            .items(items)
            .build();
    }

    private ProjectReadiness.Item item(String level, String title, String why, String anchor) {
        return ProjectReadiness.Item.builder()
            .level(level).title(title).why(why).anchor(anchor).build();
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
