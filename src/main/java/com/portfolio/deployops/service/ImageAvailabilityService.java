package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.Project;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * "배포할 이미지가 왜 하나도 없는가" 를 대신 알아봐 준다.
 *
 * <p>배포 요청 화면은 목록이 비면 <b>"조회 가능한 이미지 태그가 없습니다"</b> 한 줄만 남겼다.
 * 그런데 그 한 줄로는 지금이 어느 상황인지 알 수 없다 —
 * 빌드가 아직 도는 중인지, 빌드가 깨졌는지, 이 프로젝트가 애초에 이미지를 안 만드는지.
 *
 * <p>Java 만 짜고 push 만 하는 사람이 정확히 여기서 멈춘다. 파이프라인을 볼 줄 알아야
 * 스스로 풀 수 있는데, 그걸 아는 사람은 애초에 여기서 안 막힌다.
 * 그래서 <b>화면이 대신 판단해서 다음 할 일을 알려 준다.</b>
 *
 * <p>판정은 확실한 것부터 차례로 본다. GitLab 을 못 읽어도 앞쪽 판정은 살아 있어야 하므로
 * 조회 실패는 예외로 올리지 않고 "알 수 없음" 으로 떨어뜨린다 —
 * 도움을 주려다 화면이 죽으면 원래보다 나쁘다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageAvailabilityService {

    private final GitlabService gitlabService;

    /** GitLab 이 "아직 끝나지 않았다" 는 뜻으로 쓰는 상태들. */
    private static final List<String> RUNNING =
        List.of("running", "pending", "created", "waiting_for_resource", "preparing", "scheduled");

    /**
     * @param token GitLab 조회용 토큰. 없으면 파이프라인 판정은 건너뛴다.
     * @return {@code {reason, title, detail, action, pipelineUrl, pipelineStatus}}
     */
    public Map<String, Object> diagnose(Project project, String token) {
        // ① 이미지 저장소가 비어 있으면 이미지가 생길 수가 없다. 가장 확실한 원인.
        if (project.getImageRepository() == null || project.getImageRepository().isBlank()) {
            return result("NO_IMAGE_REPOSITORY",
                "이 프로젝트는 아직 이미지를 만들 준비가 되어 있지 않습니다.",
                "이미지 저장소가 비어 있어서 빌드를 해도 올려 둘 곳이 없습니다.",
                "프로젝트 상세 > CI/CD 설정에서 이미지 저장소를 지정해 주세요.", null, null);
        }
        if (project.getGitlabProjectId() == null) {
            return result("NO_GITLAB",
                "GitLab 프로젝트가 연결되어 있지 않습니다.",
                "어느 저장소를 빌드해야 하는지 정해져 있지 않습니다.",
                "프로젝트 상세 > GitLab 설정에서 저장소를 연결해 주세요.", null, null);
        }

        // ② 파이프라인을 본다. 못 읽어도 여기서 끝내지 않는다.
        String branch = project.getDefaultBranch() == null || project.getDefaultBranch().isBlank()
            ? "main" : project.getDefaultBranch();
        List<Map<String, Object>> pipelines;
        try {
            pipelines = token == null ? List.of()
                : gitlabService.getPipelines(token, null, project.getGitlabProjectId(), branch);
        } catch (Exception e) {
            log.warn("이미지 없음 원인 조회 중 GitLab 파이프라인을 못 읽었다 — {}", e.toString());
            pipelines = List.of();
        }

        if (pipelines.isEmpty()) {
            return result("NO_PIPELINE",
                "아직 빌드가 한 번도 돌지 않았습니다.",
                "'" + branch + "' 브랜치에서 실행된 파이프라인이 없습니다.",
                "그 브랜치에 커밋을 올리면 빌드가 시작됩니다. "
                    + "CI 설정이 없다면 프로젝트 상세 > CI/CD 설정에서 만들 수 있습니다.",
                null, null);
        }

        Map<String, Object> latest = pipelines.get(0);
        String status = String.valueOf(latest.getOrDefault("status", ""));
        String url = String.valueOf(latest.getOrDefault("webUrl", ""));

        if (RUNNING.contains(status)) {
            return result("PIPELINE_RUNNING",
                "지금 빌드하는 중입니다.",
                "'" + branch + "' 브랜치의 빌드가 아직 끝나지 않았습니다(" + status + ").",
                "끝나면 이미지가 목록에 나타납니다. 잠시 뒤 다시 불러오세요.", url, status);
        }
        if ("failed".equals(status)) {
            return result("PIPELINE_FAILED",
                "마지막 빌드가 실패했습니다.",
                "'" + branch + "' 브랜치의 최근 빌드가 실패해서 이미지가 만들어지지 않았습니다.",
                "GitLab 에서 실패한 단계를 확인하고 고친 뒤 다시 올려 주세요.", url, status);
        }
        if ("canceled".equals(status) || "skipped".equals(status)) {
            return result("PIPELINE_CANCELED",
                "마지막 빌드가 실행되지 않았습니다.",
                "'" + branch + "' 브랜치의 최근 빌드가 " + status + " 상태입니다.",
                "GitLab 에서 다시 실행하거나 새 커밋을 올려 주세요.", url, status);
        }

        // ③ 빌드는 성공했는데 이미지가 없다 — 대개 CI 가 이미지를 push 하지 않는 경우다.
        return result("BUILT_BUT_NO_IMAGE",
            "빌드는 끝났는데 올라온 이미지가 없습니다.",
            "'" + branch + "' 브랜치의 최근 빌드는 성공(" + status + ")했지만 "
                + "이미지 저장소에서 태그를 찾지 못했습니다.",
            "CI 파일에 이미지를 만들어 올리는 단계가 있는지 확인해 주세요 — "
                + "프로젝트 상세 > CI/CD 설정에서 권장 예시와 비교할 수 있습니다.", url, status);
    }

    private Map<String, Object> result(String reason, String title, String detail,
                                       String action, String pipelineUrl, String pipelineStatus) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reason", reason);
        m.put("title", title);
        m.put("detail", detail);
        m.put("action", action);
        m.put("pipelineUrl", pipelineUrl);
        m.put("pipelineStatus", pipelineStatus);
        return m;
    }
}
