package com.portfolio.deployops.config;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import com.portfolio.deployops.service.UserService;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final UserDetailsServiceImpl userDetailsService;
    private final LoginAttemptService loginAttemptService;
    private final com.portfolio.deployops.service.SystemSettingService systemSettingService;

    @org.springframework.beans.factory.annotation.Value("${app.public-demo.enabled:false}")
    private boolean publicDemo;

    @org.springframework.beans.factory.annotation.Value("${server.servlet.session.cookie.name:DEPLOYOPSSESSION}")
    private String sessionCookieName;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, UserService userService) throws Exception {
        // Spring Security 7: 무인자 생성자/ setUserDetailsService 제거 → 생성자 주입.
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());

        CsrfTokenRequestAttributeHandler requestHandler = new CsrfTokenRequestAttributeHandler();
        requestHandler.setCsrfRequestAttributeName(null);
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        if (publicDemo) csrfRepository.setCookieName("DEPLOYOPSDEMO-XSRF-TOKEN");

        http
            .authenticationProvider(provider)
            .addFilterBefore(new PasswordSessionGuardFilter(userService), AuthorizationFilter.class)
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfRepository)
                .csrfTokenRequestHandler(requestHandler)
                .ignoringRequestMatchers("/api/internal/sso/**", "/api/v1/sso/link-codes", "/api/v1/gitlab/webhook", "/api/agent/v1/**")
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/sso/consume", "/api/internal/sso/**", "/css/**", "/js/**", "/img/**", "/fonts/**", "/otp-ui/**").permitAll()
                // 감시·L4 헬스체크는 인증 없이 때린다. DB 도달 여부만 알려 주고 내부 정보는 담지 않는다.
                .requestMatchers("/health").permitAll()
                // 에이전트는 브라우저 세션 대신 서버별 등록 자격증명을 서비스에서 확인한다.
                .requestMatchers("/api/agent/v1/**").permitAll()
                // 로그인 2차 인증(pre-auth) 단계 — 코드 통과 전까지는 미인증 상태에서 접근.
                .requestMatchers("/login/otp/**").permitAll()
                // GitLab 웹훅은 시크릿(X-Gitlab-Token)으로 서비스 단에서 검증한다.
                .requestMatchers("/api/v1/gitlab/webhook").permitAll()
                // 공유 자격증명으로 실행 대상을 바꾸는 설정과 전역 조회는 최고 관리자 전용.
                // 프로젝트별 조회·MR·배포 실행 경로는 이 매처에 포함하지 않는다.
                .requestMatchers(HttpMethod.POST,
                    "/api/v1/projects", "/api/v1/projects/*/components", "/api/v1/projects/*/gitlab/test"
                ).hasRole("SUPER_ADMIN")
                .requestMatchers(HttpMethod.PUT,
                    "/api/v1/projects/*", "/api/v1/projects/*/components/*"
                ).hasRole("SUPER_ADMIN")
                .requestMatchers(HttpMethod.DELETE,
                    "/api/v1/projects/*", "/api/v1/projects/*/components/*"
                ).hasRole("SUPER_ADMIN")
                .requestMatchers(
                    "/api/v1/integration/**", "/api/v1/gitlab/projects", "/api/v1/gitlab/projects/*/branches",
                    "/api/v1/nexus/repositories"
                ).hasRole("SUPER_ADMIN")
                .requestMatchers(
                    "/deploy/users",
                    "/deploy/teams",
                    "/deploy/policies",
                    "/deploy/integration",
                    "/deploy/exec-policy",
                    "/deploy/ai-settings",
                    "/deploy/security",
                    "/api/v1/users/**",
                    "/api/v1/projects/*/permissions",
                    "/api/v1/projects/*/permissions/**",
                    "/api/v1/teams/**",
                    "/api/v1/policies/**",
                    "/api/v1/settings/**",
                    "/api/v1/audit/**",
                    "/api/v1/office-notifications/**"
                ).hasRole("SUPER_ADMIN")
                // 승인도 운영 실행과 같다 — 역할이 아니라 <건별 승인 권한>으로 통제한다.
                // 승인권자는 두 종류다: 전역 APPROVER/SUPER_ADMIN 과, 그 프로젝트에
                // TEST_APPROVE/PROD_APPROVE 를 가진 사용자. 후자는 역할이 DEVELOPER 일 수 있으므로
                // 여기서 hasAnyRole 로 막으면 <필터 체인이 @PreAuthorize 보다 먼저 돌아>
                // ApprovalService 의 권한 검사에 닿기도 전에 403 이 된다.
                // 실제 경계는 ApprovalService.assertApprovalPermission 이며, 요청 건의
                // 프로젝트와 환경(TEST/PROD)까지 봐야 하므로 URL 만으로는 판정할 수 없다.
                .requestMatchers("/api/v1/approvals/**").authenticated()
                .requestMatchers(
                    "/deploy/nginx-policies",
                    "/deploy/nexus-operations",
                    "/api/v1/nginx-changes/**"
                ).hasAnyRole("OPERATOR", "APPROVER", "SUPER_ADMIN")
                .requestMatchers("/api/v1/nexus/cleanup-requests/**", "/api/v1/nexus/backup-requests/**", "/api/v1/nexus/restore-requests/**")
                    .hasAnyRole("OPERATOR", "APPROVER", "SUPER_ADMIN")
                // 서버 그룹/서버 관리(인프라 설정)는 OPERATOR/SUPER_ADMIN 역할 전용
                // 레지스트리 저장 용량도 같은 등급의 인프라 정보로 취급한다(프로젝트 단위 권한이 없는 전역 조회).
                .requestMatchers(
                    "/deploy/server-groups",
                    "/deploy/servers",
                    "/api/v1/server-groups/**",
                    "/api/v1/servers/**",
                    "/api/v1/nexus/storage"
                ).hasAnyRole("OPERATOR", "APPROVER", "SUPER_ADMIN")
                // 운영 실행(페이지/API)은 역할이 아니라 프로젝트 실행 권한으로 통제한다(메서드 레벨 assertExecutePermission).
                // 따라서 HTTP 레벨은 인증만 요구하고, 실제 실행 권한은 컨트롤러가 검사한다.
                .requestMatchers("/deploy/infrastructure", "/api/v1/infrastructure/**").hasAnyRole("OPERATOR", "SUPER_ADMIN")
                .requestMatchers("/api/v1/operator/**").authenticated()
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login")
                .loginProcessingUrl("/login")
                .usernameParameter("username")
                .passwordParameter("password")
                // 로그인 성공 시 실패카운트 초기화
                .successHandler((req, res, auth) -> {
                    loginAttemptService.reset(auth.getName());
                    // [보안] 비밀번호만 통과한 단계 — OTP 활성 계정은 '로그인 완료(인증)'를 보류한다.
                    //  SecurityContext 를 세션에서 제거해 미인증으로 되돌리고 로그인 화면 OTP 모달로 보낸다.
                    //  OTP 통과(/login/otp/verify) 시에만 재인증 → 보호 페이지/ API 는 표준 인가가 자동 차단.
                    boolean otpRequired = false;
                    boolean enrolled = false;
                    if (auth.getPrincipal() instanceof SecurityUserDetails u) {
                        enrolled = u.getUser().isOtpEnabled();
                        boolean reqAdmin = Boolean.parseBoolean(systemSettingService.get("otp.require.admin", "false"));
                        boolean reqUser = Boolean.parseBoolean(systemSettingService.get("otp.require.user", "false"));
                        boolean isAdmin = "SUPER_ADMIN".equals(u.getRole());
                        otpRequired = enrolled || reqUser || (reqAdmin && isAdmin);
                        if (otpRequired) {
                            jakarta.servlet.http.HttpSession s = req.getSession();
                            com.portfolio.deployops.controller.OtpController.beginPendingLogin(s, u);
                            org.springframework.security.core.context.SecurityContextHolder.clearContext();
                            s.removeAttribute(org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
                            res.sendRedirect(req.getContextPath() + "/login?otp=1");
                            return;
                        }
                        try {
                            userService.recordLogin(u.getUser(), req.getRemoteAddr());
                        } catch (RuntimeException failure) {
                            org.springframework.security.core.context.SecurityContextHolder.clearContext();
                            var session = req.getSession(false);
                            if (session != null) {
                                session.removeAttribute(org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
                            }
                            throw failure;
                        }
                        // W-05: 최초 로그인(관리자 초기화) — 비밀번호 변경 화면으로 강제 이동.
                        if (u.getUser().isForcePasswordChange()) {
                            res.sendRedirect(req.getContextPath() + "/deploy/profile?pwchange=1");
                            return;
                        }
                    }
                    res.sendRedirect(req.getContextPath() + "/deploy/dashboard");
                })
                // 실패 시 카운트 증가, 잠금 상태면 안내
                .failureHandler((req, res, ex) -> {
                    userService.recordLoginFailure(req.getParameter("username"), req.getRemoteAddr());
                    if (ex instanceof org.springframework.security.authentication.LockedException) {
                        res.sendRedirect(req.getContextPath() + "/login?locked");
                    } else {
                        loginAttemptService.recordFailure(req.getParameter("username"));
                        res.sendRedirect(req.getContextPath() + "/login?error");
                    }
                })
            )
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?logout")
                .invalidateHttpSession(true)
                .deleteCookies(sessionCookieName)
            )
            .sessionManagement(session -> session
                .maximumSessions(publicDemo ? -1 : 1)
                .maxSessionsPreventsLogin(false)
            )
            .headers(headers -> headers
                .frameOptions(fo -> fo.sameOrigin())
                .referrerPolicy(r -> r.policy(
                    org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN))
                // XSS 심층방어(CSP). 화면이 인라인 스크립트/스타일·onclick 을 다수 사용하므로
                // script/style 에 한해 'unsafe-inline' 허용(자기 출처 한정). object/base/frame-ancestors 는 차단.
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                    "default-src 'self'; "
                    + "script-src 'self' 'unsafe-inline'; "
                    + "style-src 'self' 'unsafe-inline'; "
                    + "img-src 'self' data:; "
                    + "font-src 'self'; "
                    + "connect-src 'self'; "
                    + "object-src 'none'; "
                    + "base-uri 'self'; "
                    + "frame-ancestors 'self'"))
            );

        return http.build();
    }
}
