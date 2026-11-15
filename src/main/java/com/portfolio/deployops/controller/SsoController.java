package com.portfolio.deployops.controller;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.dto.ApiResponse;
import com.portfolio.deployops.service.SsoService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class SsoController {

    private final SsoService ssoService;

    @PostMapping("/api/v1/sso/link-codes")
    public ApiResponse<?> issueLinkCode(@AuthenticationPrincipal SecurityUserDetails currentUser) {
        return ApiResponse.ok("SSO 연동 코드가 발급되었습니다.", ssoService.issueLinkCode(currentUser.getId()));
    }

    @PostMapping("/api/internal/sso/link-codes/consume")
    public ApiResponse<?> consumeLinkCode(@RequestHeader(value = "X-SSO-SECRET", required = false) String secret,
                                          @RequestBody Map<String, Object> body) {
        return ApiResponse.ok("SSO 연동이 완료되었습니다.", ssoService.consumeLinkCode(
            secret, stringValue(body.get("code")), longValue(body.get("officeUserId")), stringValue(body.get("officeUsername"))
        ));
    }

    /** 오피스가 연동을 해제했을 때 호출한다. 멱등 — 이미 끊겨 있어도 성공이다. */
    @PostMapping("/api/internal/sso/links/revoke")
    public ApiResponse<?> revokeLink(@RequestHeader(value = "X-SSO-SECRET", required = false) String secret,
                                     @RequestBody Map<String, Object> body) {
        return ApiResponse.ok("SSO 연동이 해제되었습니다.",
            ssoService.revokeLink(secret, longValue(body.get("officeUserId"))));
    }

    @PostMapping("/api/internal/sso/tickets")
    public ApiResponse<?> issueTicket(@RequestHeader(value = "X-SSO-SECRET", required = false) String secret,
                                      @RequestBody Map<String, Object> body) {
        return ApiResponse.ok("SSO 티켓이 발급되었습니다.", ssoService.issueTicket(
            secret, longValue(body.get("officeUserId")), stringValue(body.get("officeUsername")), stringValue(body.get("redirect"))
        ));
    }

    @GetMapping("/sso/consume")
    public void consumeTicket(String ticket, String redirect, HttpServletRequest request, HttpServletResponse response) throws IOException {
        SecurityUserDetails user;
        try {
            user = ssoService.consumeTicket(ticket);
        } catch (IllegalArgumentException rejected) {
            response.sendRedirect("/login?ssoError=1");
            return;
        }
        UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken(user, user.getPassword(), user.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        jakarta.servlet.http.HttpSession session = request.getSession(true);
        session.setAttribute(
            HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
            SecurityContextHolder.getContext()
        );
        // 오피스에서 SSO로 진입한 사용자는 이미 OTP를 통과한 상태 — 배포시스템에서 OTP를 다시 묻지 않는다.
        // (SSO는 formLogin successHandler를 경유하지 않으므로 OTP 게이트가 트리거되지 않지만, 의도를 명시해 둔다.)
        session.setAttribute(OtpController.OTP_VERIFIED, Boolean.TRUE);
        response.sendRedirect(ssoService.normalizeRedirect(redirect));
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long longValue(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        return Long.parseLong(String.valueOf(value));
    }
}
