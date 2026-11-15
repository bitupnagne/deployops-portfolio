package com.portfolio.deployops.controller;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.config.UserDetailsServiceImpl;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.repository.UserRepository;
import com.portfolio.deployops.service.QrCodeService;
import com.portfolio.deployops.service.TotpService;
import com.portfolio.deployops.service.UserService;
import com.portfolio.deployops.service.AuditLogService;
import com.portfolio.deployops.domain.AuditResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 로그인 2차 인증(pre-auth). 비밀번호만 통과한 미인증 세션이 OTP를 통과해야 비로소 로그인 완료(재인증)된다.
 *
 * <p>이 단계의 세션은 Spring 인증이 없으므로 보호 페이지/ API 는 표준 인가가 전부 차단한다(F12·콘솔 우회 불가).
 * 모든 작업은 세션 pending uid 의 본인 계정에만 적용된다.
 */
@Controller
@RequiredArgsConstructor
public class OtpLoginController {

    private static final String ISSUER = "DeployOps";

    private final TotpService totpService;
    private final QrCodeService qrCodeService;
    private final UserRepository userRepository;
    private final UserDetailsServiceImpl userDetailsService;
    private final UserService userService;
    private final AuditLogService auditLogService;

    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    /** 로그인 화면 OTP 모달 초기 데이터. 대기 아니면 {pending:false}. 미등록이면 QR/시크릿 동반. */
    @GetMapping("/login/otp/state")
    @ResponseBody
    public Map<String, Object> state(HttpSession session) {
        Map<String, Object> m = new HashMap<>();
        Long uid = (Long) session.getAttribute(OtpController.OTP_PENDING_UID);
        if (uid == null) {
            m.put("pending", false);
            return m;
        }
        User user = userRepository.findById(uid).orElse(null);
        if (!matchesPendingUser(session, user)) {
            expirePendingLogin(session);
            m.put("pending", false);
            return m;
        }
        boolean enrolled = Boolean.TRUE.equals(session.getAttribute(OtpController.OTP_PENDING_ENROLLED));
        m.put("pending", true);
        m.put("enrolled", enrolled);
        if (!enrolled) {
            String name = (String) session.getAttribute(OtpController.OTP_PENDING_NAME);
            String secret = (String) session.getAttribute(OtpController.OTP_SETUP_SECRET);
            if (secret == null) {
                secret = totpService.generateSecret();
                session.setAttribute(OtpController.OTP_SETUP_SECRET, secret);
            }
            String otpauth = totpService.buildOtpAuthUri(secret, name, ISSUER);
            m.put("secret", secret);
            m.put("account", name);
            m.put("otpauth", otpauth);
            m.put("qrDataUri", qrCodeService.pngDataUri(otpauth, 240));
        }
        return m;
    }

    @GetMapping("/login/otp/server-time")
    @ResponseBody
    public Map<String, Object> serverTime() {
        long now = System.currentTimeMillis();
        int step = totpService.getStepSeconds();
        Map<String, Object> m = new HashMap<>();
        m.put("epochMs", now);
        m.put("step", step);
        m.put("remaining", step - ((now / 1000L) % step));
        return m;
    }

    /** OTP 코드 검증. 통과 시 실제 인증(재인증)하고 {ok:true, redirect}. */
    @PostMapping("/login/otp/verify")
    @ResponseBody
    @Transactional
    public Map<String, Object> verify(@RequestParam String code, HttpSession session,
                                      HttpServletRequest request, HttpServletResponse response) {
        Map<String, Object> m = new HashMap<>();
        Long uid = (Long) session.getAttribute(OtpController.OTP_PENDING_UID);
        String name = (String) session.getAttribute(OtpController.OTP_PENDING_NAME);
        User user = uid == null ? null : userRepository.findByIdForUpdate(uid).orElse(null);
        if (!matchesPendingUser(session, user)) {
            return expiredResponse(session);
        }
        SecurityUserDetails loaded = (SecurityUserDetails) userDetailsService.loadUserByUsername(name);
        if (!loaded.isAccountNonLocked() || !matchesPendingUser(session, loaded.getUser())) {
            return expiredResponse(session);
        }
        boolean enrolled = Boolean.TRUE.equals(session.getAttribute(OtpController.OTP_PENDING_ENROLLED));
        String trimmed = code == null ? "" : code.trim();
        boolean ok;
        if (enrolled) {
            ok = totpService.verify(user.getOtpSecret(), trimmed);
        } else {
            String secret = (String) session.getAttribute(OtpController.OTP_SETUP_SECRET);
            ok = secret != null && totpService.verify(secret, trimmed);
            if (ok) {
                if (userRepository.updateOtp(uid, secret, true) != 1) {
                    throw new IllegalStateException("2차 인증 등록 결과를 확인할 수 없습니다.");
                }
                user.setOtpSecret(secret);
                user.setOtpEnabled(true);
                auditLogService.recordAuthentication(user, null, "본인 OTP 등록", AuditResult.SUCCESS,
                    "OTP_ENABLED", request.getRemoteAddr());
            }
        }
        if (!ok) {
            auditLogService.recordAuthentication(user, null, enrolled ? "로그인" : "본인 OTP 등록", AuditResult.FAIL,
                "INVALID_OTP", request.getRemoteAddr());
            m.put("ok", false);
            m.put("message", "코드가 올바르지 않습니다. 휴대폰 시간(자동)과 6자리를 확인하세요.");
            return m;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("OTP 인증 트랜잭션을 확인할 수 없습니다.");
        }
        userService.recordLogin(user, request.getRemoteAddr());
        SecurityUserDetails principal = new SecurityUserDetails(user, loaded.isAccountNonLocked());
        // OTP 등록과 행 잠금 트랜잭션이 성공한 뒤에만 인증 세션을 만든다.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    completeLogin(principal, session, request, response, m);
                } catch (RuntimeException ex) {
                    expirePendingLogin(session, request);
                    throw ex;
                }
            }

            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    expirePendingLogin(session, request);
                }
            }
        });
        return m;
    }

    private void completeLogin(SecurityUserDetails principal, HttpSession session,
                               HttpServletRequest request, HttpServletResponse response,
                               Map<String, Object> result) {
        UsernamePasswordAuthenticationToken auth =
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);
        contextRepository.saveContext(ctx, request, response);
        session.setAttribute(OtpController.OTP_VERIFIED, Boolean.TRUE);
        OtpController.clearPendingLogin(session);
        result.put("ok", true);
        // W-05: 최초 로그인 비밀번호 강제 변경 대상이면 프로필(변경 모달)로.
        result.put("redirect", request.getContextPath() + (principal.getUser().isForcePasswordChange()
                ? "/deploy/profile?pwchange=1" : "/deploy/dashboard"));
    }

    private boolean matchesPendingUser(HttpSession session, User user) {
        Object hash = session.getAttribute(OtpController.OTP_PENDING_PASSWORD_HASH);
        return user != null && user.isActive() && hash instanceof String passwordHash
            && !passwordHash.isBlank()
            && Objects.equals(user.getId(), session.getAttribute(OtpController.OTP_PENDING_UID))
            && Objects.equals(user.getLoginId(), session.getAttribute(OtpController.OTP_PENDING_NAME))
            && Objects.equals(user.getPassword(), passwordHash)
            && user.isOtpEnabled() == Boolean.TRUE.equals(
                session.getAttribute(OtpController.OTP_PENDING_ENROLLED));
    }

    private Map<String, Object> expiredResponse(HttpSession session) {
        expirePendingLogin(session);
        return Map.of("ok", false, "expired", true,
            "message", "로그인 정보가 변경되었거나 세션이 만료되었습니다. 다시 로그인해 주세요.");
    }

    private void expirePendingLogin(HttpSession session) {
        SecurityContextHolder.clearContext();
        clearSessionAuthentication(session);
    }

    private void expirePendingLogin(HttpSession original, HttpServletRequest request) {
        expirePendingLogin(original);
        HttpSession current = request.getSession(false);
        if (current != null && current != original) {
            clearSessionAuthentication(current);
        }
    }

    private void clearSessionAuthentication(HttpSession session) {
        try {
            session.removeAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
            session.removeAttribute(OtpController.OTP_VERIFIED);
            OtpController.clearPendingLogin(session);
        } catch (IllegalStateException ignored) {
            // 동시 로그아웃으로 이미 폐기된 세션에도 인증 컨텍스트 정리는 먼저 완료한다.
        }
    }
}
