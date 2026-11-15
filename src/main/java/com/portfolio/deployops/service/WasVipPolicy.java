package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.ServerGroup;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.DeployComponent;
import com.portfolio.deployops.domain.ServerType;
import com.portfolio.deployops.domain.ArtifactType;
import com.portfolio.deployops.domain.HealthCheckMode;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** External L4 metadata only; no L4 membership or drain operation is implied. */
public final class WasVipPolicy {
    private WasVipPolicy() { }

    public static boolean enabled(ServerGroup group) {
        return group != null && Boolean.TRUE.equals(group.getWasVipEnabled());
    }

    /** A shared VM group VIP is not ingress for a portless background container. */
    public static boolean portlessWorker(Project project) {
        return portlessWorker(project, project == null ? List.of() : project.getComponents());
    }

    public static boolean portlessWorker(Project project, List<DeployComponent> components) {
        if (project == null || project.getWasVipPort() != null
            || (project.getContainerPorts() != null && !project.getContainerPorts().isBlank())) return false;
        // Host networking publishes directly; named networks may be macvlan/ipvlan.
        // Only known isolated Docker modes establish this narrow no-ingress exception.
        String network = project.getDockerNetwork();
        if (network != null && !network.isBlank() && !"bridge".equals(network) && !"none".equals(network)) return false;
        var active = components == null ? List.<DeployComponent>of() : components.stream().filter(DeployComponent::isActive).toList();
        if (active.isEmpty()) return project.getHealthCheckMode() == HealthCheckMode.CONTAINER_RUNNING;
        return active.stream().allMatch(component -> component.getRole() == ServerType.WAS
            && component.getArtifactType() == ArtifactType.CONTAINER
            && (component.getHealthCheckMode() == null ? project.getHealthCheckMode() : component.getHealthCheckMode()) == HealthCheckMode.CONTAINER_RUNNING);
    }

    public static boolean applies(Project project, ServerGroup group) {
        return enabled(group) && !portlessWorker(project);
    }

    public static String normalizeAddress(String address) {
        String key = ServerHostIdentity.canonical(address);
        if (key == null || "loopback".equals(key))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "WAS VIP에는 루프백이 아닌 IPv4·IPv6 주소를 입력하세요. DNS 이름과 인터페이스 식별자는 사용할 수 없습니다.");
        try {
            var ip = java.net.InetAddress.getByAddress(java.util.HexFormat.of().parseHex(key));
            if (ip.isAnyLocalAddress() || ip.isMulticastAddress() || ip.isLinkLocalAddress())
                throw new IllegalArgumentException();
            return ip.getHostAddress();
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "라우팅 가능한 WAS VIP 주소를 입력하세요.");
        }
    }

    public static boolean sameHost(String first, String second) {
        String key = ServerHostIdentity.canonical(first);
        return key != null ? key.equals(ServerHostIdentity.canonical(second)) : java.util.Objects.equals(first, second);
    }
}
