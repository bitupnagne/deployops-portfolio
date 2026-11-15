package com.portfolio.deployops.service;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.repository.SsoRepository;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class SsoService {

    private final SsoRepository ssoRepository;
    private final SystemSettingService settingService;
    private final UserService userService;
    private final SecureRandom random = new SecureRandom();

    @Value("${app.sso.shared-secret:${SSO_SHARED_SECRET:}}")
    private String sharedSecret;

    @Value("${app.sso.base-url:http://localhost:9090}")
    private String baseUrl;

    @Transactional
    public Map<String, Object> issueLinkCode(Long deployUserId) {
        String code = Integer.toString(100000 + random.nextInt(900000));
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(10);
        ssoRepository.insertLinkCode(code, deployUserId, expiresAt);
        return Map.of("code", code, "expiresAt", expiresAt.toString());
    }

    @Transactional
    public Map<String, Object> consumeLinkCode(String secret, String code, Long officeUserId, String officeUsername) {
        requireSecret(secret);
        if (!StringUtils.hasText(code) || officeUserId == null || !StringUtils.hasText(officeUsername)) {
            throw new IllegalArgumentException("연동 정보가 올바르지 않습니다.");
        }
        User user = ssoRepository.findUserByValidLinkCode(code.trim())
            .orElseThrow(() -> new IllegalArgumentException("연동 코드가 없거나 만료되었습니다."));
        ssoRepository.markLinkCodeUsed(code.trim());
        ssoRepository.upsertOfficeLink(officeUserId, officeUsername.trim(), user.getId());
        return userPayload(user);
    }

    /**
     * 오피스가 연동을 해제했음을 받아 이쪽 링크도 끊는다.
     *
     * <p>예전에는 오피스만 끊겨서, 해제한 사용자에게 <b>배포 알림이 계속 갔다</b> —
     * 알림 수신자는 이 링크로 결정되기 때문이다.
     *
     * <p>이미 끊겨 있어도 성공으로 답한다. 오피스가 재시도할 수 있어야 양쪽이 결국 맞는다.
     */
    @Transactional
    public Map<String, Object> revokeLink(String secret, Long officeUserId) {
        requireSecret(secret);
        if (officeUserId == null) {
            throw new IllegalArgumentException("연동 해제 정보가 올바르지 않습니다.");
        }
        int revoked = ssoRepository.deactivateOfficeLink(officeUserId);
        return Map.of("revoked", revoked > 0);
    }

    @Transactional
    public Map<String, Object> issueTicket(String secret, Long officeUserId, String officeUsername, String redirect) {
        requireSecret(secret);
        User user = ssoRepository.findLinkedUser(officeUserId, officeUsername)
            .orElseThrow(() -> new IllegalArgumentException("SSO 연동 정보가 없습니다."));
        String ticket = randomTicket();
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(2);
        ssoRepository.insertTicket(ticket, officeUserId, user.getId(), expiresAt);
        return Map.of(
            "ticket", ticket,
            "loginUrl", resolveDeployBaseUrl() + "/sso/consume?ticket=" + ticket
                + "&redirect=" + URLEncoder.encode(normalizeRedirect(redirect), StandardCharsets.UTF_8),
            "expiresAt", expiresAt.toString(),
            "user", userPayload(user)
        );
    }

    @Transactional
    public SecurityUserDetails consumeTicket(String ticket) {
        if (!StringUtils.hasText(ticket)) {
            throw new IllegalArgumentException("SSO 티켓이 없습니다.");
        }
        User user = ssoRepository.findUserByValidTicket(ticket.trim())
            .orElseThrow(() -> new IllegalArgumentException("SSO 티켓이 없거나 만료되었습니다."));
        if (ssoRepository.markTicketUsed(ticket.trim()) != 1) {
            throw new IllegalArgumentException("SSO 티켓이 이미 사용되었거나 만료되었습니다.");
        }
        userService.recordLogin(user);
        return new SecurityUserDetails(user);
    }

    private void requireSecret(String secret) {
        String expected = settingService.get("sso.shared-secret", sharedSecret);
        if (!StringUtils.hasText(expected)) {
            expected = sharedSecret;
        }
        // 상수시간 비교(타이밍 사이드채널 방지).
        if (!StringUtils.hasText(expected) || secret == null
                || !java.security.MessageDigest.isEqual(
                        expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        secret.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("SSO 호출 권한이 없습니다.");
        }
    }

    private String resolveDeployBaseUrl() {
        String configured = settingService.get("deploy.public-url", baseUrl);
        if (!StringUtils.hasText(configured)) {
            configured = baseUrl;
        }
        return configured.replaceAll("/+$", "");
    }

    public String normalizeRedirect(String redirect) {
        if (!StringUtils.hasText(redirect) || !redirect.startsWith("/") || redirect.startsWith("//")) {
            return "/deploy/dashboard";
        }
        return redirect.length() > 255 ? "/deploy/dashboard" : redirect;
    }

    private String randomTicket() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private Map<String, Object> userPayload(User user) {
        return Map.of("id", user.getId(), "loginId", user.getLoginId(), "name", user.getName(), "role", user.getRole().name());
    }
}
