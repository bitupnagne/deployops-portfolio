package com.portfolio.deployops.domain;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    private Long id;
    private String loginId;
    private String password;
    private String name;
    private Team team;
    private UserRole role;

    @Builder.Default
    private boolean active = true;

    private LocalDateTime lastLoginAt;
    private LocalDateTime passwordChangedAt;

    @Builder.Default
    private boolean forcePasswordChange = false;

    private LocalDateTime createdAt;
    private String gitlabToken;
    private String gitlabUsername;

    // 2차 인증(TOTP)
    private String otpSecret;
    @Builder.Default
    private boolean otpEnabled = false;
}
