package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.NginxChangeRequest;
import com.portfolio.deployops.domain.NginxChangeStatus;
import com.portfolio.deployops.domain.NginxPolicyType;
import com.portfolio.deployops.domain.Server;
import com.portfolio.deployops.domain.ServerType;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.dto.NginxPolicyForm;
import com.portfolio.deployops.repository.NginxChangeRequestRepository;
import com.portfolio.deployops.repository.ServerRepository;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Slf4j
public class NginxChangeService {
    static final String AGENT = "/usr/local/deploy/bin/deploy-nginx-policy.sh";
    private static final long MAX_PEM_BYTES = 2L * 1024 * 1024;
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9._:-]{0,253}[A-Za-z0-9])?");
    private static final Pattern SERVER_NAME = Pattern.compile("(?:_|\\*\\.)?[A-Za-z0-9](?:[A-Za-z0-9.-]{0,253}[A-Za-z0-9])?");
    private static final Pattern CIDR = Pattern.compile("[0-9A-Fa-f:.]+(?:/[0-9]{1,3})?");
    private static final Pattern WEB_ROOT = Pattern.compile("/(?:var/www|srv/web)/[A-Za-z0-9._/+:-]+");
    private static final Pattern API_PREFIX = Pattern.compile("/[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*/");

    private final NginxChangeRequestRepository repository;
    private final ServerRepository serverRepository;
    private final SshCommandService sshCommandService;
    private final com.portfolio.deployops.repository.ExecutionGuardRepository executionGuardRepository;

    @Value("${app.nginx.upload-dir:${user.home}/.deployops/nginx-uploads}")
    private String uploadDir;

    private WasVipNginxResolver wasVipResolver;
    @org.springframework.beans.factory.annotation.Autowired
    public void setWasVipResolver(WasVipNginxResolver resolver) { this.wasVipResolver = resolver; }

    public List<WasVipNginxResolver.Choice> wasVipChoices(Server web) { return wasVipResolver.choices(web); }

    /** 승인 대기 상태로 이 기간을 넘기면 업로드한 인증서 임시본을 지운다. */
    @Value("${app.nginx.stale-upload-retention-days:7}")
    private int retentionDaysSetting;

    @Transactional(readOnly = true)
    public List<NginxChangeRequest> findAll() {
        return repository.findAll();
    }

    /**
     * 승인도 반려도 되지 않은 채 방치된 요청의 개인키를 지운다.
     *
     * <p>{@code deleteStored} 는 승인·반려 시에만 돈다. 요청만 올려 두고 아무도 처리하지
     * 않으면 개인키가 {@code app.nginx.upload-dir} 에 <b>무기한</b> 남았다. 0600 root 라도
     * 남아 있을 이유가 없는 자료다.
     *
     * <p>요청 자체는 지우지 않는다 — 이력이 사라지면 왜 적용되지 않았는지 알 수 없다.
     * 대신 인증서 파일만 지우고, 그 요청은 인증서를 다시 올려야 적용할 수 있게 된다.
     */
    @Scheduled(cron = "${app.nginx.stale-upload-cleanup-cron:0 30 3 * * *}")
    @Transactional
    public void purgeStaleUploads() {
        int retentionDays = Math.max(retentionDaysSetting, 1);
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int purged = 0;
        for (NginxChangeRequest request : repository.findStalePendingWithUploads(cutoff)) {
            deleteStored(request.getStorageKey());
            repository.clearStorageKey(request.getId());
            purged++;
        }
        if (purged > 0) {
            log.warn("승인 대기로 {}일 넘게 방치된 Nginx 변경 요청 {}건의 인증서 임시본을 삭제했습니다.",
                retentionDays, purged);
        }
    }

    @Transactional
    public NginxChangeRequest create(NginxPolicyForm form, MultipartFile certificate,
                                     MultipartFile privateKey, Long requesterId) {
        Server server = requireWebServer(form.getServerId());
        var upstreams = resolveUpstreams(form, server);
        validate(form, certificate, privateKey);

        String storageKey = hasUpload(certificate) ? UUID.randomUUID().toString() : null;
        NginxChangeRequest request = draft(form, server, upstreams, requesterId, certificate, privateKey, storageKey);
        pinTarget(request, server);
        if (form.getRawConfig() != null) {
            String raw = NginxConfigEditor.canonicalInput(form.getRawConfig());
            NginxConfigEditor.validate(raw, request);
            request.setRawConfig(raw);
            request.setRawConfigSha256(NginxConfigEditor.sha256(raw));
        }
        if (storageKey != null) storePem(storageKey, certificate, privateKey);
        try {
            repository.insert(request);
            for (var endpoint : upstreams) repository.insertUpstream(request.getId(), endpoint);
            return request;
        } catch (RuntimeException ex) {
            deleteStored(storageKey);
            throw ex;
        }
    }

    private NginxChangeRequest draft(NginxPolicyForm form, Server server,
            java.util.List<com.portfolio.deployops.domain.NginxUpstream> upstreams,
            Long requesterId, MultipartFile certificate, MultipartFile privateKey, String storageKey) {
        return NginxChangeRequest.builder()
            .server(server)
            .upstreamSnapshots(upstreams)
            .requestedBy(User.builder().id(requesterId).build())
            .status(NginxChangeStatus.PENDING)
            .policyName(form.getPolicyName().trim())
            .policyType(policyType(form))
            .serverName(form.getServerName().trim().toLowerCase(Locale.ROOT))
            .listenPort(form.getListenPort())
            .upstreamHost(isProxy(form) ? form.getUpstreamHost().trim() : "static")
            .upstreamPort(isProxy(form) ? form.getUpstreamPort() : 1)
            .secondaryUpstreamHost(form.isSecondaryUpstreamEnabled() ? form.getSecondaryUpstreamHost().trim().toLowerCase(Locale.ROOT) : null)
            .secondaryUpstreamPort(form.isSecondaryUpstreamEnabled() ? form.getSecondaryUpstreamPort() : null)
            .upstreamMaxFails(form.getUpstreamMaxFails())
            .upstreamFailTimeoutSec(form.getUpstreamFailTimeoutSec())
            .proxyConnectTimeoutSec(form.getProxyConnectTimeoutSec())
            .webRoot(isProxy(form) ? null : normalizeWebRoot(form.getWebRoot()))
            .apiProxyEnabled(form.isApiProxyEnabled())
            .apiPrefix(form.isApiProxyEnabled() ? form.getApiPrefix().trim() : null)
            .apiUpstreamHost(form.isApiProxyEnabled() ? form.getApiUpstreamHost().trim() : null)
            .apiUpstreamPort(form.isApiProxyEnabled() ? form.getApiUpstreamPort() : null)
            .websocketEnabled(form.isWebsocketEnabled())
            .tlsEnabled(form.isTlsEnabled())
            .tlsProtocols(tlsProtocols(form))
            .http2Enabled(form.isHttp2Enabled())
            .redirectHttp(form.isRedirectHttp())
            .hstsEnabled(form.isHstsEnabled())
            .clientMaxBodyMb(form.getClientMaxBodyMb())
            .proxyReadTimeoutSec(form.getProxyReadTimeoutSec())
            .proxySendTimeoutSec(form.getProxySendTimeoutSec())
            .proxyBuffering(form.isProxyBuffering())
            .securityHeaders(form.isSecurityHeaders())
            .allowedCidrs(normalizeCidrs(form.getAllowedCidrs()))
            .certificateName(blankToNull(form.getCertificateName()))
            .certificateFileName(hasUpload(certificate) ? safeDisplayName(certificate.getOriginalFilename()) : null)
            .privateKeyFileName(hasUpload(privateKey) ? safeDisplayName(privateKey.getOriginalFilename()) : null)
            .storageKey(storageKey)
            .build();
    }

    public record Configuration(String config, String sha256, boolean exists, boolean editable,
                                boolean legacyTyped, boolean managed, String fullConfigSha256,
                                java.util.Map<String,Object> editorSettings) {
        public Configuration(String config, String sha256, boolean exists, boolean editable,
                             boolean legacyTyped, boolean managed, String fullConfigSha256) {
            this(config,sha256,exists,editable,legacyTyped,managed,fullConfigSha256,null);
        }
    }

    @Transactional(readOnly = true)
    public Configuration preview(NginxPolicyForm form) {
        Server server = requireWebServer(form.getServerId());
        var upstreams = resolveUpstreams(form, server);
        validate(form, null, null);
        NginxChangeRequest request = draft(form, server, upstreams, 0L, null, null, null);
        String config = NginxConfigEditor.render(request);
        NginxConfigEditor.validate(config, request);
        return new Configuration(config, NginxConfigEditor.sha256(config), true, true, false, true, null);
    }

    @Transactional(readOnly = true)
    public Configuration requestedConfiguration(Long id) {
        NginxChangeRequest request = require(id);
        if (request.isRestoreAbsent()) return new Configuration("", null, false, false, false, true, null);
        boolean legacy = request.getRawConfig() == null;
        String config = legacy ? NginxConfigEditor.render(request) : request.getRawConfig();
        String hash = NginxConfigEditor.sha256(config);
        if (!legacy && !hash.equals(request.getRawConfigSha256()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "보관된 설정 원문의 검증 값이 일치하지 않습니다.");
        return new Configuration(config, hash, true, false, legacy, true, request.getAppliedConfigHash());
    }

    public Configuration currentConfiguration(Long serverId, String policyName) {
        if (policyName == null || !policyName.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}"))
            throw badRequest("관리 정책 이름을 확인하세요.");
        Server server = requireWebServer(serverId);
        requireEditorCapability(server);
        var result = sshCommandService.execute(server, List.of("sudo", "-n", AGENT,
            "read-config", "--policy-name", policyName));
        ensureSuccess(result);
        String output = result.stdout();
        if (output == null || output.length() > 100000) throw new RemoteExecutionException("설정 조회 응답 크기가 올바르지 않습니다.");
        String present = outputValue(output, "CURRENT_CONFIG_PRESENT=");
        if ("false".equals(present)) return new Configuration("", null, false, true, false, true, null);
        if (!"true".equals(present)) throw new RemoteExecutionException("관리 설정 존재 여부를 확인할 수 없습니다.");
        String encoded = outputValue(output, "CONFIG_BODY_BASE64=");
        String hash = outputValue(output, "CONFIG_BODY_SHA=");
        String config;
        try {
            byte[] bytes = java.util.Base64.getDecoder().decode(encoded == null ? "" : encoded);
            if (bytes.length > NginxConfigEditor.MAX_BYTES) throw new IllegalArgumentException("oversized");
            config = StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (IllegalArgumentException | java.nio.charset.CharacterCodingException ex) {
            throw new RemoteExecutionException("관리 설정 원문을 읽을 수 없습니다.");
        }
        if (!validHash(hash) || !NginxConfigEditor.sha256(config).equals(hash))
            throw new RemoteExecutionException("관리 설정 원문의 검증 값이 일치하지 않습니다.");
        String full = outputValue(output, "CURRENT_CONFIG_SHA=");
        String nativeRequestId = outputValue(output, "CURRENT_REQUEST_ID=");
        if (!validHash(full) || nativeRequestId == null || !nativeRequestId.matches("[1-9][0-9]{0,17}")
            || !NginxConfigEditor.sha256("# Generated by Deploy System. Request " + nativeRequestId + ".\n" + config).equals(full))
            throw new RemoteExecutionException("관리 설정 파일의 세대 또는 검증 값이 올바르지 않습니다.");
        return new Configuration(config, hash, true, true, false, true, full,
            currentEditorSettings(server, policyName, Long.valueOf(nativeRequestId), hash, full));
    }

    /** Reuse only completed metadata matching the actual native generation and file bytes. */
    private java.util.Map<String,Object> currentEditorSettings(Server server, String policy,
            Long nativeId, String bodyHash, String fullHash) {
        Long latest = repository.findLatestCompletedId(server.getId(), policy);
        if (latest == null) return null;
        var r = repository.findById(latest).orElse(null);
        if (r == null || r.getServer() == null || !server.getId().equals(r.getServer().getId())
            || !policy.equals(r.getPolicyName()) || r.isRestoreAbsent()
            || (r.getStatus() != NginxChangeStatus.APPLIED && r.getStatus() != NginxChangeStatus.ROLLED_BACK)
            || !nativeId.equals(r.getNativeRequestId()) || !fullHash.equals(r.getAppliedConfigHash())) return null;
        String stored = r.getRawConfig() == null ? NginxConfigEditor.render(r) : r.getRawConfig();
        if (!bodyHash.equals(NginxConfigEditor.sha256(stored))
            || (r.getRawConfig() != null && !bodyHash.equals(r.getRawConfigSha256()))) return null;
        var values = new java.util.LinkedHashMap<String,Object>();
        values.put("policyType", r.getPolicyType().name());
        values.put("serverName", r.getServerName());
        values.put("listenPort", r.getListenPort());
        values.put("webRoot", r.getWebRoot());
        values.put("apiProxyEnabled", r.isApiProxyEnabled());
        values.put("apiPrefix", r.getApiPrefix());
        values.put("websocketEnabled", r.isWebsocketEnabled());
        values.put("tlsEnabled", r.isTlsEnabled());
        values.put("http2Enabled", r.isHttp2Enabled());
        values.put("redirectHttp", r.isRedirectHttp());
        values.put("hstsEnabled", r.isHstsEnabled());
        values.put("clientMaxBodyMb", r.getClientMaxBodyMb());
        values.put("proxyReadTimeoutSec", r.getProxyReadTimeoutSec());
        values.put("proxySendTimeoutSec", r.getProxySendTimeoutSec());
        values.put("proxyBuffering", r.isProxyBuffering());
        values.put("securityHeaders", r.isSecurityHeaders());
        values.put("allowedCidrs", r.getAllowedCidrs());
        values.put("certificateName", r.getCertificateName());
        values.put("upstreamMaxFails", r.getUpstreamMaxFails());
        values.put("upstreamFailTimeoutSec", r.getUpstreamFailTimeoutSec());
        values.put("proxyConnectTimeoutSec", r.getProxyConnectTimeoutSec());
        values.put("tls12",r.getTlsProtocols() != null && r.getTlsProtocols().contains("TLSv1.2"));
        values.put("tls13",r.getTlsProtocols() != null && r.getTlsProtocols().contains("TLSv1.3"));
        var endpoints = r.getEffectiveUpstreams();
        var upstreams = new java.util.ArrayList<java.util.Map<String,Object>>();
        for (var endpoint : endpoints) {
            if (endpoint.getSourceServerId() != null) upstreams.add(java.util.Map.of(
                "serverId",endpoint.getSourceServerId(),"port",endpoint.getPort()));
        }
        values.put("upstreams",upstreams);
        values.put("wasVipSelection", "");
        boolean resolved = endpoints.isEmpty() || upstreams.size() == endpoints.size();
        if (!resolved && endpoints.size() == 1 && wasVipResolver != null) {
            var endpoint = endpoints.get(0);
            var choices = wasVipResolver.choices(server).stream().filter(c ->
                c.address().equalsIgnoreCase(endpoint.getHost()) && c.port() == endpoint.getPort()).toList();
            if (choices.size() == 1) {
                var choice = choices.get(0);
                values.put("wasVipSelection",choice.projectId()+":"+choice.groupId()); resolved = true;
            }
        }
        values.put("upstreamsResolved",resolved);
        return java.util.Collections.unmodifiableMap(values);
    }

    private void requireEditorCapability(Server server) {
        var capability = sshCommandService.execute(server, List.of("sudo", "-n", AGENT, "inspect"));
        ensureSuccess(capability);
        if (!"1".equals(outputValue(capability.output(), "EDITOR_CONFIG_VERSION=")))
            throw new RemoteExecutionException("WEB 에이전트를 최신 설치기로 업데이트한 뒤 설정 편집을 사용하세요.");
    }

    @Transactional
    public NginxChangeRequest requestRollback(Long appliedRequestId, Long requesterId) {
        NginxChangeRequest source = require(appliedRequestId);
        if ((source.getStatus() != NginxChangeStatus.APPLIED && source.getStatus() != NginxChangeStatus.ROLLED_BACK) || source.getBackupPath() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "복구 가능한 적용 완료 요청이 아닙니다.");
        }
        assertLatestAppliedGeneration(source);
        if (!validHash(source.getBackupSnapshotHash()) || !validHash(source.getAppliedSnapshotHash())) {
            throw badRequest("이전 백업의 검증된 복원 정보가 없습니다. 기존 백업은 자동 신뢰하지 않습니다. 검증된 새 정책을 적용한 후, 그 다음 변경에서 생성된 백업으로 복구하세요.");
        }
        RemoteCommandResult described = sshCommandService.execute(source.getServer(), List.of("sudo", "-n", AGENT,
            "describe-backup", "--backup-dir", source.getBackupPath(), "--policy-name", source.getPolicyName(),
            "--expected-current-request-id", nativeId(source.getNativeRequestId()),
            "--expected-current-snapshot", source.getAppliedSnapshotHash()));
        ensureSuccess(described);
        NativeSnapshot description = nativeSnapshot(described.stdout(), "TARGET_");
        if (!description.hash().equals(source.getBackupSnapshotHash())) throw badRequest("복구 백업이 적용 당시 기록과 다릅니다.");
        NginxChangeRequest target = source;
        if (description.present()) {
            target = require(description.nativeId());
            if (!java.util.Objects.equals(target.getServer().getId(), source.getServer().getId())
                || !target.getPolicyName().equals(source.getPolicyName())
                || !description.configHash().equals(target.getAppliedConfigHash())
                || !description.hash().equals(target.getAppliedSnapshotHash())) {
                throw badRequest("백업의 실제 설정과 승인 이력이 일치하지 않습니다. 복원 대상을 확인할 수 없습니다.");
            }
        }
        NginxChangeRequest rollback = NginxChangeRequest.builder()
            .server(source.getServer()).requestedBy(User.builder().id(requesterId).build())
            .rollbackOfId(source.getId()).status(NginxChangeStatus.PENDING)
            .policyName(target.getPolicyName()).rawConfig(target.getRawConfig()).rawConfigSha256(target.getRawConfigSha256()).serverName(target.getServerName())
            .policyType(target.getPolicyType())
            .listenPort(target.getListenPort()).upstreamHost(target.getUpstreamHost()).upstreamPort(target.getUpstreamPort())
            .secondaryUpstreamHost(target.getSecondaryUpstreamHost()).secondaryUpstreamPort(target.getSecondaryUpstreamPort())
            .upstreamMaxFails(target.getUpstreamMaxFails()).upstreamFailTimeoutSec(target.getUpstreamFailTimeoutSec())
            .proxyConnectTimeoutSec(target.getProxyConnectTimeoutSec())
            .webRoot(target.getWebRoot())
            .apiProxyEnabled(target.isApiProxyEnabled()).apiPrefix(target.getApiPrefix())
            .apiUpstreamHost(target.getApiUpstreamHost()).apiUpstreamPort(target.getApiUpstreamPort())
            .websocketEnabled(target.isWebsocketEnabled()).tlsEnabled(target.isTlsEnabled())
            .tlsProtocols(target.getTlsProtocols()).http2Enabled(target.isHttp2Enabled())
            .redirectHttp(target.isRedirectHttp()).hstsEnabled(target.isHstsEnabled())
            .clientMaxBodyMb(target.getClientMaxBodyMb()).proxyReadTimeoutSec(target.getProxyReadTimeoutSec())
            .proxySendTimeoutSec(target.getProxySendTimeoutSec()).proxyBuffering(target.isProxyBuffering())
            .securityHeaders(target.isSecurityHeaders()).allowedCidrs(target.getAllowedCidrs())
            .certificateName(target.getCertificateName()).backupPath(source.getBackupPath())
            .rollbackSnapshotHash(description.hash()).expectedNativeRequestId(source.getNativeRequestId())
            .expectedCurrentSnapshotHash(source.getAppliedSnapshotHash())
            .restoreTargetId(description.nativeId()).restoreAbsent(!description.present()).build();
        pinTarget(rollback, source.getServer());
        rollback.setUpstreamSnapshots(description.present() ? target.getEffectiveUpstreams() : java.util.List.of());
        repository.insert(rollback);
        for (var endpoint : rollback.getUpstreamSnapshots()) repository.insertUpstream(rollback.getId(), endpoint);
        return rollback;
    }

    @Transactional(noRollbackFor = RuntimeException.class)
    public NginxChangeRequest approveAndApply(Long id, Long approverId) {
        if (executionGuardRepository.lockSingleton() == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "인프라 변경 실행 잠금 정보를 찾을 수 없습니다.");
        }
        NginxChangeRequest request = require(id);
        if (request.getRequestedBy().getId().equals(approverId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "요청자와 승인자는 달라야 합니다.");
        }
        assertTargetBinding(request);
        NginxChangeRequest rollbackSource = null;
        if (request.getRollbackOfId() != null) {
            NginxChangeRequest source = require(request.getRollbackOfId());
            assertLatestAppliedGeneration(source);
            if (!validHash(request.getRollbackSnapshotHash()) || !validHash(request.getExpectedCurrentSnapshotHash())
                || !request.getRollbackSnapshotHash().equals(source.getBackupSnapshotHash())
                || !request.getExpectedCurrentSnapshotHash().equals(source.getAppliedSnapshotHash())
                || !java.util.Objects.equals(request.getExpectedNativeRequestId(), source.getNativeRequestId())
                || !java.util.Objects.equals(request.getServer().getId(), source.getServer().getId())
                || !request.getPolicyName().equals(source.getPolicyName())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "승인할 복구 snapshot의 현재 세대 정보가 다릅니다.");
            }
            rollbackSource = source;
        }
        int acquired = rollbackSource == null
            ? repository.acquireApproval(id, approverId)
            : repository.acquireRollbackApproval(id, approverId, rollbackSource.getId(),
                rollbackSource.getServer().getId(), rollbackSource.getPolicyName());
        if (acquired != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 처리되었거나 승인할 수 없는 요청입니다.");
        }

        try {
            RemoteCommandResult result = request.getRollbackOfId() == null ? apply(request) : rollback(request);
            if (result.exitCode() != 0) {
                throw new RemoteExecutionException(nonBlank(result.stderr(), result.stdout()));
            }
            NativeSnapshot actual = nativeSnapshot(result.stdout(), "CURRENT_");
            String backupHash = requiredHash(result.stdout(), "BACKUP_SNAPSHOT_SHA=");
            String backupPath = outputValue(result.stdout(), "BACKUP_PATH=");
            if (backupPath == null || !backupPath.matches("^/var/backups/deploy-system/nginx/[A-Za-z0-9._-]+$"))
                throw new RemoteExecutionException("백업 식별자 증거가 올바르지 않습니다.");
            if (request.getRollbackOfId() != null) {
                if (!actual.hash().equals(request.getRollbackSnapshotHash())
                    || actual.present() == request.isRestoreAbsent()
                    || !java.util.Objects.equals(actual.nativeId(), request.getRestoreTargetId()))
                    throw new RemoteExecutionException("실제 복원 결과가 승인된 백업 snapshot과 다릅니다.");
            } else if (!java.util.Objects.equals(actual.nativeId(), request.getId()) || !actual.present()) {
                throw new RemoteExecutionException("실제 적용 세대 증거가 올바르지 않습니다.");
            }
            if (request.getRollbackOfId() == null && request.getRawConfig() != null) {
                String rawHash = NginxConfigEditor.sha256(request.getRawConfig());
                String fullHash = NginxConfigEditor.sha256("# Generated by Deploy System. Request " + request.getId() + ".\n" + request.getRawConfig());
                if (!rawHash.equals(outputValue(result.stdout(), "CONFIG_BODY_SHA=")) || !fullHash.equals(actual.configHash()))
                    throw new RemoteExecutionException("실제 적용된 설정이 승인한 원문과 일치하지 않습니다.");
            }
            if (repository.storeNativeProof(id, backupHash, actual.hash(), actual.configHash(), actual.nativeId()) != 1)
                throw new RemoteExecutionException("실제 설정 증거를 저장하지 못했습니다.");
            NginxChangeStatus completed = request.getRollbackOfId() == null
                ? NginxChangeStatus.APPLIED : NginxChangeStatus.ROLLED_BACK;
            repository.finish(id, completed, backupPath, null);
            storeCertificateMetadata(id, result.stdout());
            if (request.getRollbackOfId() != null) {
                repository.markSourceRolledBack(request.getRollbackOfId());
            }
        } catch (RuntimeException ex) {
            repository.finish(id, ex instanceof AgentOutcomeUnknownException
                ? NginxChangeStatus.RESULT_UNKNOWN : NginxChangeStatus.FAILED,
                request.getBackupPath(), abbreviate(ex.getMessage(), 2000));
            throw ex;
        } finally {
            deleteStored(request.getStorageKey());
        }
        return require(id);
    }

    @Transactional
    public void reject(Long id, Long approverId, String reason) {
        NginxChangeRequest request = require(id);
        if (request.getRequestedBy().getId().equals(approverId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "요청자와 승인자는 달라야 합니다.");
        }
        String normalized = blankToNull(reason);
        if (normalized == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "반려 사유를 입력하세요.");
        }
        if (repository.reject(id, approverId, abbreviate(normalized, 2000)) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 처리된 요청입니다.");
        }
        deleteStored(request.getStorageKey());
    }

    public String inspect(Long serverId) {
        Server server = requireWebServer(serverId);
        RemoteCommandResult result = sshCommandService.execute(server, List.of("sudo", "-n", AGENT, "inspect"));
        if (result.exitCode() != 0) {
            throw new RemoteExecutionException(nonBlank(result.stderr(), result.stdout()));
        }
        return result.stdout();
    }

    private RemoteCommandResult apply(NginxChangeRequest request) {
        Server server = request.getServer();
        boolean listMode = request.getUpstreamSnapshots() != null && !request.getUpstreamSnapshots().isEmpty();
        boolean rawMode = request.getRawConfig() != null;
        if (rawMode) {
            NginxConfigEditor.validate(request.getRawConfig(), request);
            if (!NginxConfigEditor.sha256(request.getRawConfig()).equals(request.getRawConfigSha256()))
                throw new RemoteExecutionException("승인할 설정 원문의 검증 값이 일치하지 않습니다.");
            requireEditorCapability(server);
        }
        if (listMode) {
            validateFrozenUpstreams(request);
            var capability = sshCommandService.execute(server, List.of("sudo", "-n", AGENT, "inspect"));
            ensureSuccess(capability);
            if (!"1".equals(outputValue(capability.output(), "UPSTREAM_LIST_VERSION=")))
                throw new RemoteExecutionException("WEB 에이전트를 정상 설치기로 업데이트한 뒤 WAS 목록 정책을 적용하세요.");
        }
        String remoteDir = "/var/tmp/deploy-ops/nginx-change-" + request.getId();
        boolean staging = rawMode || request.getStorageKey() != null;
        Path approvedFile = null;
        try {
            if (staging) ensureSuccess(sshCommandService.execute(server, List.of("mkdir", "-m", "700", "-p", remoteDir)));
            if (request.getStorageKey() != null) {
                ensureSuccess(sshCommandService.copyFile(server, storagePath(request.getStorageKey()).resolve("certificate.pem"), remoteDir + "/certificate.pem"));
                ensureSuccess(sshCommandService.copyFile(server, storagePath(request.getStorageKey()).resolve("private-key.pem"), remoteDir + "/private-key.pem"));
            }
            List<String> args = policyArguments(request, remoteDir);
            if (rawMode) {
                approvedFile = Files.createTempFile("deploy-nginx-approved-", ".conf");
                setOwnerOnly(approvedFile, false);
                Files.writeString(approvedFile, request.getRawConfig(), StandardCharsets.UTF_8);
                ensureSuccess(sshCommandService.copyFile(server, approvedFile, remoteDir + "/policy.conf"));
                args.addAll(List.of("--config-file", remoteDir + "/policy.conf", "--config-sha256", request.getRawConfigSha256()));
            }
            return sshCommandService.execute(server, args);
        } catch (IOException ex) {
            throw new RemoteExecutionException("승인된 설정 원문을 임시 전송할 수 없습니다.", ex);
        } finally {
            if (approvedFile != null) try { Files.deleteIfExists(approvedFile); }
            catch (IOException ex) { log.warn("Nginx 원문 로컬 staging 정리 실패: requestId={}", request.getId()); }
            if (staging) try { sshCommandService.execute(server, List.of("rm", "-rf", "--", remoteDir)); }
            catch (RuntimeException ex) { log.warn("Nginx 원격 staging 정리 실패: requestId={} server={}", request.getId(), server.getName()); }
        }
    }

    private List<String> policyArguments(NginxChangeRequest request, String remoteDir) {
        boolean listMode = request.getUpstreamSnapshots() != null && !request.getUpstreamSnapshots().isEmpty();
        List<String> args = new ArrayList<>(List.of(
            "sudo", "-n", AGENT, "apply",
            "--request-id", String.valueOf(request.getId()),
            "--policy-name", request.getPolicyName(),
            "--policy-type", request.getPolicyType().name(),
            "--server-name", request.getServerName(),
            "--listen", String.valueOf(request.getListenPort()),
            "--upstream-host", request.getUpstreamHost(),
            "--upstream-port", String.valueOf(request.getUpstreamPort()),
            "--web-root", request.getWebRoot() == null ? "" : request.getWebRoot(),
            "--api-proxy", bool(request.isApiProxyEnabled()),
            "--api-prefix", request.isApiProxyEnabled() ? request.getApiPrefix() : "",
            "--api-upstream-host", request.isApiProxyEnabled() ? request.getApiUpstreamHost() : "",
            "--api-upstream-port", request.isApiProxyEnabled() ? String.valueOf(request.getApiUpstreamPort()) : "1",
            "--websocket", bool(request.isWebsocketEnabled()),
            "--tls", bool(request.isTlsEnabled()),
            "--tls-protocols", request.getTlsProtocols(),
            "--http2", bool(request.isHttp2Enabled()),
            "--redirect-http", bool(request.isRedirectHttp()),
            "--hsts", bool(request.isHstsEnabled()),
            "--client-max-body-mb", String.valueOf(request.getClientMaxBodyMb()),
            "--proxy-read-timeout", String.valueOf(request.getProxyReadTimeoutSec()),
            "--proxy-send-timeout", String.valueOf(request.getProxySendTimeoutSec()),
            "--proxy-buffering", bool(request.isProxyBuffering()),
            "--security-headers", bool(request.isSecurityHeaders()),
            "--allowed-cidrs", request.getAllowedCidrs() == null ? "" : request.getAllowedCidrs(),
            "--certificate-name", request.getCertificateName() == null ? request.getPolicyName() : request.getCertificateName()
        ));
        if (listMode) {
            for(String flag:List.of("--upstream-host","--upstream-port","--api-upstream-host","--api-upstream-port")) {
                int index=args.indexOf(flag); args.remove(index+1); args.remove(index);
            }
            args.addAll(List.of("--upstream-purpose",request.getPolicyType()==NginxPolicyType.REVERSE_PROXY?"PROXY":"API",
                "--upstream-max-fails",String.valueOf(request.getUpstreamMaxFails()),"--upstream-fail-timeout",String.valueOf(request.getUpstreamFailTimeoutSec()),"--proxy-connect-timeout",String.valueOf(request.getProxyConnectTimeoutSec())));
            for(var endpoint:request.getEffectiveUpstreams()) args.addAll(List.of("--upstream-endpoint",endpoint.getHost(),String.valueOf(endpoint.getPort())));
        } else if (request.hasSecondaryUpstream()) {
            args.addAll(List.of("--secondary-upstream-host", request.getSecondaryUpstreamHost(),
                "--secondary-upstream-port", String.valueOf(request.getSecondaryUpstreamPort()),
                "--upstream-max-fails", String.valueOf(request.getUpstreamMaxFails()),
                "--upstream-fail-timeout", String.valueOf(request.getUpstreamFailTimeoutSec()),
                "--proxy-connect-timeout", String.valueOf(request.getProxyConnectTimeoutSec())));
        }
        if (request.getStorageKey() != null) {
            args.addAll(List.of("--certificate", remoteDir + "/certificate.pem", "--private-key", remoteDir + "/private-key.pem"));
        }
        return args;
    }

    private RemoteCommandResult rollback(NginxChangeRequest request) {
        return sshCommandService.execute(request.getServer(), List.of(
            "sudo", "-n", AGENT, "rollback", "--backup-dir", request.getBackupPath(),
            "--policy-name", request.getPolicyName(), "--expected-current-request-id", nativeId(request.getExpectedNativeRequestId()),
            "--expected-current-snapshot", request.getExpectedCurrentSnapshotHash(),
            "--expected-snapshot", request.getRollbackSnapshotHash()));
    }

    private record NativeSnapshot(String hash, String configHash, Long nativeId, boolean present) {}
    private static boolean validHash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static String nativeId(Long id) { return id == null ? "absent" : String.valueOf(id); }
    private String requiredHash(String output, String key) {
        String value = outputValue(output, key);
        if (!validHash(value)) throw new RemoteExecutionException("백업 digest 증거가 올바르지 않습니다.");
        return value;
    }
    private NativeSnapshot nativeSnapshot(String output, String prefix) {
        if (output == null || output.length() > 16384) throw new RemoteExecutionException("백업 증거 크기가 올바르지 않습니다.");
        String hash = requiredHash(output, prefix + "SNAPSHOT_SHA=");
        String present = outputValue(output, prefix + "CONFIG_PRESENT=");
        String id = outputValue(output, prefix + "REQUEST_ID=");
        String configHash = outputValue(output, prefix + "CONFIG_SHA=");
        if ("false".equals(present) && "absent".equals(id) && "absent".equals(configHash))
            return new NativeSnapshot(hash, null, null, false);
        if (!"true".equals(present) || id == null || !id.matches("[1-9][0-9]{0,17}") || !validHash(configHash))
            throw new RemoteExecutionException("복원 설정 세대 증거가 올바르지 않습니다.");
        return new NativeSnapshot(hash, configHash, Long.valueOf(id), true);
    }

    private void validateFrozenUpstreams(NginxChangeRequest request) {
        var rows=request.getEffectiveUpstreams();
        if(rows.isEmpty() || rows.size()>32 || rows.size()!=request.getUpstreamSnapshots().size()
            || (request.getPolicyType()!=NginxPolicyType.REVERSE_PROXY && !request.isApiProxyEnabled()))
            throw new RemoteExecutionException("보관된 WAS 목록을 확인할 수 없습니다.");
        var duplicates=new java.util.HashSet<String>();
        for(int i=0;i<rows.size();i++){
            var row=rows.get(i);
            if(row.getPosition()!=i || row.getSourceServerId()==null || row.getSourceServerId()<1 || row.getPort()<1 || row.getPort()>65535)
                throw new RemoteExecutionException("보관된 WAS 순서 또는 포트가 올바르지 않습니다.");
            String host=normalizedUpstreamHost(row.getHost());
            if(!host.equals(row.getHost()) || !duplicates.add(host+":"+row.getPort()))
                throw new RemoteExecutionException("보관된 WAS 주소가 변경되었거나 중복됐습니다.");
        }
    }

    private java.util.List<com.portfolio.deployops.domain.NginxUpstream> resolveUpstreams(NginxPolicyForm form, Server web) {
        if (form.getWasVipProjectId() != null || form.getWasVipGroupId() != null) {
            wasVipResolver.resolve(form, web);
            return java.util.List.of();
        }
        if (!form.isUpstreamListPresent() && form.getUpstreams() == null) return java.util.List.of();
        if (!(isProxy(form) || form.isApiProxyEnabled())) {
            if (form.getUpstreams()!=null && !form.getUpstreams().isEmpty()) throw badRequest("정적 전용 정책에는 WAS 목록을 사용할 수 없습니다.");
            return java.util.List.of();
        }
        if (form.getUpstreams()==null || form.getUpstreams().isEmpty() || form.getUpstreams().size()>32)
            throw badRequest("WAS를 1~32개 선택하세요.");
        if (form.isLegacyUpstreamPortPresent() || form.isLegacyApiUpstreamPortPresent() || blankToNull(form.getUpstreamHost())!=null || blankToNull(form.getApiUpstreamHost())!=null
            || form.isSecondaryUpstreamEnabled() || blankToNull(form.getSecondaryUpstreamHost())!=null || form.getSecondaryUpstreamPort()!=null)
            throw badRequest("WAS 목록과 이전 upstream 필드를 함께 보낼 수 없습니다.");
        var result = new java.util.ArrayList<com.portfolio.deployops.domain.NginxUpstream>();
        var duplicates = new java.util.HashSet<String>();
        String purpose=isProxy(form)?"PROXY":"API";
        for (var row:form.getUpstreams()) {
            if(row==null || row.getServerId()==null || row.getPort()==null || row.getPort()<1 || row.getPort()>65535) throw badRequest("WAS와 올바른 서비스 포트를 선택하세요.");
            Server was=serverRepository.findById(row.getServerId()).orElseThrow(()->badRequest("등록된 WAS 서버를 찾을 수 없습니다."));
            if(was.getServerType()!=ServerType.WAS) throw badRequest("upstream에는 WAS 서버만 선택할 수 있습니다.");
            if(web.getEnvironment()==null || was.getEnvironment()!=web.getEnvironment())
                throw badRequest("선택한 WEB과 같은 환경의 WAS만 사용할 수 있습니다.");
            String host=normalizedUpstreamHost(was.getIpAddress());
            if(!duplicates.add(host+":"+row.getPort())) throw badRequest("동일한 WAS 주소와 포트를 중복 선택할 수 없습니다.");
            result.add(new com.portfolio.deployops.domain.NginxUpstream(purpose,result.size(),was.getId(),host,row.getPort()));
        }
        var first=result.getFirst();
        if(isProxy(form)){form.setUpstreamHost(first.getHost());form.setUpstreamPort(first.getPort());}
        else {form.setApiUpstreamHost(first.getHost());form.setApiUpstreamPort(first.getPort());}
        if(result.size()>1){var second=result.get(1);form.setSecondaryUpstreamEnabled(true);form.setSecondaryUpstreamHost(second.getHost());form.setSecondaryUpstreamPort(second.getPort());}
        return java.util.List.copyOf(result);
    }

    private static boolean validUpstreamHost(String host) {
        try { normalizedUpstreamHost(host); return true; } catch(ResponseStatusException ex) { return false; }
    }

    static String normalizedUpstreamHost(String input) {
        if(input==null) throw badRequest("WAS 주소가 없습니다.");
        String value=input.trim().toLowerCase(Locale.ROOT);
        if(value.startsWith("[") && value.endsWith("]")) value=value.substring(1,value.length()-1);
        if(value.contains(":")) {
            if(!value.matches("[0-9a-f:]{2,39}")) throw badRequest("WAS IPv6 주소가 올바르지 않습니다.");
            try {return java.net.InetAddress.getByName(value).getHostAddress();}
            catch(java.net.UnknownHostException ex){throw badRequest("WAS IPv6 주소가 올바르지 않습니다.");}
        }
        if(value.matches("[0-9.]+")) {
            String[] parts=value.split("\\.",-1);
            if(parts.length!=4) throw badRequest("WAS IPv4 주소가 올바르지 않습니다.");
            var normalized=new java.util.ArrayList<String>();
            for(String part:parts){if(!part.matches("[0-9]{1,3}") || Integer.parseInt(part)>255)throw badRequest("WAS IPv4 주소가 올바르지 않습니다.");normalized.add(String.valueOf(Integer.parseInt(part)));}
            return String.join(".",normalized);
        }
        if(!HOST.matcher(value).matches()) throw badRequest("WAS 주소가 올바르지 않습니다.");
        return value;
    }

    private void validate(NginxPolicyForm form, MultipartFile certificate, MultipartFile privateKey) {
        if (form.getUpstreamMaxFails() < 1 || form.getUpstreamMaxFails() > 10
            || form.getUpstreamFailTimeoutSec() < 1 || form.getUpstreamFailTimeoutSec() > 300
            || form.getProxyConnectTimeoutSec() < 1 || form.getProxyConnectTimeoutSec() > 30) {
            throw badRequest("upstream 실패 처리 범위가 올바르지 않습니다.");
        }
        if (form.isApiProxyEnabled()) {
            if (isProxy(form)) {
                throw badRequest("API 경로 프록시는 정적 SPA 또는 정적 파일 정책에서만 사용할 수 있습니다.");
            }
            String prefix = blankToNull(form.getApiPrefix());
            if (prefix == null || prefix.length() > 80 || !API_PREFIX.matcher(prefix).matches()) {
                throw badRequest("API 경로는 /api/처럼 영문·숫자·밑줄·하이픈으로 작성하고 /로 끝내세요.");
            }
            if (form.getApiUpstreamHost() == null || !validUpstreamHost(form.getApiUpstreamHost())) {
                throw badRequest("API upstream 호스트 형식이 올바르지 않습니다.");
            }
            if (form.getApiUpstreamPort() == null || form.getApiUpstreamPort() < 1 || form.getApiUpstreamPort() > 65535) {
                throw badRequest("API upstream 포트는 1~65535 사이로 입력하세요.");
            }
        }
        if (isProxy(form)) {
            if (form.getUpstreamHost() == null || !validUpstreamHost(form.getUpstreamHost())) {
                throw badRequest("리버스 프록시는 올바른 upstream 호스트가 필요합니다.");
            }
            if (form.getUpstreamPort() < 1 || form.getUpstreamPort() > 65535) {
                throw badRequest("리버스 프록시 upstream 포트가 올바르지 않습니다.");
            }
        } else {
            normalizeWebRoot(form.getWebRoot());
        }
        if (form.isSecondaryUpstreamEnabled()) {
            if (!(isProxy(form) || form.isApiProxyEnabled()) || form.getSecondaryUpstreamHost() == null
                || !validUpstreamHost(form.getSecondaryUpstreamHost())
                || form.getSecondaryUpstreamPort() == null || form.getSecondaryUpstreamPort() < 1
                || form.getSecondaryUpstreamPort() > 65535) {
                throw badRequest("두 번째 upstream은 프록시 또는 정적 API 경로의 올바른 호스트와 포트가 필요합니다.");
            }
            String primaryHost = isProxy(form) ? form.getUpstreamHost() : form.getApiUpstreamHost();
            int primaryPort = isProxy(form) ? form.getUpstreamPort() : form.getApiUpstreamPort();
            if (primaryHost.trim().equalsIgnoreCase(form.getSecondaryUpstreamHost().trim())
                && primaryPort == form.getSecondaryUpstreamPort()) {
                throw badRequest("두 upstream은 서로 다른 주소여야 합니다.");
            }
        } else if (blankToNull(form.getSecondaryUpstreamHost()) != null || form.getSecondaryUpstreamPort() != null) {
            throw badRequest("사용하지 않는 두 번째 upstream 필드는 비워야 합니다.");
        }
        if (!SERVER_NAME.matcher(form.getServerName().trim()).matches()) {
            throw badRequest("server name 형식이 올바르지 않습니다.");
        }
        if (form.isTlsEnabled() && !form.isTls12() && !form.isTls13()) {
            throw badRequest("TLS 1.2 또는 TLS 1.3을 하나 이상 허용해야 합니다.");
        }
        if (!form.isTlsEnabled() && (hasUpload(certificate) || hasUpload(privateKey))) {
            throw badRequest("TLS를 사용해야 인증서를 업로드할 수 있습니다.");
        }
        if (hasUpload(certificate) != hasUpload(privateKey)) {
            throw badRequest("인증서와 개인키를 함께 업로드하세요.");
        }
        normalizeCidrs(form.getAllowedCidrs());
        if (hasUpload(certificate)) {
            validatePem(certificate, privateKey);
        }
    }

    private NginxPolicyType policyType(NginxPolicyForm form) {
        try {
            return NginxPolicyType.valueOf(form.getPolicyType() == null
                ? NginxPolicyType.REVERSE_PROXY.name() : form.getPolicyType().trim());
        } catch (IllegalArgumentException ex) {
            throw badRequest("Nginx 정책 종류가 올바르지 않습니다.");
        }
    }

    private boolean isProxy(NginxPolicyForm form) {
        return policyType(form) == NginxPolicyType.REVERSE_PROXY;
    }

    private String normalizeWebRoot(String raw) {
        String value = blankToNull(raw);
        if (value == null || !WEB_ROOT.matcher(value).matches()
                || value.matches(".*(^|/)\\.\\.(/|$).*") || value.endsWith("/current")) {
            throw badRequest("정적 정책 web root는 /var/www 또는 /srv/web 아래의 배포 루트여야 합니다(current 제외).");
        }
        return value;
    }

    private void validatePem(MultipartFile certificate, MultipartFile privateKey) {
        if (certificate.getSize() > MAX_PEM_BYTES || privateKey.getSize() > MAX_PEM_BYTES) {
            throw badRequest("인증서와 개인키는 각각 2MB 이하여야 합니다.");
        }
        try {
            byte[] certBytes = certificate.getBytes();
            byte[] keyBytes = privateKey.getBytes();
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            var certificates = certificateFactory.generateCertificates(new ByteArrayInputStream(certBytes));
            if (certificates.isEmpty()) {
                throw badRequest("X.509 PEM 인증서를 읽을 수 없습니다.");
            }
            X509Certificate serverCertificate = (X509Certificate) certificateFactory
                .generateCertificate(new ByteArrayInputStream(certBytes));
            if (serverCertificate.getBasicConstraints() >= 0) {
                throw badRequest("CA 인증서를 서버 인증서로 사용할 수 없습니다. 서버 인증서를 PEM 파일의 첫 항목으로 넣어 주세요.");
            }
            String key = new String(keyBytes, StandardCharsets.US_ASCII);
            if (!key.contains("-----BEGIN ") || !key.contains("PRIVATE KEY-----")) {
                throw badRequest("PEM 형식의 개인키가 아닙니다.");
            }
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw badRequest("X.509 PEM 인증서를 읽을 수 없습니다.");
        }
    }

    private void storePem(String storageKey, MultipartFile certificate, MultipartFile privateKey) {
        Path dir = storagePath(storageKey);
        try {
            Files.createDirectories(dir);
            setOwnerOnly(dir, true);
            Files.write(dir.resolve("certificate.pem"), certificate.getBytes());
            Files.write(dir.resolve("private-key.pem"), privateKey.getBytes());
            setOwnerOnly(dir.resolve("certificate.pem"), false);
            setOwnerOnly(dir.resolve("private-key.pem"), false);
        } catch (IOException ex) {
            deleteStored(storageKey);
            throw new IllegalStateException("인증서 임시 보관에 실패했습니다.", ex);
        }
    }

    private void setOwnerOnly(Path path, boolean directory) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows 개발 환경은 POSIX 권한을 지원하지 않는다. 운영 Linux에서는 위 권한이 적용된다.
        }
    }

    private Path storagePath(String storageKey) {
        if (storageKey == null || !storageKey.matches("[0-9a-f-]{36}")) {
            throw new IllegalArgumentException("잘못된 인증서 보관 키입니다.");
        }
        return Path.of(uploadDir).toAbsolutePath().normalize().resolve(storageKey);
    }

    private void deleteStored(String storageKey) {
        if (storageKey == null) return;
        try {
            Path dir = storagePath(storageKey);
            Files.deleteIfExists(dir.resolve("certificate.pem"));
            Files.deleteIfExists(dir.resolve("private-key.pem"));
            Files.deleteIfExists(dir);
        } catch (IOException ex) {
            log.warn("Nginx 인증서 로컬 staging 정리 실패: storageKey={}", storageKey);
        }
    }

    private void pinTarget(NginxChangeRequest request, Server server) {
        if (server.isAgentControlled()
            && (server.getAgentBindingId() == null || server.getAgentBindingId().isBlank())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "WEB 에이전트 등록을 승인하고 연결을 확인한 뒤 설정 변경을 요청해 주세요.");
        }
        request.setRequestedControlMode(server.isAgentControlled()
            ? com.portfolio.deployops.domain.ServerControlMode.AGENT : com.portfolio.deployops.domain.ServerControlMode.SSH);
        request.setRequestedAgentBindingId(server.isAgentControlled() ? server.getAgentBindingId() : null);
    }

    private void assertTargetBinding(NginxChangeRequest request) {
        Server server = request.getServer();
        var expectedMode = request.getRequestedControlMode() == null
            ? com.portfolio.deployops.domain.ServerControlMode.SSH : request.getRequestedControlMode();
        var actualMode = server.isAgentControlled()
            ? com.portfolio.deployops.domain.ServerControlMode.AGENT : com.portfolio.deployops.domain.ServerControlMode.SSH;
        if (expectedMode != actualMode || (server.isAgentControlled()
            && (request.getRequestedAgentBindingId() == null || request.getRequestedAgentBindingId().isBlank()
                || !java.util.Objects.equals(request.getRequestedAgentBindingId(), server.getAgentBindingId())))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "요청 이후 WEB 접속 방식 또는 에이전트 등록이 변경되었습니다. 새 설정 변경 요청으로 다시 승인받아 주세요.");
        }
    }

    private Server requireWebServer(Long id) {
        Server server = serverRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "서버를 찾을 수 없습니다."));
        if (server.getServerType() != ServerType.WEB) {
            throw badRequest("Nginx 정책은 WEB 서버에만 적용할 수 있습니다.");
        }
        return server;
    }

    private NginxChangeRequest require(Long id) {
        return repository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nginx 변경 요청을 찾을 수 없습니다."));
    }

    private void assertLatestAppliedGeneration(NginxChangeRequest source) {
        Long latest = repository.findLatestCompletedId(source.getServer().getId(), source.getPolicyName());
        if (!java.util.Objects.equals(latest, source.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "이 요청 뒤에 더 최신 Nginx 정책 동작이 있습니다. 최신 적용 건에서만 복구를 요청하세요.");
        }
    }

    private String normalizeCidrs(String raw) {
        if (raw == null || raw.isBlank()) return null;
        List<String> values = new ArrayList<>();
        for (String value : raw.split("[,\\r\\n]+")) {
            String cidr = value.trim();
            if (!cidr.isEmpty()) {
                if (!CIDR.matcher(cidr).matches()) throw badRequest("허용 IP/CIDR 형식이 올바르지 않습니다: " + cidr);
                values.add(cidr);
            }
        }
        return values.isEmpty() ? null : String.join(",", values);
    }

    private String tlsProtocols(NginxPolicyForm form) {
        List<String> values = new ArrayList<>();
        if (form.isTls12()) values.add("TLSv1.2");
        if (form.isTls13()) values.add("TLSv1.3");
        return String.join(" ", values);
    }

    private void ensureSuccess(RemoteCommandResult result) {
        if (result.exitCode() != 0) throw new RemoteExecutionException(nonBlank(result.stderr(), result.stdout()));
    }

    /**
     * 에이전트가 apply 성공 시 돌려주는 인증서 신원·만료를 저장한다.
     *
     * <p>예전에는 에이전트가 subject/issuer/enddate 를 찍는데도 {@code BACKUP_PATH=} 만
     * 파싱하고 나머지를 버렸다. 그래서 시스템이 인증서 만료를 전혀 추적하지 못했고,
     * 만료는 장애로만 알 수 있었다.
     *
     * <p>여기서 실패해도 적용을 되돌리지 않는다 — nginx 에는 이미 정상 반영된 뒤이고,
     * 메타데이터가 없다고 서비스가 멈추는 것이 더 나쁘다. 대신 경고로 남긴다.
     */
    private void storeCertificateMetadata(Long id, String stdout) {
        String notAfter = outputValue(stdout, "CERT_NOT_AFTER=");
        String subject = outputValue(stdout, "CERT_SUBJECT=");
        if (notAfter == null && subject == null) {
            return;
        }
        try {
            repository.updateCertificateMetadata(
                id,
                abbreviate(subject, 500),
                abbreviate(outputValue(stdout, "CERT_ISSUER="), 500),
                abbreviate(outputValue(stdout, "CERT_SANS="), 1000),
                parseNotAfter(notAfter));
        } catch (RuntimeException ex) {
            log.warn("Nginx 인증서 메타데이터 저장 실패: requestId={} reason={}", id, ex.getMessage());
        }
    }

    private java.time.LocalDateTime parseNotAfter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return java.time.LocalDateTime.parse(value.trim());
        } catch (java.time.format.DateTimeParseException ex) {
            log.warn("인증서 만료일 형식을 해석하지 못했습니다: {}", value);
            return null;
        }
    }

    private String outputValue(String output, String prefix) {
        if (output == null) return null;
        List<String> values = output.lines().filter(line -> line.startsWith(prefix)).map(line -> line.substring(prefix.length()).trim()).toList();
        if (values.size() > 1) throw new RemoteExecutionException("중복된 agent 증거 항목입니다.");
        return values.isEmpty() ? null : values.getFirst();
    }

    private boolean hasUpload(MultipartFile file) {
        return file != null && !file.isEmpty();
    }

    private String safeDisplayName(String value) {
        if (value == null) return null;
        return Path.of(value).getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private String bool(boolean value) { return value ? "true" : "false"; }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String nonBlank(String first, String second) { return first != null && !first.isBlank() ? first : second; }
    private String abbreviate(String value, int max) { return value == null || value.length() <= max ? value : value.substring(0, max); }
    private static ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
