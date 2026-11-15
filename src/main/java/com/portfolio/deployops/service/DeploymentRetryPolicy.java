package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.DeployEnvironment;
import com.portfolio.deployops.domain.DeployRequest;
import com.portfolio.deployops.domain.DeployStatus;
import com.portfolio.deployops.domain.DeployStrategy;
import com.portfolio.deployops.domain.ArtifactType;
import com.portfolio.deployops.domain.DeployComponent;
import com.portfolio.deployops.domain.DeployScope;
import com.portfolio.deployops.domain.ServerType;
import com.portfolio.deployops.domain.ServerEnvironment;
import java.util.List;

/** 실패한 SAFE 요청의 고정된 이미지로만 남은 대상을 다시 실행한다. */
public final class DeploymentRetryPolicy {
    private DeploymentRetryPolicy() {}

    public static boolean eligible(DeployRequest request) {
        if (request == null || request.getEnvironment() == null
            || (request.getDeployStrategy() != null && request.getDeployStrategy() != DeployStrategy.SAFE)) {
            return false;
        }
        DeployStatus failed = request.getEnvironment() == DeployEnvironment.TEST
            ? DeployStatus.TEST_FAILED : DeployStatus.PROD_FAILED;
        if (request.getStatus() != failed || request.getProject() == null
            || request.getTargetAttemptTrackingStartedAt() == null) return false;
        var project = request.getProject();
        var scope = request.getDeployScope() == null ? DeployScope.ALL : request.getDeployScope();
        var environment = request.getEnvironment() == DeployEnvironment.TEST ? ServerEnvironment.TEST : ServerEnvironment.PROD;
        List<ServerType> roles = project.getServerGroups() == null ? List.of(ServerType.WAS, ServerType.WEB)
            : project.getServerGroups().stream().filter(group -> group.getEnvironment() == environment)
                .flatMap(group -> group.getServers().stream()).map(server -> server.getServerType()).distinct().toList();
        roles = roles.stream().filter(role -> (role == ServerType.WAS || role == ServerType.WEB) && scope.includes(role)).toList();
        if (roles.isEmpty()) return false;
        for (ServerType role : roles) {
            List<DeployComponent> matching = project.getComponents() == null ? List.of() : project.getComponents().stream()
                .filter(DeployComponent::isActive).filter(component -> component.getRole() == role).toList();
            if (matching.size() > 1 && project.getManagedRuntimeSettings() != null && !project.getManagedRuntimeSettings().isEmpty()) return false;
            DeployComponent component = matching.isEmpty() ? null : matching.getFirst();
            if (component != null && component.getArtifactType() == ArtifactType.PROXY_RELOAD) continue;
            if (component != null && request.getItems() != null) {
                var item = request.getItems().stream().filter(value ->
                    (component.getId() != null && component.getId().equals(value.getComponentId()))
                    || (value.getComponentCode() != null && value.getComponentCode().equals(component.getCode())))
                    .findFirst().orElse(null);
                if (item != null) {
                    String repository = component.getImageRepository() != null && !component.getImageRepository().isBlank()
                        ? component.getImageRepository() : project.getImageRepository();
                    if (item.getImageDigest() != null && !item.getImageDigest().isBlank()) {
                        if (!digest(item.getImageDigest()) || repository == null || repository.isBlank()) return false;
                        continue;
                    }
                    if ((item.getImageTag() != null && !item.getImageTag().isBlank())
                        || (item.getBundleRef() != null && !item.getBundleRef().isBlank())) return false;
                }
            }
            if (!digest(request.getImageDigest()) || project.getImageRepository() == null || project.getImageRepository().isBlank()) return false;
        }
        return true;
    }

    private static boolean digest(String value) {
        return value != null && value.matches("^sha256:[0-9a-fA-F]{64}$");
    }
}
