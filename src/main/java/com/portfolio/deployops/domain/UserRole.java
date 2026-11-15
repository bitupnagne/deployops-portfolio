package com.portfolio.deployops.domain;

/**
 * 사용자 역할.
 *
 * <p>한글 문구를 여기 둔다. 그동안 화면이 {@code role.name()} 을 그대로 찍어서
 * 사이드바에 <b>SUPER_ADMIN</b>, 승인자 고르는 목록에 <b>DEVELOPER</b> 가 보였다.
 * 화면마다 매핑을 따로 두면 역할이 하나 늘 때 어느 화면은 다시 영어로 돌아간다
 * ({@link DeployStatus} 가 같은 이유로 이미 이 방식이다).
 */
public enum UserRole {

    DEVELOPER("개발자"),
    APPROVER("승인자"),
    OPERATOR("운영자"),
    // 프로젝트 배포 설정·CI 값을 관리한다. 단 MR 병합/승인은 승인정책을 따른다(역할로 우회 불가).
    PROJECT_MANAGER("프로젝트 관리자"),
    SUPER_ADMIN("최고 관리자");

    private final String label;

    UserRole(String label) {
        this.label = label;
    }

    /** 사람이 읽는 역할 이름. */
    public String getLabel() {
        return label;
    }
}
