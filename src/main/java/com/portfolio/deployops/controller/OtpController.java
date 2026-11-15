package com.portfolio.deployops.controller;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.repository.UserRepository;
import com.portfolio.deployops.service.QrCodeService;
import com.portfolio.deployops.service.TotpService;
import com.portfolio.deployops.service.AuditLogService;
import com.portfolio.deployops.domain.AuditResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/** 관리자 2차 인증(TOTP) — 등록/검증/해제. */
@Controller
@RequiredArgsConstructor
public class OtpController {

    public static final String OTP_VERIFIED = "OTP_VERIFIED";
    public static final String OTP_SETUP_SECRET = "OTP_SETUP_SECRET";
    // 로그인 2차 인증(pre-auth) — 비밀번호만 통과한 미인증 세션이 보관하는 대기 정보.
    public static final String OTP_PENDING_UID = "OTP_PENDING_UID";
    public static final String OTP_PENDING_NAME = "OTP_PENDING_NAME";
    public static final String OTP_PENDING_ENROLLED = "OTP_PENDING_ENROLLED";
    public static final String OTP_PENDING_PASSWORD_HASH = "OTP_PENDING_PASSWORD_HASH";
    private static final String SETUP_SECRET = OTP_SETUP_SECRET;
    private static final String ISSUER = "DeployOps";

    private final TotpService totpService;
    private final QrCodeService qrCodeService;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    /** 비밀번호를 통과한 시점의 자격증명에 OTP 대기를 결속한다. 브라우저에 전달하지 않는다. */
    public static void beginPendingLogin(HttpSession session, SecurityUserDetails user) {
        clearPendingLogin(session);
        session.removeAttribute(OTP_VERIFIED);
        session.setAttribute(OTP_PENDING_UID, user.getId());
        session.setAttribute(OTP_PENDING_NAME, user.getUsername());
        session.setAttribute(OTP_PENDING_ENROLLED, user.getUser().isOtpEnabled());
        session.setAttribute(OTP_PENDING_PASSWORD_HASH, user.getPassword());
    }

    public static void clearPendingLogin(HttpSession session) {
        session.removeAttribute(OTP_PENDING_UID);
        session.removeAttribute(OTP_PENDING_NAME);
        session.removeAttribute(OTP_PENDING_ENROLLED);
        session.removeAttribute(OTP_PENDING_PASSWORD_HASH);
        session.removeAttribute(OTP_SETUP_SECRET);
    }

    /** 로그인 후 OTP 코드 입력 페이지. */
    @GetMapping("/otp")
    public String otpPage(@AuthenticationPrincipal SecurityUserDetails user) {
        User current = currentUser(user);
        if (current == null) {
            return "redirect:/login";
        }
        if (!current.isOtpEnabled()) {
            return "redirect:/deploy/dashboard";
        }
        return "otp";
    }

    @PostMapping("/otp/verify")
    public String verify(@AuthenticationPrincipal SecurityUserDetails user,
                         @RequestParam String code, HttpSession session) {
        User current = currentUser(user);
        if (current == null) {
            return "redirect:/login";
        }
        if (current.isOtpEnabled() && totpService.verify(current.getOtpSecret(), code)) {
            session.setAttribute(OTP_VERIFIED, Boolean.TRUE);
            return "redirect:/deploy/dashboard";
        }
        return "redirect:/otp?error";
    }

    /** 2차 인증 등록 화면 (시크릿/otpauth/수동키 표시). */
    @GetMapping("/otp/setup")
    public String setup(@AuthenticationPrincipal SecurityUserDetails user, HttpSession session, Model model) {
        User current = currentUser(user);
        if (current == null) {
            return "redirect:/login";
        }
        if (current.isOtpEnabled()) {
            model.addAttribute("already", true);
            return "otp-setup";
        }
        String secret = (String) session.getAttribute(SETUP_SECRET);
        if (secret == null) {
            secret = totpService.generateSecret();
            session.setAttribute(SETUP_SECRET, secret);
        }
        String otpauth = totpService.buildOtpAuthUri(secret, user.getUsername(), ISSUER);
        model.addAttribute("secret", secret);
        model.addAttribute("otpauth", otpauth);
        model.addAttribute("qrDataUri", qrCodeService.pngDataUri(otpauth, 240));
        return "otp-setup";
    }

    @PostMapping("/otp/setup")
    @Transactional
    public String confirmSetup(@AuthenticationPrincipal SecurityUserDetails user,
                               @RequestParam String code, HttpSession session) {
        User current = currentUser(user);
        if (current == null) {
            return "redirect:/login";
        }
        if (enableCurrentOtp(current, code, session)) {
            return "redirect:/deploy/dashboard?otp=on";
        }
        return "redirect:/otp/setup?error";
    }

    // ───────────────────────────────────────────────────────────
    // 모달/실시간 시계용 JSON 엔드포인트 (페이지 이동 없이 동작)
    // ───────────────────────────────────────────────────────────

    /** 서버 현재시각(epoch ms)·TOTP 스텝·현재 창 잔여초. 클라이언트가 기기-서버 시차를 계산·표시. */
    @GetMapping("/otp/server-time")
    @ResponseBody
    public Map<String, Object> serverTime() {
        long now = System.currentTimeMillis();
        int step = totpService.getStepSeconds();
        long remaining = step - ((now / 1000L) % step);
        Map<String, Object> m = new HashMap<>();
        m.put("epochMs", now);
        m.put("step", step);
        m.put("remaining", remaining);
        return m;
    }

    /** 등록 모달용 데이터(시크릿/otpauth/QR). 이미 등록 시 already=true. */
    @GetMapping("/otp/setup/data")
    @ResponseBody
    public Map<String, Object> setupData(@AuthenticationPrincipal SecurityUserDetails user, HttpSession session) {
        Map<String, Object> m = new HashMap<>();
        User current = currentUser(user);
        if (current == null) {
            m.put("error", "unauthorized");
            return m;
        }
        if (current.isOtpEnabled()) {
            m.put("already", true);
            return m;
        }
        String secret = (String) session.getAttribute(SETUP_SECRET);
        if (secret == null) {
            secret = totpService.generateSecret();
            session.setAttribute(SETUP_SECRET, secret);
        }
        String otpauth = totpService.buildOtpAuthUri(secret, user.getUsername(), ISSUER);
        m.put("already", false);
        m.put("secret", secret);
        m.put("account", user.getUsername());
        m.put("otpauth", otpauth);
        m.put("qrDataUri", qrCodeService.pngDataUri(otpauth, 240));
        return m;
    }

    /** 등록 확정(모달). 성공 시 {ok:true}. */
    @PostMapping("/otp/confirm")
    @ResponseBody
    @Transactional
    public Map<String, Object> confirm(@AuthenticationPrincipal SecurityUserDetails user,
                                       @RequestParam String code, HttpSession session) {
        Map<String, Object> m = new HashMap<>();
        User current = currentUser(user);
        if (current == null) {
            m.put("ok", false);
            m.put("expired", true);
            m.put("message", "세션이 만료되었습니다. 다시 로그인하세요.");
            return m;
        }
        if (enableCurrentOtp(current, code, session)) {
            m.put("ok", true);
            m.put("message", "2차 인증이 등록되었습니다.");
        } else {
            m.put("ok", false);
            m.put("message", "코드가 올바르지 않습니다. 휴대폰 시간(자동)과 6자리를 확인하세요.");
        }
        return m;
    }

    /** 로그인 후 검증(모달/오버레이). 성공 시 {ok:true}. */
    @PostMapping("/otp/verify-ajax")
    @ResponseBody
    public Map<String, Object> verifyAjax(@AuthenticationPrincipal SecurityUserDetails user,
                                          @RequestParam String code, HttpSession session) {
        Map<String, Object> m = new HashMap<>();
        User current = currentUser(user);
        if (current == null) {
            m.put("ok", false);
            m.put("expired", true);
            m.put("message", "세션이 만료되었습니다.");
            return m;
        }
        if (current.isOtpEnabled() && totpService.verify(current.getOtpSecret(), code)) {
            session.setAttribute(OTP_VERIFIED, Boolean.TRUE);
            m.put("ok", true);
        } else {
            m.put("ok", false);
            m.put("message", "코드가 올바르지 않습니다. 휴대폰 시간(자동)과 6자리를 확인하세요.");
        }
        return m;
    }

    /** 본인 2차 인증 해제(모달). */
    @PostMapping("/otp/off")
    @ResponseBody
    @Transactional
    public Map<String, Object> off(@AuthenticationPrincipal SecurityUserDetails user, HttpSession session) {
        Map<String, Object> m = new HashMap<>();
        User current = currentUser(user);
        if (current == null) {
            m.put("ok", false);
            return m;
        }
        disableCurrentOtp(current, session);
        m.put("ok", true);
        m.put("message", "2차 인증이 해제되었습니다.");
        return m;
    }

    /** 본인 2차 인증 해제. */
    @PostMapping("/otp/disable")
    @Transactional
    public String disable(@AuthenticationPrincipal SecurityUserDetails user, HttpSession session) {
        User current = currentUser(user);
        if (current == null) {
            return "redirect:/login";
        }
        disableCurrentOtp(current, session);
        return "redirect:/deploy/dashboard?otp=off";
    }

    /** 인증된 본인 계정의 최신 상태만 사용한다. 로그인 principal의 OTP 필드는 오래될 수 있다. */
    private User currentUser(SecurityUserDetails authenticated) {
        if (authenticated == null || authenticated.getId() == null) {
            return null;
        }
        return userRepository.findById(authenticated.getId())
                .filter(current -> current.isActive()
                        && Objects.equals(current.getId(), authenticated.getId())
                        && Objects.equals(current.getLoginId(), authenticated.getUsername()))
                .orElse(null);
    }

    private boolean enableCurrentOtp(User current, String code, HttpSession session) {
        if (current.isOtpEnabled()) {
            auditOtp(current, "본인 OTP 등록", AuditResult.FAIL, "ALREADY_ENABLED");
            session.removeAttribute(SETUP_SECRET);
            return false;
        }
        String secret = (String) session.getAttribute(SETUP_SECRET);
        if (secret == null || !totpService.verify(secret, code)) {
            auditOtp(current, "본인 OTP 등록", AuditResult.FAIL, "INVALID_OTP");
            return false;
        }
        requireTransaction();
        // 다른 세션이 먼저 등록했거나 계정이 비활성화됐다면 기존 키를 덮어쓰지 않는다.
        if (userRepository.enableOtpIfDisabled(current.getId(), secret) != 1) {
            auditOtp(current, "본인 OTP 등록", AuditResult.FAIL, "ENROLLMENT_STATE_CHANGED");
            session.removeAttribute(SETUP_SECRET);
            return false;
        }
        auditOtp(current, "본인 OTP 등록", AuditResult.SUCCESS, "OTP_ENABLED");
        afterCommit(() -> {
            session.removeAttribute(SETUP_SECRET);
            session.setAttribute(OTP_VERIFIED, Boolean.TRUE);
        });
        return true;
    }

    private void disableCurrentOtp(User current, HttpSession session) {
        requireTransaction();
        if (userRepository.updateOtp(current.getId(), null, false) != 1) {
            throw new IllegalStateException("2차 인증 해제 결과를 확인할 수 없습니다.");
        }
        auditOtp(current, "본인 OTP 해제", AuditResult.SUCCESS, "OTP_DISABLED");
        afterCommit(() -> {
            session.removeAttribute(OTP_VERIFIED);
            session.removeAttribute(SETUP_SECRET);
        });
    }

    private void auditOtp(User current, String action, AuditResult result, String category) {
        auditLogService.recordAuthentication(current, null, action, result, category, null);
    }

    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("OTP 변경 트랜잭션을 확인할 수 없습니다.");
        }
    }

    private void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { action.run(); }
        });
    }
}
