package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.*;
import java.util.ArrayList;
import java.util.List;

/** Shared effective membership only. Execution ordering remains in RemoteDeployService. */
public final class EffectiveDeployTargets {
    private EffectiveDeployTargets() {}

    /** Only project-mapped groups of the selected environment can be chosen. */
    public static List<ServerGroup> selectGroups(Project project, DeployEnvironment environment,
                                                List<Long> selectedIds) {
        if (environment == null || project == null) throw invalid("프로젝트와 배포 환경을 선택해 주세요.");
        ServerEnvironment target = environment == DeployEnvironment.PROD ? ServerEnvironment.PROD : ServerEnvironment.TEST;
        List<ServerGroup> mapped = project.getServerGroups() == null ? List.of() : project.getServerGroups();
        if (selectedIds != null && (selectedIds.stream().anyMatch(id -> id == null || id <= 0)
            || selectedIds.stream().distinct().count() != selectedIds.size())) {
            throw invalid("선택한 서버 그룹 ID가 올바르지 않거나 중복되었습니다.");
        }
        boolean explicit = selectedIds != null && !selectedIds.isEmpty();
        List<ServerGroup> groups = mapped.stream().filter(java.util.Objects::nonNull)
            .filter(group -> group.getEnvironment() == target)
            .filter(group -> !explicit || selectedIds.contains(group.getId()))
            .sorted(java.util.Comparator.comparing(ServerGroup::getId)).toList();
        if (explicit && groups.size() != selectedIds.size()) {
            throw invalid("선택한 그룹이 이 프로젝트에 연결되어 있지 않거나 배포 환경이 다릅니다. 개발·검증은 TEST 그룹을 사용합니다.");
        }
        if (groups.isEmpty()) throw invalid("선택한 환경에 연결된 서버 그룹이 없습니다. 개발·검증은 TEST 그룹을 사용합니다.");
        return groups;
    }

    public static List<Server> resolve(DeployRequest request) {
        if (request == null) return List.of();
        List<Long> selected = request.getSelectedServerGroupIds();
        if (selected == null || selected.isEmpty()) {
            return resolve(request.getProject(), request.getEnvironment(), request.getDeployScope());
        }
        Project project = Project.builder()
            .serverGroups(selectGroups(request.getProject(), request.getEnvironment(), selected)).build();
        return resolve(project, request.getEnvironment(), request.getDeployScope());
    }

    private static org.springframework.web.server.ResponseStatusException invalid(String message) {
        return new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.BAD_REQUEST, message);
    }
    /** 배포 단위가 명시된 프로젝트에서 선택한 대상 역할의 누락을 검출한다. */
    public static List<ServerType> missingComponentRoles(Project project, List<Server> targets) {
        if (project == null || project.getComponents() == null || project.getComponents().isEmpty()) return List.of();
        return targets.stream().map(Server::getServerType).distinct()
            .filter(role -> project.getComponents().stream().noneMatch(component ->
                component != null && component.isActive() && component.getRole() == role))
            .toList();
    }

    public static String missingComponentMessage(List<ServerType> roles) {
        return "선택한 대상 서버 역할(" + String.join(", ", roles.stream().map(Enum::name).toList())
            + ")에 활성 배포 단위가 없습니다. 해당 서버 그룹 매핑을 해제하거나 배포 단위를 추가해 주세요.";
    }

    /** 산출물만 등록하고 해당 역할의 서버를 실제로 검증하지 않은 릴리스를 차단한다. */
    public static List<ServerType> missingTargetRoles(Project project, List<Server> targets, DeployScope requestedScope) {
        if (project == null || project.getComponents() == null) return List.of();
        DeployScope scope = requestedScope == null ? DeployScope.ALL : requestedScope;
        return project.getComponents().stream().filter(java.util.Objects::nonNull)
            .filter(DeployComponent::isActive).map(DeployComponent::getRole)
            .filter(java.util.Objects::nonNull).filter(scope::includes).distinct()
            .filter(role -> targets.stream().noneMatch(server -> server.getServerType() == role)).toList();
    }

    public static String missingTargetMessage(List<ServerType> roles) {
        return "선택한 배포 범위의 활성 배포 단위(" + String.join(", ", roles.stream().map(Enum::name).toList())
            + ")에 실제 대상 서버가 없습니다. 해당 역할의 서버 그룹을 선택하거나 배포 범위를 조정해 주세요.";
    }

    public static List<Server> resolve(Project project, DeployEnvironment environment, DeployScope requestedScope) {
        ServerEnvironment target = environment == DeployEnvironment.PROD ? ServerEnvironment.PROD : ServerEnvironment.TEST;
        DeployScope scope = requestedScope != null ? requestedScope : DeployScope.ALL;
        List<Server> result = new ArrayList<>();
        if (project == null || project.getServerGroups() == null) return result;
        for (ServerGroup group : project.getServerGroups()) {
            if (group == null || group.getEnvironment() != target || group.getServers() == null) continue;
            for (Server server : group.getServers()) {
                if (server != null && (server.getServerType() == ServerType.WAS || server.getServerType() == ServerType.WEB)
                        && scope.includes(server.getServerType())) result.add(server);
            }
        }
        return result;
    }
}
