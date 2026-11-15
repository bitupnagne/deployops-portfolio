package com.portfolio.deployops.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.domain.UserRole;
import com.portfolio.deployops.service.SystemSettingService;
import com.portfolio.deployops.service.UserService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@SpringJUnitConfig(LoginCompletionTimestampTest.TestApp.class)
@WebAppConfiguration
class LoginCompletionTimestampTest {
    @Autowired WebApplicationContext context;
    @Autowired UserDetailsServiceImpl details;
    @Autowired UserService users;
    @Autowired SystemSettingService settings;
    @Autowired LoginAttemptService attempts;
    private MockMvc mvc;
    private User user;
    private final LocalDateTime at = LocalDateTime.of(2026, 10, 11, 13, 0, 0);

    @BeforeEach void setup() {
        reset(details, users, settings, attempts);
        user = User.builder().id(7L).loginId("fixture").name("fixture").active(true)
            .role(UserRole.DEVELOPER).password(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("Correct1!"))
            .build();
        when(details.loadUserByUsername("fixture")).thenReturn(new SecurityUserDetails(user));
        when(users.findById(7L)).thenReturn(user);
        when(settings.get(anyString(), anyString())).thenReturn("false");
        doAnswer(invocation -> { user.setLastLoginAt(at); return null; }).when(users).recordLogin(eq(user), anyString());
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test void normalLoginRecordsAndSameSessionProfileShowsTimestamp() throws Exception {
        var result = mvc.perform(formLogin().user("fixture").password("Correct1!"))
            .andExpect(redirectedUrl("/deploy/dashboard")).andReturn();
        verify(users).recordLogin(eq(user), anyString());
        mvc.perform(get("/deploy/profile").session((MockHttpSession) result.getRequest().getSession(false)))
            .andExpect(status().isOk()).andExpect(content().string(at.toString()));
    }

    @Test void forcePasswordChangeKeepsItsRedirectAndRecordsCompletedAuthentication() throws Exception {
        user.setForcePasswordChange(true);
        mvc.perform(formLogin().user("fixture").password("Correct1!"))
            .andExpect(redirectedUrl("/deploy/profile?pwchange=1"));
        verify(users).recordLogin(eq(user), anyString());
        assertThat(user.isForcePasswordChange()).isTrue();
    }

    @Test void otpPendingDoesNotRecordPasswordOnlyAuthentication() throws Exception {
        user.setOtpEnabled(true);
        mvc.perform(formLogin().user("fixture").password("Correct1!"))
            .andExpect(redirectedUrl("/login?otp=1"));
        verify(users, never()).recordLogin(any(), anyString());
        assertThat(user.getLastLoginAt()).isNull();
    }

    @Test void wrongPasswordDoesNotRecordTimestamp() throws Exception {
        mvc.perform(formLogin().user("fixture").password("wrong"))
            .andExpect(redirectedUrl("/login?error"));
        verify(users, never()).recordLogin(any(), anyString());
    }

    @Test void inactiveAccountCannotRecordLogin() throws Exception {
        user.setActive(false);
        mvc.perform(formLogin().user("fixture").password("Correct1!"))
            .andExpect(redirectedUrl("/login?error"));
        verify(users, never()).recordLogin(any(), anyString());
    }

    @Test void lockedAccountCannotRecordLogin() throws Exception {
        when(details.loadUserByUsername("fixture")).thenReturn(new SecurityUserDetails(user, false));
        mvc.perform(formLogin().user("fixture").password("Correct1!"))
            .andExpect(redirectedUrl("/login?locked"));
        verify(users, never()).recordLogin(any(), anyString());
    }

    @Test void recordingFailureRemovesNewAuthenticationFromSession() {
        doThrow(new IllegalStateException("recording unavailable")).when(users).recordLogin(eq(user), anyString());
        MockHttpSession session = new MockHttpSession();
        assertThatThrownBy(() -> mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/login")
            .session(session).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
            .param("username", "fixture").param("password", "Correct1!")))
            .isInstanceOf(IllegalStateException.class);
        assertThat(session.getAttribute(org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).isNull();
    }

    @Configuration @EnableWebMvc @Import(SecurityConfig.class)
    static class TestApp {
        @Bean UserDetailsServiceImpl details() { return mock(UserDetailsServiceImpl.class); }
        @Bean LoginAttemptService attempts() { return mock(LoginAttemptService.class); }
        @Bean SystemSettingService settings() { return mock(SystemSettingService.class); }
        @Bean UserService users() { return mock(UserService.class); }
        @Bean ProfileFixture profileFixture() { return new ProfileFixture(); }
    }
    @RestController static class ProfileFixture {
        @GetMapping("/deploy/profile") String profile(@AuthenticationPrincipal SecurityUserDetails principal) {
            return principal.getUser().getLastLoginAt().toString();
        }
    }
}
