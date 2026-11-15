package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.*;
import java.util.HashSet;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Reject unsupported combinations before artifacts, approvals or remote effects are created. */
final class DeploymentPlanPolicy {
    private DeploymentPlanPolicy() { }

    static void assertComponentRoles(Project project, HttpStatus status) {
        if (project == null || project.getComponents() == null) return;
        var roles = new HashSet<ServerType>();
        for (DeployComponent component : project.getComponents()) {
            if (component == null || !component.isActive()) continue;
            if (!roles.add(component.getRole()))
                throw new ResponseStatusException(status,"한 프로젝트의 같은 역할(" + component.getRole()
                    + ")에 활성 배포 단위가 여러 개 있습니다. 현재 실행기는 역할별 한 단위만 지원하므로 별도 서비스를 프로젝트로 분리하세요.");
        }
    }

    static void assertStrategy(List<ServerGroup> selected, DeployEnvironment environment,
                               DeployStrategy strategy, HttpStatus status) {
        if (strategy != DeployStrategy.MANUAL || selected == null) return;
        ServerEnvironment expected = environment == DeployEnvironment.PROD ? ServerEnvironment.PROD : ServerEnvironment.TEST;
        if (selected.stream().anyMatch(group -> group.getEnvironment() == expected
            && group.getFailurePolicy() == GroupFailurePolicy.ROLLBACK))
            throw new ResponseStatusException(status,
                "수동 전략은 그룹 전체 자동 복구(ROLLBACK)를 지원하지 않습니다. 순차·호기별 전략을 선택하거나 실패 시 중단(STOP) 정책의 그룹을 선택하세요.");
    }
}
