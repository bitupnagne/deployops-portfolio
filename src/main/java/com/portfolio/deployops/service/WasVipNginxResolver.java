package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.dto.NginxPolicyForm;
import com.portfolio.deployops.repository.ProjectRepository;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Resolves trusted project/group metadata; the browser never supplies a VIP address or port. */
@Service
@RequiredArgsConstructor
public class WasVipNginxResolver {
    private final ProjectRepository projects;

    public record Choice(Long projectId, String projectName, Long groupId, String groupName,
                         String address, Integer port) { }

    public List<Choice> choices(Server web) {
        if (web == null || web.getServerGroup() == null || web.getServerType() != ServerType.WEB) return List.of();
        List<Choice> result = new ArrayList<>();
        for (Project project : projects.findAll()) {
            if (!project.isActive() || project.getWasVipPort() == null) continue;
            var groups = projects.findServerGroupsByProjectId(project.getId());
            if (groups.stream().noneMatch(g -> Objects.equals(g.getId(), web.getServerGroup().getId())
                    && g.getEnvironment() == web.getEnvironment())) continue;
            groups.stream().filter(WasVipPolicy::enabled).filter(g -> g.getEnvironment() == web.getEnvironment())
                .forEach(g -> result.add(new Choice(project.getId(), project.getName(), g.getId(), g.getName(),
                    WasVipPolicy.normalizeAddress(g.getWasVipAddress()), project.getWasVipPort())));
        }
        return List.copyOf(result);
    }

    public void resolve(NginxPolicyForm form, Server web) {
        if (form.getWasVipProjectId() == null || form.getWasVipGroupId() == null)
            throw bad("VIP 프로젝트와 서버그룹을 함께 선택하세요.");
        if (!"REVERSE_PROXY".equals(form.getPolicyType()) && !form.isApiProxyEnabled())
            throw bad("WAS VIP는 리버스 프록시 또는 정적 사이트의 API 프록시에 사용하세요.");
        if (form.isUpstreamListPresent() || (form.getUpstreams() != null && !form.getUpstreams().isEmpty())
            || form.isSecondaryUpstreamEnabled() || form.getSecondaryUpstreamHost() != null
            || form.getUpstreamHost() != null || form.getApiUpstreamHost() != null
            || form.isLegacyUpstreamPortPresent() || form.isLegacyApiUpstreamPortPresent())
            throw bad("WAS VIP와 개별 WAS 목록·직접 주소를 함께 지정할 수 없습니다.");
        Choice choice = choices(web).stream().filter(c -> c.projectId().equals(form.getWasVipProjectId())
            && c.groupId().equals(form.getWasVipGroupId())).findFirst()
            .orElseThrow(() -> bad("선택한 WEB과 같은 환경이며 프로젝트에 연결된 VIP 그룹·서비스 포트를 확인하세요."));
        if (choice.port() < 1 || choice.port() > 65535) throw bad("프로젝트의 WAS VIP 서비스 포트가 올바르지 않습니다.");
        if ("REVERSE_PROXY".equals(form.getPolicyType())) {
            form.setUpstreamHost(choice.address()); form.setUpstreamPort(choice.port());
        } else {
            form.setApiUpstreamHost(choice.address()); form.setApiUpstreamPort(choice.port());
        }
    }

    private ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
