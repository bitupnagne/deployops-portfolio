package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.DeployEnvironment;
import com.portfolio.deployops.domain.DeployRequest;
import com.portfolio.deployops.domain.DeployStatus;
import com.portfolio.deployops.domain.ExecutionTrigger;
import com.portfolio.deployops.domain.ReleaseVerificationEvidence;
import com.portfolio.deployops.domain.StatusHistory;
import com.portfolio.deployops.repository.DeployRequestRepository;
import com.portfolio.deployops.repository.ExecutionGuardRepository;
import com.portfolio.deployops.repository.StatusHistoryRepository;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ExecutionPolicyService {

    private static final List<DeployStatus> RUNNING_STATUSES = List.of(
        DeployStatus.TEST_BUILDING,
        DeployStatus.TEST_DEPLOYING,
        DeployStatus.PROD_DEPLOYING
    );
    private static final Set<DayOfWeek> DEFAULT_WEEKDAYS = EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
    private static final Pattern DIGEST_PATTERN = Pattern.compile("^sha256:[0-9a-fA-F]{64}$");
    private static final Pattern COMMIT_PATTERN = Pattern.compile("^[0-9a-fA-F]{40,64}$");
    private static final Pattern BRANCH_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/-]{0,199}$");

    private final SystemSettingService settingService;
    private final DeployRequestRepository deployRequestRepository;
    private final ExecutionGuardRepository executionGuardRepository;
    private final StatusHistoryRepository statusHistoryRepository;
    private final DeploymentPlanSnapshotService deploymentPlanSnapshotService;

    public boolean requireTestFirst() {
        return booleanSetting("require-test-first", true);
    }

    public boolean requireApproval() {
        return booleanSetting("require-approval", true);
    }

    public boolean autoApproveVerifiedPromotion() {
        return booleanSetting("auto-approve-verified-promotion", false);
    }

    public boolean autoDispatchTest() {
        return booleanSetting("auto-dispatch-test", false);
    }

    public boolean autoDispatchProd() {
        return booleanSetting("auto-dispatch-prod", false);
    }

    /** GitLab에서 새 이미지를 만드는 TEST 요청은 DeployOps가 기록한 병합 증거를 기본으로 요구한다. */
    public boolean requireMrEvidenceForBuild() {
        return booleanSetting("require-mr-evidence-for-build", true);
    }

    @Transactional
    public boolean tryStart(DeployRequest request, DeployStatus from, DeployStatus to) {
        return tryStart(request, from, to, ExecutionTrigger.MANUAL);
    }

    /** DB singleton 행으로 모든 애플리케이션 인스턴스의 동시 실행 확인과 선점을 직렬화한다. */
    @Transactional
    public boolean tryStart(DeployRequest request, DeployStatus from, DeployStatus to, ExecutionTrigger trigger) {
        if (executionGuardRepository.lockSingleton() == null) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "배포 실행 잠금 정보를 찾을 수 없습니다. 데이터베이스 마이그레이션 상태를 확인해 주세요."
            );
        }
        executionGuardRepository.assertNoNexusMaintenance();
        if (deployRequestRepository.lockById(request.getId()) == null) {
            return false;
        }
        DeployRequest current = deployRequestRepository.findById(request.getId()).orElse(null);
        if (current == null || current.getStatus() != from) {
            return false;
        }
        assertExecutionWindow(current, LocalDateTime.now());
        ExecutionTrigger resolvedTrigger = trigger == null ? ExecutionTrigger.MANUAL : trigger;
        try {
            deploymentPlanSnapshotService.assertCurrentPlanMatches(current);
            if (!deploymentPlanSnapshotService.applySnapshot(current)
                || EffectiveDeployTargets.resolve(current).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "승인된 배포 계획의 선택한 환경·범위에 대상 서버가 없습니다. 서버 그룹 매핑 후 새 요청을 생성해 주세요.");
            }
        } catch (ResponseStatusException ex) {
            if (resolvedTrigger == ExecutionTrigger.AUTO) {
                String reason = ex.getReason() != null ? ex.getReason() : "배포 계획 검증에 실패했습니다.";
                throw new AutomaticDispatchPlanBlockedException(reason, ex);
            }
            throw ex;
        }
        if (from == DeployStatus.TEST_FAILED || from == DeployStatus.PROD_FAILED) {
            assertRetryNotSuperseded(current);
        }
        assertNoActiveTargetOverlap(current);
        int maxConcurrent = intSetting("max-concurrent", 1, 1, 10);
        if (deployRequestRepository.countByStatusIn(RUNNING_STATUSES) >= maxConcurrent) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "동시 배포 제한(" + maxConcurrent + "건)에 도달했습니다. 진행 중인 배포가 끝난 뒤 다시 시도해 주세요."
            );
        }
        if (deployRequestRepository.compareAndSetExecutionStart(
            current.getId(), from, to, resolvedTrigger) != 1) {
            return false;
        }
        request.setStatus(to);
        request.setExecutionTrigger(resolvedTrigger);
        statusHistoryRepository.save(StatusHistory.builder()
            .deployRequest(request)
            .status(to)
            .build());
        return true;
    }

    private void assertRetryNotSuperseded(DeployRequest request) {
        if (!deploymentPlanSnapshotService.applySnapshot(request)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "저장된 배포 계획이 없는 요청은 재시도할 수 없습니다.");
        }
        var since = statusHistoryRepository.findFirstDeploymentStartedAt(request.getId());
        if (since == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "기존 배포 실행 시각을 확인할 수 없어 재시도할 수 없습니다.");
        }
        var tracking = request.getTargetAttemptTrackingStartedAt();
        if (tracking == null || since.isBefore(tracking)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "기존 실행의 대상별 추적 이력을 보장할 수 없어 재시도할 수 없습니다. 새 요청의 승인 또는 롤백 절차를 진행해 주세요.");
        }
        List<Long> targets = pinnedTargetIds(request);
        for (DeployRequest other : deployRequestRepository.findUntrackedExecutionsSince(request.getId(),
            request.getProject().getId(), request.getEnvironment(), since)) {
            if (!deploymentPlanSnapshotService.applySnapshot(other)
                || pinnedTargetIds(other).stream().anyMatch(targets::contains)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "이후 추적되지 않은 다른 배포가 실행되어 재시도할 수 없습니다. 새 요청의 승인 또는 롤백 절차를 진행해 주세요.");
            }
        }
        if (deployRequestRepository.countSupersedingTargetAttempts(request.getId(), request.getProject().getId(),
            request.getEnvironment().name(), targets, since) != 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "이후 다른 배포가 실행된 대상이 있어 이 요청을 재시도할 수 없습니다. 새 요청의 승인 또는 롤백 절차를 진행해 주세요.");
        }
    }

    private List<Long> pinnedTargetIds(DeployRequest request) {
        var environment = request.getEnvironment() == DeployEnvironment.PROD
            ? com.portfolio.deployops.domain.ServerEnvironment.PROD : com.portfolio.deployops.domain.ServerEnvironment.TEST;
        var scope = request.getDeployScope() == null ? com.portfolio.deployops.domain.DeployScope.ALL : request.getDeployScope();
        List<Long> targets = request.getProject().getServerGroups().stream()
            .filter(group -> group.getEnvironment() == environment)
            .flatMap(group -> group.getServers().stream())
            .filter(server -> (server.getServerType() == com.portfolio.deployops.domain.ServerType.WAS
                || server.getServerType() == com.portfolio.deployops.domain.ServerType.WEB) && scope.includes(server.getServerType()))
            .map(com.portfolio.deployops.domain.Server::getId).distinct().toList();
        if (targets.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "저장된 배포 대상이 없어 재시도할 수 없습니다.");
        }
        return targets;
    }

    private void assertNoActiveTargetOverlap(DeployRequest request) {
        if (request.getProject() == null) return;
        List<DeployRequest> active = deployRequestRepository.findByProjectIdAndEnvironmentAndStatusInWithProject(
            request.getProject().getId(), request.getEnvironment(), RUNNING_STATUSES);
        if (active.isEmpty()) return;
        if (!deploymentPlanSnapshotService.applySnapshot(request)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "실행 중인 요청과 배포 대상의 중복 여부를 확인할 수 없습니다.");
        }
        List<Long> targets = pinnedTargetIds(request);
        for (DeployRequest other : active) {
            if (Objects.equals(other.getId(), request.getId())) continue;
            if (!deploymentPlanSnapshotService.applySnapshot(other)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "실행 중인 요청의 배포 계획을 확인할 수 없습니다.");
            }
            if (pinnedTargetIds(other).stream().anyMatch(targets::contains)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "같은 대상 서버의 다른 배포가 실행 중입니다. 완료 후 다시 확인해 주세요.");
            }
        }
    }

    /**
     * 배포 시작과 운영자 서비스 제어를 같은 DB 잠금으로 직렬화한다.
     * 제어 명령의 네트워크 호출까지 callback 안에서 실행해, 잠금 해제 직전의 배포 시작 경쟁을 막는다.
     */
    @Transactional
    public <T> T withExecutionGuard(Supplier<T> action) {
        if (executionGuardRepository.lockSingleton() == null) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "배포 실행 잠금 정보를 찾을 수 없습니다. 데이터베이스 마이그레이션 상태를 확인해 주세요."
            );
        }
        executionGuardRepository.assertNoNexusMaintenance();
        return action.get();
    }

    public void validateSettings(Map<String, String> values) {
        if (values == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "실행 정책 값이 없습니다.");
        }
        parseTime(values.getOrDefault("allow-start", "09:00"), "허용 시작 시간");
        parseTime(values.getOrDefault("allow-end", "18:00"), "허용 종료 시간");
        parseInt(values.getOrDefault("max-concurrent", "1"), "최대 동시 배포 수", 1, 10);
        parseDays(values.getOrDefault("allowed-weekdays", "MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY"));
        parseTime(values.getOrDefault("freeze-start", "00:00"), "금지 시작 시간");
        parseTime(values.getOrDefault("freeze-end", "23:59"), "금지 종료 시간");
        parseDays(values.getOrDefault("freeze-weekdays", "SATURDAY,SUNDAY"));

        boolean requireTestFirst = parseBoolean(
            values.getOrDefault("require-test-first", "true"), "PROD 배포 전 TEST 확인 필수");
        boolean requireApproval = parseBoolean(
            values.getOrDefault("require-approval", "true"), "PROD 배포 승인 필수");
        parseBoolean(values.getOrDefault("require-mr-evidence-for-build", "true"),
            "새 이미지 빌드 전 MR 승인·병합 증거 필수");
        boolean autoApprove = parseBoolean(
            values.getOrDefault("auto-approve-verified-promotion", "false"), "검증된 승격 자동 승인");
        parseBoolean(values.getOrDefault("auto-dispatch-test", "false"), "TEST 자동 실행");
        parseBoolean(values.getOrDefault("auto-dispatch-prod", "false"), "PROD 자동 실행");
        parseBoolean(values.getOrDefault("restrict-time", "false"), "배포 가능 시간 제한");
        parseBoolean(values.getOrDefault("freeze-enabled", "false"), "PROD 금지시간 적용");
        Set<String> approvalBranches = parseApprovalBranches(
            values.getOrDefault("auto-approval-allowed-branches", ""));
        if (autoApprove && !requireTestFirst) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "검증된 승격 자동 승인은 'PROD 배포 전 TEST 확인 필수'가 활성화되어야 합니다.");
        }
        if (autoApprove && !requireApproval) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "검증된 승격 자동 승인은 'PROD 배포 승인 필수'가 활성화되어야 합니다.");
        }
        if (autoApprove && approvalBranches.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "자동 승인을 허용할 브랜치를 한 개 이상 입력해 주세요.");
        }
    }

    /** 요청 생성 시점의 PROD 승격만 자동 승인할 수 있는지 fail-closed로 판정한다. */
    public boolean shouldAutoApproveVerifiedPromotion(
        DeployRequest request,
        DeployRequest testSource,
        boolean hasComponentOverrides
    ) {
        if (!autoApproveVerifiedPromotion() || !requireApproval() || !requireTestFirst()) {
            return false;
        }
        if (request == null || testSource == null || hasComponentOverrides
            || request.getEnvironment() != DeployEnvironment.PROD
            || testSource.getEnvironment() != DeployEnvironment.TEST
            || testSource.getStatus() != DeployStatus.TEST_CONFIRMED
            || testSource.getTestConfirmedBy() == null
            || testSource.getTestConfirmedBy().getId() == null
            || testSource.getTestConfirmedAt() == null
            || request.getProject() == null || testSource.getProject() == null
            || !Objects.equals(request.getProject().getId(), testSource.getProject().getId())
            || request.getPromotedFromRequest() == null
            || !Objects.equals(request.getPromotedFromRequest().getId(), testSource.getId())
            || !ReleaseVerificationEvidence.isVerifiedCiArtifact(testSource)
            || !ReleaseVerificationEvidence.isVerifiedCiArtifact(request)) {
            return false;
        }

        String branch = normalize(request.getBranch());
        String sourceBranch = normalize(testSource.getBranch());
        String tag = normalize(request.getImageTag());
        String sourceTag = normalize(testSource.getImageTag());
        String digest = normalize(request.getImageDigest());
        String sourceDigest = normalize(testSource.getImageDigest());
        String commit = normalize(request.getCommitSha());
        String sourceCommit = normalize(testSource.getCommitSha());
        Long pipelineId = request.getGitlabPipelineId();

        return branch != null
            && branch.equals(sourceBranch)
            && parseApprovalBranches(settingService.get("auto-approval-allowed-branches", "")).contains(branch)
            && tag != null && tag.equals(sourceTag)
            && digest != null && sourceDigest != null && digest.equalsIgnoreCase(sourceDigest)
            && DIGEST_PATTERN.matcher(digest).matches()
            && commit != null && sourceCommit != null && commit.equalsIgnoreCase(sourceCommit)
            && COMMIT_PATTERN.matcher(commit).matches()
            && pipelineId != null && pipelineId > 0
            && Objects.equals(pipelineId, testSource.getGitlabPipelineId())
            && isImmutablePromotionTag(tag, commit, pipelineId);
    }

    void assertExecutionWindow(DeployRequest request, LocalDateTime now) {
        if (request.getEnvironment() != DeployEnvironment.PROD || !booleanSetting("restrict-time", false)) {
            assertFreezeWindow(request, now);
            return;
        }
        Set<DayOfWeek> allowedDays = parseDays(settingService.get(
            "allowed-weekdays",
            "MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY"
        ));
        LocalTime start = parseTime(settingService.get("allow-start", "09:00"), "허용 시작 시간");
        LocalTime end = parseTime(settingService.get("allow-end", "18:00"), "허용 종료 시간");
        LocalTime current = now.toLocalTime();
        boolean inTime = start.equals(end)
            || (start.isBefore(end)
                ? !current.isBefore(start) && current.isBefore(end)
                : !current.isBefore(start) || current.isBefore(end));
        if (!allowedDays.contains(now.getDayOfWeek()) || !inTime) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "현재는 PROD 배포 허용 시간이 아닙니다. 실행 정책을 확인해 주세요."
            );
        }
        assertFreezeWindow(request, now);
    }

    private void assertFreezeWindow(DeployRequest request, LocalDateTime now) {
        if (request.getEnvironment() != DeployEnvironment.PROD || !booleanSetting("freeze-enabled", false)) {
            return;
        }
        Set<DayOfWeek> freezeDays = parseDays(settingService.get("freeze-weekdays", "SATURDAY,SUNDAY"));
        LocalTime start = parseTime(settingService.get("freeze-start", "00:00"), "금지 시작 시간");
        LocalTime end = parseTime(settingService.get("freeze-end", "23:59"), "금지 종료 시간");
        LocalTime current = now.toLocalTime();
        boolean inTime = start.equals(end)
            || (start.isBefore(end)
                ? !current.isBefore(start) && current.isBefore(end)
                : !current.isBefore(start) || current.isBefore(end));
        if (freezeDays.contains(now.getDayOfWeek()) && inTime) {
            String reason = settingService.get("freeze-reason", "운영 금지시간");
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "현재는 PROD 배포 금지시간입니다. " + reason);
        }
    }

    private boolean booleanSetting(String key, boolean defaultValue) {
        return Boolean.parseBoolean(settingService.get(key, String.valueOf(defaultValue)));
    }

    private boolean parseBoolean(String value, String label) {
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return Boolean.parseBoolean(value);
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " 값이 올바르지 않습니다.");
    }

    private Set<String> parseApprovalBranches(String value) {
        Set<String> branches = new LinkedHashSet<>();
        if (value == null || value.isBlank()) {
            return branches;
        }
        for (String token : value.split("[,\\r\\n]+")) {
            String branch = normalize(token);
            if (branch == null) {
                continue;
            }
            if (!BRANCH_PATTERN.matcher(branch).matches()
                || branch.contains("..") || branch.contains("//") || branch.contains("@{")
                || branch.endsWith("/") || branch.endsWith(".") || branch.endsWith(".lock")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "자동 승인 허용 브랜치 값이 올바르지 않습니다: " + branch);
            }
            if (!branches.add(branch)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "자동 승인 허용 브랜치가 중복되었습니다: " + branch);
            }
            if (branches.size() > 50) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "자동 승인 허용 브랜치는 최대 50개까지 입력할 수 있습니다.");
            }
        }
        return branches;
    }

    private boolean isImmutablePromotionTag(String tag, String commit, Long pipelineId) {
        if ("latest".equalsIgnoreCase(tag)) {
            return false;
        }
        String lowerTag = tag.toLowerCase(Locale.ROOT);
        String lowerCommit = commit.toLowerCase(Locale.ROOT);
        String shortCommit = lowerCommit.substring(0, Math.min(8, lowerCommit.length()));
        String pipelineToken = String.valueOf(pipelineId);
        return lowerTag.contains(shortCommit)
            || Pattern.compile("(^|[-_.])" + Pattern.quote(pipelineToken) + "($|[-_.])")
                .matcher(lowerTag).find();
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private int intSetting(String key, int defaultValue, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(settingService.get(key, String.valueOf(defaultValue)))));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private int parseInt(String value, String label, int min, int max) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < min || parsed > max) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " 값이 올바르지 않습니다.");
        }
    }

    private LocalTime parseTime(String value, String label) {
        try {
            return LocalTime.parse(value);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " 값이 올바르지 않습니다.");
        }
    }

    private Set<DayOfWeek> parseDays(String value) {
        EnumSet<DayOfWeek> result = EnumSet.noneOf(DayOfWeek.class);
        if (value != null) {
            for (String token : value.split(",")) {
                try {
                    result.add(DayOfWeek.valueOf(token.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ignored) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "허용 요일 값이 올바르지 않습니다.");
                }
            }
        }
        if (result.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "허용 요일을 한 개 이상 선택해 주세요.");
        }
        return result;
    }
}
