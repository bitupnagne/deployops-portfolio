package com.portfolio.deployops.service;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.AuditLog;
import com.portfolio.deployops.domain.AuditResult;
import com.portfolio.deployops.domain.DeployRequest;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.repository.AuditLogRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private static final String EMPTY_CHAIN_HASH = "0".repeat(64);
    private final ReentrantLock chainLock = new ReentrantLock();

    private final AuditLogRepository auditLogRepository;
    private final SecretMaskingService secretMaskingService;

    /**
     * 관리 화면 CRUD 용 — 행위자와 IP 를 현재 요청 컨텍스트에서 직접 꺼낸다.
     *
     * <p>배포 파이프라인 쪽은 {@link #record} 를 그대로 쓴다. 그쪽은 이미 {@code currentUser} 와
     * {@code HttpServletRequest} 를 인자로 받고 있고, 실패 경로에서 actor 를 바꿔 남기는 곳도 있다.
     *
     * <p>관리 CRUD 는 사정이 다르다. 컨트롤러 8개 25개 메서드에 인자 2개씩을 새로 끼워 넣으면
     * 그 시그니처 변경이 곧 누락 지점이 된다 — 새 엔드포인트를 추가하는 사람이 인자를 안 받으면
     * 감사로그도 조용히 빠진다. 컨텍스트에서 꺼내면 호출 한 줄이 전부라 빠뜨릴 여지가 줄어든다.
     *
     * <p>비제어성 관측 이벤트용 best-effort 경로다. 권한, 설정, 서버, 프로젝트 또는 승인 정책을
     * 바꾸는 호출에는 사용하지 말고 {@link #recordCurrentRequired}를 사용한다.
     */
    public void recordCurrent(String action, String targetType, String targetId,
                              String targetDesc, AuditResult result) {
        try {
            record(currentActor(), action, targetType, targetId, targetDesc, result, currentIp());
        } catch (Exception ex) {
            log.error("감사로그 기록 실패 — action={} target={}/{} : {}",
                action, targetType, targetId, ex.toString());
        }
    }

    /** 보안·운영 제어 변경용 감사 기록. 저장 실패를 호출자에게 전파해 성공으로 처리하지 않는다. */
    public void recordCurrentRequired(String action, String targetType, String targetId,
                                      String targetDesc, AuditResult result) {
        record(currentActor(), action, targetType, targetId, targetDesc, result, currentIp());
    }

    /** 인증 전에도 검증한 계정을 명시하며 자격증명은 기록하지 않는다. */
    @Transactional
    public void recordAuthentication(User user, String loginId, String action,
                                     AuditResult result, String category, String ip) {
        String safeLogin = safeLoginId(user == null ? loginId : user.getLoginId());
        record(safeLogin, action, "User", user == null ? "(unknown)" : String.valueOf(user.getId()),
            "loginId=" + safeLogin + " | " + category, result, ip == null ? currentIp() : ip);
    }

    private String safeLoginId(String loginId) {
        if (loginId == null || loginId.isBlank()) return "(unknown)";
        String safe = loginId.replaceAll("[\\p{Cntrl}]", "?");
        return safe.substring(0, Math.min(safe.length(), 100));
    }

    /** 로그인 사용자의 표시 이름. 인증 정보를 못 찾으면 빈칸 대신 표식을 남긴다. */
    private String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return "(unauthenticated)";
        }
        if (auth.getPrincipal() instanceof SecurityUserDetails u) {
            return u.getName();
        }
        return auth.getName();
    }

    /** 요청 IP. 스케줄러 등 요청 밖에서 불릴 수도 있으므로 없으면 표식을 남긴다. */
    private String currentIp() {
        ServletRequestAttributes attrs =
            (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attrs == null ? "(no-request)" : attrs.getRequest().getRemoteAddr();
    }

    @Transactional
    public void record(String actor, String action, String targetType, String targetId,
                       String targetDesc, AuditResult result, String ip) {
        AuditLog entry = AuditLog.builder()
            .actor(secretMaskingService.mask(actor))
            .action(secretMaskingService.mask(action))
            .targetType(secretMaskingService.mask(targetType))
            .targetId(secretMaskingService.mask(targetId))
            .targetDesc(secretMaskingService.mask(targetDesc))
            .result(result)
            .ipAddress(secretMaskingService.mask(ip))
            .createdAt(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS))
            .build();

        chainLock.lock();
        boolean unlockAfterMethod = true;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            try {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        chainLock.unlock();
                    }
                });
                unlockAfterMethod = false;
            } catch (RuntimeException ex) {
                chainLock.unlock();
                throw ex;
            }
        }
        try {
            List<AuditLog> legacy = safeList(auditLogRepository.findLegacyEntries());
            List<AuditLog> chained = safeList(auditLogRepository.findChainedEntries());
            String previousHash = chained.isEmpty()
                ? legacyAnchor(legacy)
                : chained.get(chained.size() - 1).getEntryHash();
            entry.setPreviousHash(previousHash);
            entry.setEntryHash(hash(previousHash + canonicalEntry(entry, false)));
            auditLogRepository.save(entry);
        } finally {
            if (unlockAfterMethod) {
                chainLock.unlock();
            }
        }
    }

    @Transactional(readOnly = true)
    public IntegrityResult verifyIntegrity() {
        List<AuditLog> legacy = safeList(auditLogRepository.findLegacyEntries());
        List<AuditLog> chained = safeList(auditLogRepository.findChainedEntries());
        String expectedPrevious = legacyAnchor(legacy);
        for (AuditLog entry : chained) {
            if (!secureEquals(expectedPrevious, entry.getPreviousHash())) {
                return new IntegrityResult(false, legacy.size(), chained.size(), entry.getId(), "이전 해시가 일치하지 않습니다.");
            }
            String expectedEntry = hash(expectedPrevious + canonicalEntry(entry, false));
            if (!secureEquals(expectedEntry, entry.getEntryHash())) {
                return new IntegrityResult(false, legacy.size(), chained.size(), entry.getId(), "감사로그 내용 해시가 일치하지 않습니다.");
            }
            expectedPrevious = entry.getEntryHash();
        }
        return new IntegrityResult(true, legacy.size(), chained.size(), null,
            legacy.isEmpty() ? "감사로그 해시 체인이 정상입니다." : "기존 로그 앵커를 포함한 해시 체인이 정상입니다.");
    }

    private String legacyAnchor(List<AuditLog> legacy) {
        if (legacy.isEmpty()) {
            return EMPTY_CHAIN_HASH;
        }
        StringBuilder canonical = new StringBuilder("legacy|");
        for (AuditLog entry : legacy) {
            canonical.append(canonicalEntry(entry, true));
        }
        return hash(canonical.toString());
    }

    private String canonicalEntry(AuditLog entry, boolean includeId) {
        StringBuilder value = new StringBuilder("|");
        if (includeId) append(value, entry.getId());
        append(value, entry.getActor());
        append(value, entry.getAction());
        append(value, entry.getTargetType());
        append(value, entry.getTargetId());
        append(value, entry.getTargetDesc());
        append(value, entry.getResult() == null ? null : entry.getResult().name());
        append(value, entry.getIpAddress());
        append(value, entry.getCreatedAt());
        return value.toString();
    }

    private void append(StringBuilder target, Object raw) {
        String value = raw == null ? "" : String.valueOf(raw);
        target.append(value.length()).append(':').append(value).append('|');
    }

    private String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }

    private boolean secureEquals(String expected, String actual) {
        byte[] left = expected == null ? new byte[0] : expected.getBytes(StandardCharsets.UTF_8);
        byte[] right = actual == null ? new byte[0] : actual.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(left, right);
    }

    private List<AuditLog> safeList(List<AuditLog> entries) {
        return entries == null ? List.of() : entries;
    }

    public record IntegrityResult(boolean valid, int legacyEntries, int chainedEntries,
                                  Long failedEntryId, String message) {
    }

    @Transactional(readOnly = true)
    public Page<AuditLog> findWithFilter(String actor, LocalDateTime from, LocalDateTime to, Pageable pageable) {
        return auditLogRepository.findWithFilter(actor, from, to, pageable);
    }

    public String describeDeployRequest(DeployRequest request) {
        List<String> parts = new ArrayList<>();
        parts.add(request.getReqNo());
        if (request.getProject() != null && request.getProject().getName() != null) {
            parts.add(request.getProject().getName());
        }
        if (request.getEnvironment() != null) {
            parts.add("env=" + request.getEnvironment().name());
        }
        if (request.getBranch() != null && !request.getBranch().isBlank()) {
            parts.add("ref=" + request.getBranch());
        }
        if (request.getImageTag() != null && !request.getImageTag().isBlank()) {
            parts.add("tag=" + request.getImageTag());
        }
        if (request.getGitlabPipelineId() != null) {
            parts.add("pipeline=" + request.getGitlabPipelineId());
        }
        if (request.getCommitSha() != null && !request.getCommitSha().isBlank()) {
            parts.add("commit=" + abbreviate(request.getCommitSha(), 12));
        }
        if (request.getImageDigest() != null && !request.getImageDigest().isBlank()) {
            parts.add("digest=" + abbreviate(request.getImageDigest(), 18));
        }
        return String.join(" | ", parts);
    }

    private String abbreviate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }
}
