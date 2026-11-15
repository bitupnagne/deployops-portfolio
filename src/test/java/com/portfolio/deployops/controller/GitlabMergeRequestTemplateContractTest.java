package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class GitlabMergeRequestTemplateContractTest {

    @Test
    void approvalQueueRequiresProjectDiffReviewAndDetailPostsTheFullReviewedContext() throws Exception {
        String queue = resource("templates/pages/mr-approvals.html");
        String detail = resource("templates/pages/project-detail.html");

        assertThat(queue)
            .contains("변경내용 검토")
            .contains("navigate('project-detail', projectId)")
            .contains("var warning = res.message")
            .contains("esc(res.message)")
            .contains("role=\"status\"")
            .contains("현재 표시할 MR이 없습니다. 위 안내에서 제외 사유나 조회 상태를 확인해 주세요.")
            .contains("box.innerHTML = warning + mrs.map")
            .doesNotContain("/merge-requests/${iid}/${action}")
            .doesNotContain("JSON.stringify({ reviewedSha");
        assertThat(detail)
            .contains("MR 승인은 변경 승인 권한으로 처리하고, 병합은 별도의 MR 병합 권한자가 실행합니다.")
            .doesNotContain("proj-auto-merge")
            .doesNotContain("autoMergeOnApproval:")
            .contains("JSON.stringify({ reviewedSha, reviewedTargetBranch, reviewedDiffBaseSha, reviewedDiffStartSha,")
            .contains("id=\"mr-reject-reason\"")
            .contains("maxlength=\"1000\"")
            .contains("executeMergeRequestAction('reject', pending.iid, pending.review, reason)")
            .contains("executeMergeRequestAction('merge', iid, review)")
            .contains("const review = JSON.parse(JSON.stringify(selectedMergeRequestInfo))")
            .contains("generation !== mrReviewGeneration")
            .contains("changeInfo.deployOpsCanApprove === true")
            .contains("selectedMergeRequestInfo.deployOpsCanApprove !== true")
            .contains("${canApprove ? '' : 'disabled'}")
            .contains("changeInfo.deployOpsCanMerge === true")
            .contains("hasDeployOpsMergePermission && (canMergeOnGitLab || reconcile) && !incompleteDiff")
            .contains("changeInfo.state === 'merged' && changeInfo.deployOpsCanReconcileMerge === true")
            .contains("${canMerge ? '' : 'disabled'}")
            .contains("selectedMergeRequestInfo.deployOpsCanMerge !== true")
            .contains("DeployOps 프로젝트의 MR 병합 권한이 없습니다")
            .contains("changeInfo.canMerge === true")
            .contains("selectedMergeRequestInfo.canMerge !== true")
            .contains("연동된 GitLab 계정에 대상 브랜치 병합 권한이 없습니다")
            .contains("GitLab에서 병합 권한을 확인할 수 없어 병합을 차단했습니다")
            .contains("reviewInfo?.diffRefs?.base_sha")
            .contains("reviewInfo?.diffRefs?.start_sha")
            .contains("Number(selectedMergeRequestInfo.iid) !== Number(selectedMergeRequestIid)")
            .contains("hasIncompleteMergeRequestDiff(selectedMergeRequestInfo)")
            .contains("sourceMergeRequestIid: Number(iid)")
            .contains("확정된 병합 결과 commit만 고유 태그로 빌드")
            .doesNotContain("MR 병합 완료 후 대상 브랜치의 최신 커밋을 빌드합니다");
    }

    private String resource(String path) throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream(path)) {
            assertThat(stream).as(path).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
