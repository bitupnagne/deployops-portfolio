package com.portfolio.deployops.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * W-05: 최초 로그인(또는 관리자 초기화)으로 force_password_change=1 인 사용자는
 * 비밀번호를 변경하기 전까지 페이지 이동을 프로필(변경 모달)로 강제한다.
 * API 와 이전 세션의 보안 경계는 PasswordSessionGuardFilter 에서 처리한다.
 */
@Configuration
public class ForcePasswordChangeConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                    throws Exception {
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth == null || !(auth.getPrincipal() instanceof SecurityUserDetails u)) {
                    return true;
                }
                if (u.getUser() == null || !u.getUser().isForcePasswordChange()) {
                    return true;
                }
                response.sendRedirect(request.getContextPath() + "/deploy/profile?pwchange=1");
                return false;
            }
        }).addPathPatterns("/deploy/**").excludePathPatterns("/deploy/profile");
    }
}
