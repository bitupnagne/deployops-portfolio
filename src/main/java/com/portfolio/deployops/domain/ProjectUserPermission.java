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
public class ProjectUserPermission {

    private Long id;
    private Project project;
    private User user;
    private ProjectPermissionType permission;

    @Builder.Default
    private ProjectPermissionEnvironment environment = ProjectPermissionEnvironment.ALL;

    @Builder.Default
    private boolean active = true;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
