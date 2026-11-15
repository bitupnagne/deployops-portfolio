package com.portfolio.deployops.dto;

import com.portfolio.deployops.domain.UserRole;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UserForm {
    private String loginId;
    private String name;
    private UserRole role;
    private boolean active = true;
    private String password;       // 생성 시에만 사용
    private String gitlabToken;    // GitLab PAT (관리자 설정)
    private String gitlabUsername; // GitLab 사용자명 (연동 확인 후 자동 채움)
    private Long teamId;           // 소속 팀 (null = 미소속)
}
