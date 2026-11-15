package com.portfolio.deployops.service;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.MergeRequestEvidence;
import com.portfolio.deployops.domain.MergeRequestEvidenceStatus;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.repository.MergeRequestEvidenceRepository;
import com.portfolio.deployops.repository.MergeRequestOriginRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * DeployOps 안에서 수행한 MR 검토와 GitLab의 병합 결과를 하나의 서버측 증거로 묶는다.
 * 브라우저가 보내는 SHA는 비교 조건일 뿐 정본으로 저장하지 않고 GitLab을 다시 조회한다.
 */
@Service
@RequiredArgsConstructor
public class MergeRequestGovernanceService {

    private static final int MERGE_RESULT_ATTEMPTS = 10;
    private static final long MERGE_RESULT_WAIT_MILLIS = 500L;

    private final GitlabService gitlabService;
    private final MergeRequestEvidenceRepository evidenceRepository;
    private final MergeRequestOriginRepository originRepository;
    private final MergeRequestApprovalInvalidationService approvalInvalidationService;

    @Transactional
    public Map<String, Object> approve(
        Project project,
        long mergeRequestIid,
        String reviewedSha,
        String token,
        String tokenSource,
        String reviewedTargetBranch,
        String reviewedDiffBaseSha,
        String reviewedDiffStartSha,
        SecurityUserDetails currentUser
    ) throws Exception {
        if (!"USER_PAT".equals(tokenSource) || token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "MR 승인에는 본인 GitLab 개인 토큰이 필요합니다.");
        }
        Identity reviewer = requireIdentity(currentUser);
        GitlabService.GitlabUserIdentity tokenUser = gitlabService.getAuthenticatedUserIdentity(token, null);
        if (!reviewer.gitlabUsername().equalsIgnoreCase(tokenUser.username())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "GitLab 개인 토큰의 계정이 로그인 사용자의 GitLab 사용자명과 다릅니다.");
        }
        gitlabService.assertDistinctApprovalActor(token, tokenUser);
        long gitlabProjectId = requireGitlabProject(project);
        assertOriginProjectMatches(project, gitlabProjectId, mergeRequestIid);
        long reviewGeneration = approvalInvalidationService.beginReview(project.getId(), gitlabProjectId, mergeRequestIid);
        String expectedSha = requireCommitSha(reviewedSha);
        Map<String, Object> mr = gitlabService.getMergeRequest(token, null, gitlabProjectId, mergeRequestIid);
        assertReviewable(mr, gitlabProjectId, expectedSha);

        Long authorId = number(mr.get("authorId"));
        if (authorId == null || authorId <= 0) {
            throw conflict("MR 작성자의 GitLab ID를 확인할 수 없어 승인할 수 없습니다.");
        }
        if (authorId == tokenUser.id()) {
            throw conflict("MR 작성자는 자신의 변경을 승인할 수 없습니다.");
        }
        if (originRepository.findCreatedById(project.getId(), gitlabProjectId, mergeRequestIid)
            .filter(reviewer.userId()::equals).isPresent()) {
            throw conflict("DeployOps에서 이 MR을 생성한 사용자는 자신의 변경을 승인할 수 없습니다.");
        }

        Map<String, Object> changes = gitlabService.getMergeRequestChanges(
            token, null, gitlabProjectId, mergeRequestIid);
        if (GitlabService.hasIncompleteMergeRequestDiff(changes)) {
            throw conflict("GitLab이 전체 변경 내용을 반환하지 않아 승인할 수 없습니다. GitLab에서 전체 diff를 확인해 주세요.");
        }
        if (!expectedSha.equalsIgnoreCase(text(changes.get("sha")))) {
            throw conflict("변경 내용을 읽는 동안 MR source SHA가 바뀌었습니다. 다시 검토해 주세요.");
        }
        assertReviewedContext(
            changes, reviewedTargetBranch, reviewedDiffBaseSha, reviewedDiffStartSha);

        boolean alreadyApproved = gitlabService.getMergeRequestApproverIds(
            token, null, gitlabProjectId, mergeRequestIid).contains(tokenUser.id());
        Map<String, Object> result = alreadyApproved
            ? Map.of("alreadyApproved", true)
            : gitlabService.approveMergeRequest(
                token, null, gitlabProjectId, mergeRequestIid, expectedSha);
        // GitLab 승인 호출과 동시에 target/diff가 바뀌는 경합도 로컬 증거로 확정하지 않는다.
        // GitLab의 승인이 남더라도 Deploy 증거가 없으므로 병합·빌드는 fail-closed 된다.
        Map<String, Object> approved = gitlabService.getMergeRequest(
            token, null, gitlabProjectId, mergeRequestIid);
        assertReviewable(approved, gitlabProjectId, expectedSha);
        assertReviewedContext(
            approved, reviewedTargetBranch, reviewedDiffBaseSha, reviewedDiffStartSha);
        assertGitlabApprovalPresent(token, gitlabProjectId, mergeRequestIid, tokenUser.id());
        Map<?, ?> diffRefs = map(approved.get("diffRefs"));
        MergeRequestEvidence evidence = MergeRequestEvidence.builder()
            .projectId(project.getId())
            .gitlabProjectId(gitlabProjectId)
            .mergeRequestIid(mergeRequestIid)
            .sourceSha(expectedSha)
            .targetBranch(requireText(text(approved.get("targetBranch")), "MR target branch를 확인할 수 없습니다."))
            .diffBaseSha(text(diffRefs.get("base_sha")))
            .diffStartSha(text(diffRefs.get("start_sha")))
            .reviewerId(reviewer.userId())
            .reviewerGitlabUserId(tokenUser.id())
            .reviewerGitlabUsername(tokenUser.username())
            .tokenSource(requireText(tokenSource, "GitLab token 출처를 확인할 수 없습니다."))
            .status(MergeRequestEvidenceStatus.APPROVED)
            .build();
        approvalInvalidationService.assertReviewStillCurrent(project.getId(), gitlabProjectId, mergeRequestIid, reviewGeneration);
        evidenceRepository.saveApproved(evidence);

        Map<String, Object> response = new LinkedHashMap<>(result);
        response.put("evidenceId", evidence.getId());
        response.put("reviewedSha", expectedSha);
        return response;
    }

    @Transactional
    public Map<String, Object> merge(
        Project project,
        long mergeRequestIid,
        String reviewedSha,
        String token,
        String reviewedTargetBranch,
        String reviewedDiffBaseSha,
        String reviewedDiffStartSha,
        SecurityUserDetails currentUser
    ) throws Exception {
        Identity merger = mergePreflight(() -> requireIdentity(currentUser));
        long gitlabProjectId = mergePreflight(() -> requireGitlabProject(project));
        String expectedSha = mergePreflight(() -> {
            assertOriginProjectMatches(project, gitlabProjectId, mergeRequestIid);
            return requireCommitSha(reviewedSha);
        });
        MergeRequestEvidence evidence = mergePreflight(() -> evidenceRepository
            .findApproved(project.getId(), gitlabProjectId, mergeRequestIid, expectedSha)
            .orElseThrow(() -> conflict("현재 source SHA에 대한 DeployOps 승인 기록이 없습니다. 변경 내용을 다시 검토하고 승인해 주세요.")));

        Map<String, Object> current = gitlabService.getMergeRequest(token, null, gitlabProjectId, mergeRequestIid);
        boolean alreadyMerged = mergePreflight(() -> {
            assertSameMergeRequest(current, gitlabProjectId, expectedSha);
            assertEvidenceMatchesCurrentMr(evidence, current, gitlabProjectId);
            assertReviewedContext(current, reviewedTargetBranch, reviewedDiffBaseSha, reviewedDiffStartSha);
            boolean merged = "merged".equalsIgnoreCase(text(current.get("state")));
            if (!merged && Boolean.FALSE.equals(current.get("canMerge"))) {
                throw conflict("연동된 GitLab 계정에 대상 브랜치 병합 권한이 없습니다. GitLab 보호 브랜치 권한을 확인해 주세요.");
            }
            if (!merged && !Boolean.TRUE.equals(current.get("canMerge"))) {
                throw conflict("GitLab에서 대상 브랜치 병합 권한을 확인하지 못해 병합을 차단했습니다.");
            }
            return merged;
        });
        Long reviewerGitlabUserId = mergePreflight(() -> {
            Long id = evidence.getReviewerGitlabUserId();
            if (id == null || id <= 0) throw conflict("저장된 MR 승인자 GitLab ID가 없습니다. 다시 승인해 주세요.");
            return id;
        });
        var approverIds = gitlabService.getMergeRequestApproverIds(token, null, gitlabProjectId, mergeRequestIid);
        mergePreflight(() -> {
            if (!approverIds.contains(reviewerGitlabUserId)) {
                throw conflict("GitLab에서 해당 검토자의 현재 MR 승인을 확인할 수 없습니다. 다시 승인해 주세요.");
            }
            return null;
        });

        Map<String, Object> result;
        if (alreadyMerged) {
            result = current; // GitLab 성공 후 로컬 기록 실패 시 안전하게 재조정할 수 있다.
        } else {
            mergePreflight(() -> {
                assertReviewable(current, gitlabProjectId, expectedSha);
                return null;
            });
            Map<String, Object> changes = gitlabService.getMergeRequestChanges(
                token, null, gitlabProjectId, mergeRequestIid);
            mergePreflight(() -> {
                if (GitlabService.hasIncompleteMergeRequestDiff(changes)) {
                    throw conflict("GitLab이 전체 변경 내용을 반환하지 않아 병합할 수 없습니다. 전체 diff를 다시 검토해 주세요.");
                }
                if (!expectedSha.equalsIgnoreCase(text(changes.get("sha")))) {
                    throw conflict("변경 내용을 읽는 동안 MR source SHA가 바뀌었습니다. 다시 검토해 주세요.");
                }
                assertReviewedContext(changes, reviewedTargetBranch, reviewedDiffBaseSha, reviewedDiffStartSha);
                return null;
            });
            result = gitlabService.mergeMergeRequest(
                token, null, gitlabProjectId, mergeRequestIid, expectedSha,
                project.isDeleteSourceBranchOnMerge());
        }

        Map<String, Object> merged = waitForMergedResult(
            token, gitlabProjectId, mergeRequestIid, expectedSha, evidence, result);
        assertEvidenceMatchesCurrentMr(evidence, merged, gitlabProjectId);
        String mergedResultSha = resolveMergedResultSha(merged, expectedSha);
        evidence.setMergedResultSha(mergedResultSha);
        evidence.setMergerId(merger.userId());
        if (evidenceRepository.markMerged(evidence) != 1) {
            throw conflict("MR 병합 증거를 확정하지 못했습니다. 배포 요청은 생성할 수 없습니다.");
        }

        Map<String, Object> response = new LinkedHashMap<>(merged);
        response.put("evidenceId", evidence.getId());
        response.put("mergedResultSha", mergedResultSha);
        return response;
    }

    /** A rejection closes the MR; it never merges, triggers CI or creates a deployment. */
    public Map<String, Object> reject(
        Project project, long mergeRequestIid, String reviewedSha, String token, String tokenSource,
        String reviewedTargetBranch, String reviewedDiffBaseSha, String reviewedDiffStartSha,
        String reason, SecurityUserDetails currentUser
    ) throws Exception {
        if (reason == null || reason.isBlank() || reason.length() > 1000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "반려 사유는 1~1000자로 입력해 주세요.");
        }
        if (!"USER_PAT".equals(tokenSource) || token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "MR 반려에는 본인 GitLab 개인 토큰이 필요합니다.");
        }
        Identity reviewer = requireIdentity(currentUser);
        GitlabService.GitlabUserIdentity tokenUser = gitlabService.getAuthenticatedUserIdentity(token, null);
        if (!reviewer.gitlabUsername().equalsIgnoreCase(tokenUser.username())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "GitLab 개인 토큰의 계정이 로그인 사용자의 GitLab 사용자명과 다릅니다.");
        }
        gitlabService.assertDistinctApprovalActor(token, tokenUser);
        long gitlabProjectId = requireGitlabProject(project);
        assertOriginProjectMatches(project, gitlabProjectId, mergeRequestIid);
        String expectedSha = requireCommitSha(reviewedSha);
        Map<String, Object> mr = gitlabService.getMergeRequest(token, null, gitlabProjectId, mergeRequestIid);
        assertRejectable(mr, gitlabProjectId, expectedSha);
        Long authorId = number(mr.get("authorId"));
        if (authorId == null || authorId <= 0) throw conflict("MR 작성자 정보를 확인할 수 없어 반려할 수 없습니다.");
        if (authorId == tokenUser.id() || originRepository
            .findCreatedById(project.getId(), gitlabProjectId, mergeRequestIid)
            .filter(reviewer.userId()::equals).isPresent()) {
            throw conflict("MR 작성자는 자신의 변경을 반려할 수 없습니다. 별도 검토자가 반려해 주세요.");
        }
        Map<String, Object> changes = gitlabService.getMergeRequestChanges(token, null, gitlabProjectId, mergeRequestIid);
        if (!expectedSha.equalsIgnoreCase(text(changes.get("sha")))
            || !"opened".equalsIgnoreCase(text(changes.get("state")))) {
            throw conflict("변경 내용을 읽는 동안 MR 상태 또는 SHA가 바뀌었습니다. 다시 검토해 주세요.");
        }
        if (GitlabService.hasIncompleteMergeRequestDiff(changes)) throw conflict("전체 diff를 확인할 수 없어 반려할 수 없습니다.");
        assertReviewedContext(changes, reviewedTargetBranch, reviewedDiffBaseSha, reviewedDiffStartSha);
        // Encode slashes in addition to quoting: the note must never contain quick-action commands.
        String quotedReason = reason.trim().replace("&", "&amp;").replace("/", "&#47;")
            .lines().map(line -> "> " + line)
            .collect(java.util.stream.Collectors.joining("\n"));
        gitlabService.postMergeRequestNote(token, null, gitlabProjectId, mergeRequestIid,
            "DeployOps MR 반려 요청 (검토 SHA: " + expectedSha + ")\n\n사유:\n" + quotedReason);
        // GitLab close has no SHA precondition. Recheck immediately before and after the call.
        Map<String, Object> beforeClose = gitlabService.getMergeRequest(token, null, gitlabProjectId, mergeRequestIid);
        assertRejectable(beforeClose, gitlabProjectId, expectedSha);
        assertReviewedContext(beforeClose, reviewedTargetBranch, reviewedDiffBaseSha, reviewedDiffStartSha);
        // Durable withdrawal prevents old approvals from becoming usable if close succeeds but
        // confirmation fails, or if GitLab later reopens the same head. Failure requires a new approval.
        approvalInvalidationService.invalidate(project.getId(), gitlabProjectId, mergeRequestIid);
        gitlabService.closeMergeRequest(token, null, gitlabProjectId, mergeRequestIid);
        Map<String, Object> closed = gitlabService.getMergeRequest(token, null, gitlabProjectId, mergeRequestIid);
        assertSameMergeRequest(closed, gitlabProjectId, expectedSha);
        assertReviewedContext(closed, reviewedTargetBranch, reviewedDiffBaseSha, reviewedDiffStartSha);
        if (!"closed".equalsIgnoreCase(text(closed.get("state")))) {
            throw conflict("GitLab에서 MR 닫힘 상태를 확인하지 못했습니다. 상태를 새로고침해 주세요.");
        }
        approvalInvalidationService.invalidate(project.getId(), gitlabProjectId, mergeRequestIid);
        return Map.of("iid", mergeRequestIid, "state", "closed", "reviewedSha", expectedSha);
    }

    private void assertRejectable(Map<String, Object> mr, long gitlabProjectId, String expectedSha) {
        assertSameMergeRequest(mr, gitlabProjectId, expectedSha);
        if (!"opened".equalsIgnoreCase(text(mr.get("state")))) throw conflict("열린 MR만 반려할 수 있습니다.");
    }

    private Map<String, Object> waitForMergedResult(
        String token, long gitlabProjectId, long iid, String expectedSha,
        MergeRequestEvidence evidence, Map<String, Object> first
    ) throws Exception {
        Map<String, Object> current = first;
        for (int attempt = 0; attempt < MERGE_RESULT_ATTEMPTS; attempt++) {
            if ("merged".equalsIgnoreCase(text(current.get("state"))) && hasMergedResult(current)) {
                assertSameMergeRequest(current, gitlabProjectId, expectedSha);
                assertEvidenceMatchesCurrentMr(evidence, current, gitlabProjectId);
                return current;
            }
            if (attempt + 1 < MERGE_RESULT_ATTEMPTS) {
                try {
                    Thread.sleep(MERGE_RESULT_WAIT_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("MR 병합 결과 확인이 중단되었습니다.", e);
                }
                current = gitlabService.getMergeRequest(token, null, gitlabProjectId, iid);
            }
        }
        if ("merged".equalsIgnoreCase(text(current.get("state")))) {
            assertSameMergeRequest(current, gitlabProjectId, expectedSha);
            assertEvidenceMatchesCurrentMr(evidence, current, gitlabProjectId);
            String targetBranch = requireText(text(current.get("targetBranch")),
                "MR target branch를 확인할 수 없습니다.");
            String branchHead = gitlabService.getBranchCommitSha(token, null, gitlabProjectId, targetBranch);
            if (expectedSha.equalsIgnoreCase(branchHead)) {
                Map<String, Object> fastForward = new LinkedHashMap<>(current);
                fastForward.put("mergeCommitSha", expectedSha);
                return fastForward;
            }
        }
        throw conflict("GitLab에서 확정된 병합 결과 SHA를 확인하지 못했습니다. 배포 요청은 생성할 수 없습니다.");
    }

    private void assertReviewable(Map<String, Object> mr, long gitlabProjectId, String expectedSha) {
        assertSameMergeRequest(mr, gitlabProjectId, expectedSha);
        if (!"opened".equalsIgnoreCase(text(mr.get("state")))) {
            throw conflict("열린 MR만 승인 또는 병합할 수 있습니다.");
        }
        if (Boolean.TRUE.equals(mr.get("draft"))) {
            throw conflict("Draft MR은 승인 또는 병합할 수 없습니다.");
        }
    }

    private void assertSameMergeRequest(Map<String, Object> mr, long gitlabProjectId, String expectedSha) {
        if (!expectedSha.equalsIgnoreCase(text(mr.get("sha")))) {
            throw conflict("검토 후 MR source SHA가 변경되었습니다. 최신 변경 내용을 다시 검토해 주세요.");
        }
        Long targetProjectId = number(mr.get("targetProjectId"));
        if (targetProjectId == null || targetProjectId != gitlabProjectId) {
            throw conflict("MR 대상 저장소가 DeployOps 프로젝트 설정과 일치하지 않습니다.");
        }
    }

    private void assertEvidenceMatchesCurrentMr(
        MergeRequestEvidence evidence, Map<String, Object> mr, long gitlabProjectId
    ) {
        Map<?, ?> diffRefs = map(mr.get("diffRefs"));
        if (!java.util.Objects.equals(evidence.getGitlabProjectId(), gitlabProjectId)
            || !equalsText(evidence.getTargetBranch(), text(mr.get("targetBranch")))
            || !equalsOptionalText(evidence.getDiffBaseSha(), text(diffRefs.get("base_sha")))
            || !equalsOptionalText(evidence.getDiffStartSha(), text(diffRefs.get("start_sha")))) {
            throw conflict("승인 후 MR 대상 브랜치 또는 diff 기준이 변경되었습니다. 최신 변경 내용을 다시 검토해 주세요.");
        }
    }

    private void assertReviewedContext(
        Map<String, Object> mr,
        String reviewedTargetBranch,
        String reviewedDiffBaseSha,
        String reviewedDiffStartSha
    ) {
        Map<?, ?> diffRefs = map(mr.get("diffRefs"));
        if (!equalsText(requireText(reviewedTargetBranch, "검토한 target branch가 없습니다."),
                text(mr.get("targetBranch")))
            || !equalsText(requireCommitSha(reviewedDiffBaseSha), text(diffRefs.get("base_sha")))
            || !equalsText(requireCommitSha(reviewedDiffStartSha), text(diffRefs.get("start_sha")))) {
            throw conflict("화면 검토 후 MR 대상 브랜치 또는 diff 기준이 변경되었습니다. 변경 내용을 다시 열어 검토해 주세요.");
        }
    }

    private void assertGitlabApprovalPresent(
        String token, long gitlabProjectId, long mergeRequestIid, long reviewerGitlabUserId
    ) throws Exception {
        if (!gitlabService.getMergeRequestApproverIds(token, null, gitlabProjectId, mergeRequestIid)
            .contains(reviewerGitlabUserId)) {
            throw conflict("GitLab에서 해당 검토자의 현재 MR 승인을 확인할 수 없습니다. 다시 승인해 주세요.");
        }
    }

    private boolean equalsText(String left, String right) {
        return left != null && right != null && left.equals(right);
    }

    /** GitLab 버전에 따라 diff ref가 생략될 수 있어 승인 때 저장된 값이 있을 때만 엄격히 비교한다. */
    private boolean equalsOptionalText(String approved, String current) {
        return approved == null || (current != null && approved.equalsIgnoreCase(current));
    }

    private String resolveMergedResultSha(Map<String, Object> mr, String sourceSha) {
        String mergeCommit = validSha(text(mr.get("mergeCommitSha")));
        if (mergeCommit != null) return mergeCommit;
        String squashCommit = validSha(text(mr.get("squashCommitSha")));
        if (squashCommit != null) return squashCommit;
        // fast-forward 병합은 별도 결과 commit 없이 검토한 source commit 자체가 target 결과다.
        return sourceSha;
    }

    private boolean hasMergedResult(Map<String, Object> mr) {
        return validSha(text(mr.get("mergeCommitSha"))) != null
            || validSha(text(mr.get("squashCommitSha"))) != null;
    }

    private long requireGitlabProject(Project project) {
        if (project == null || project.getId() == null || project.getGitlabProjectId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GitLab 저장소가 연결되어 있지 않습니다.");
        }
        return project.getGitlabProjectId();
    }

    private void assertOriginProjectMatches(Project project, long gitlabProjectId, long mergeRequestIid) {
        var owners = originRepository.findProjectIdsByGitlabProjectIdAndMergeRequestIid(
            gitlabProjectId, mergeRequestIid);
        if (!owners.isEmpty()
            && (owners.size() != 1 || !project.getId().equals(owners.getFirst()))) {
            throw conflict("이 MR은 다른 DeployOps 프로젝트에서 생성된 기록이 있습니다. 저장소 연결이 바뀌었는지 확인한 뒤 해당 프로젝트의 이력에서 검토해 주세요.");
        }
    }

    private Identity requireIdentity(SecurityUserDetails currentUser) {
        if (currentUser == null || currentUser.getUser() == null || currentUser.getId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인 사용자를 확인할 수 없습니다.");
        }
        String username = requireText(currentUser.getUser().getGitlabUsername(),
            "내 계정의 GitLab 사용자명이 설정되어 있지 않습니다.");
        return new Identity(currentUser.getId(), username);
    }

    private String requireCommitSha(String value) {
        String sha = requireText(value, "검토한 commit SHA가 없습니다.");
        if (!sha.matches("^[0-9a-fA-F]{40,64}$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "검토한 commit SHA 형식이 올바르지 않습니다.");
        }
        return sha.toLowerCase(java.util.Locale.ROOT);
    }

    private String validSha(String value) {
        return value != null && value.matches("^[0-9a-fA-F]{40,64}$")
            ? value.toLowerCase(java.util.Locale.ROOT) : null;
    }

    private String requireText(String value, String message) {
        String normalized = text(value);
        if (normalized == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        return normalized;
    }

    private String text(Object value) {
        if (value == null) return null;
        String normalized = String.valueOf(value).trim();
        return normalized.isEmpty() || "null".equalsIgnoreCase(normalized) ? null : normalized;
    }

    private Long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return value == null ? null : Long.valueOf(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return null; }
    }

    private Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> result ? result : Map.of();
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    /** Only local checks before the external merge may report a definite rejection. */
    private <T> T mergePreflight(java.util.function.Supplier<T> check) {
        try {
            return check.get();
        } catch (ResponseStatusException rejection) {
            throw new MergePreflightRejectedException(rejection);
        }
    }

    public static final class MergePreflightRejectedException extends ResponseStatusException {
        public MergePreflightRejectedException(ResponseStatusException rejection) {
            super(rejection.getStatusCode(), rejection.getReason(), rejection);
        }
    }

    private record Identity(Long userId, String gitlabUsername) {}
}
