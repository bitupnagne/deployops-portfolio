package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.Server;
import com.portfolio.deployops.domain.ServerGroup;
import com.portfolio.deployops.repository.ProjectRepository;
import com.portfolio.deployops.repository.ApprovalPolicyRepository;
import com.portfolio.deployops.domain.ApprovalPolicy;
import java.util.Map;
import java.util.Objects;
import com.portfolio.deployops.repository.ServerGroupRepository;
import com.portfolio.deployops.repository.ServerRepository;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ServerGroupService {

    private final ServerGroupRepository serverGroupRepository;
    private final ServerRepository serverRepository;
    private final ProjectRepository projectRepository;
    private ApprovalPolicyRepository approvalPolicyRepository;

    @Autowired
    public void setApprovalPolicyRepository(ApprovalPolicyRepository repository) {
        this.approvalPolicyRepository = repository;
    }

    @Transactional(readOnly = true)
    public List<ApprovalPolicy> approvalPolicyChoices() {
        assertApprovalAdministrator();
        return approvalPolicyRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Map<Long, String> approvalPolicyNames(List<ServerGroup> groups) {
        var ids = groups.stream().map(ServerGroup::getApprovalPolicyId).filter(Objects::nonNull)
            .collect(Collectors.toSet());
        if (ids.isEmpty()) return Map.of();
        return approvalPolicyRepository.findAll().stream().filter(policy -> ids.contains(policy.getId()))
            .collect(Collectors.toMap(ApprovalPolicy::getId, ApprovalPolicy::getName));
    }

    @Transactional(readOnly = true)
    public List<ServerGroup> findAll() {
        List<ServerGroup> groups = serverGroupRepository.findAll();
        groups.forEach(this::attachServers);
        return groups;
    }

    @Transactional(readOnly = true)
    public ServerGroup findById(Long id) {
        ServerGroup group = serverGroupRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("서버 그룹을 찾을 수 없습니다."));
        attachServers(group);
        return group;
    }

    @Transactional
    public ServerGroup create(com.portfolio.deployops.dto.ServerGroupForm form) {
        validatePolicy(form);
        validateApprovalPolicyChange(null, form.getApprovalPolicyId());
        return serverGroupRepository.save(ServerGroup.builder()
            .name(form.getName())
            .environment(form.getEnvironment())
            .rollingIntervalSec(form.getRollingIntervalSec())
            .minAvailable(form.getMinAvailable())
            .maxUnavailable(form.getMaxUnavailable())
            .observationSeconds(form.getObservationSeconds())
            .failurePolicy(form.getFailurePolicy())
            .approvalPolicyId(form.getApprovalPolicyId())
            .wasVipEnabled(Boolean.TRUE.equals(form.getWasVipEnabled()) ? true : null)
            .wasVipAddress(Boolean.TRUE.equals(form.getWasVipEnabled()) ? WasVipPolicy.normalizeAddress(form.getWasVipAddress()) : null)
            .description(form.getDescription())
            .build());
    }

    /**
     * 서버 그룹을 <b>물리 삭제</b>한다 — 참조가 남아 있으면 삭제하지 않고 이유를 돌려준다.
     *
     * <p>막는 참조가 둘이다.
     * <ul>
     *   <li>{@code server.server_group_id} — 이 그룹에 속한 서버</li>
     *   <li>{@code project_server_groups.server_group_id} — 이 그룹을 배포 대상으로 쓰는 프로젝트</li>
     * </ul>
     *
     * <p>둘 다 FK 라 그냥 지우면 {@code DataIntegrityViolationException} → <b>500</b> 이 된다.
     * 프로젝트 쪽이 특히 조용한 사고다 — 그룹이 사라지면 그 프로젝트는 배포 대상을 잃는데,
     * 화면은 삭제가 성공한 것처럼 보이고 다음 배포에서야 드러난다.
     * 그래서 이름을 함께 돌려준다. 무엇을 먼저 손봐야 하는지 알아야 다음 행동을 할 수 있다.
     */
    @Transactional
    public void delete(Long id) {
        ServerGroup sg = findById(id);
        if (sg.getApprovalPolicyId() != null) assertApprovalAdministrator();

        List<String> projects = projectRepository.findProjectNamesByServerGroupId(id);
        if (!projects.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "'" + sg.getName() + "' 그룹을 배포 대상으로 쓰는 프로젝트가 있어 삭제할 수 없습니다: "
                + String.join(", ", projects) + ". 해당 프로젝트에서 먼저 대상을 바꿔 주세요.");
        }

        List<Server> members = serverRepository.findByServerGroupId(id);
        if (!members.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "'" + sg.getName() + "' 그룹에 서버 " + members.size() + "대가 속해 있어 삭제할 수 없습니다: "
                + members.stream().map(Server::getName).collect(Collectors.joining(", "))
                + ". 서버를 다른 그룹으로 옮기거나 먼저 삭제해 주세요.");
        }

        serverGroupRepository.delete(sg);
    }

    @Transactional
    public ServerGroup update(Long id, com.portfolio.deployops.dto.ServerGroupForm form) {
        validatePolicy(form);
        ServerGroup sg = serverGroupRepository.findByIdForUpdate(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "서버 그룹을 찾을 수 없습니다."));
        validateApprovalPolicyChange(sg.getApprovalPolicyId(), form.getApprovalPolicyId());
        if (sg.getEnvironment() != form.getEnvironment()) {
            if (!serverRepository.findByServerGroupId(id).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "서버가 속한 그룹의 환경은 변경할 수 없습니다. 서버를 같은 환경의 다른 그룹으로 먼저 옮겨 주세요.");
            }
            if (!projectRepository.findProjectNamesByServerGroupId(id).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "프로젝트의 배포 대상인 그룹의 환경은 변경할 수 없습니다. 프로젝트에서 먼저 대상을 바꿔 주세요.");
            }
        }
        sg.setName(form.getName());
        sg.setEnvironment(form.getEnvironment());
        sg.setRollingIntervalSec(form.getRollingIntervalSec());
        sg.setMinAvailable(form.getMinAvailable());
        sg.setMaxUnavailable(form.getMaxUnavailable());
        sg.setObservationSeconds(form.getObservationSeconds());
        sg.setFailurePolicy(form.getFailurePolicy());
        sg.setApprovalPolicyId(form.getApprovalPolicyId());
        if (form.getWasVipEnabled() != null) {
            sg.setWasVipEnabled(Boolean.TRUE.equals(form.getWasVipEnabled()) ? true : null);
            sg.setWasVipAddress(Boolean.TRUE.equals(form.getWasVipEnabled()) ? WasVipPolicy.normalizeAddress(form.getWasVipAddress()) : null);
        }
        sg.setDescription(form.getDescription());
        return serverGroupRepository.save(sg);
    }

    private void attachServers(ServerGroup serverGroup) {
        serverGroup.setServers(serverRepository.findByServerGroupId(serverGroup.getId()));
    }

    private void validateApprovalPolicyChange(Long previous, Long requested) {
        if (!Objects.equals(previous, requested)) assertApprovalAdministrator();
        if (requested != null && (requested <= 0 || approvalPolicyRepository.findById(requested).isEmpty())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "선택한 그룹 승인 정책을 찾을 수 없습니다.");
        }
    }

    private void assertApprovalAdministrator() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
            || authentication.getAuthorities().stream().noneMatch(a -> "ROLE_SUPER_ADMIN".equals(a.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "그룹 승인 정책의 추가·변경·해제는 최고 관리자만 할 수 있습니다. 기존 정책을 유지하거나 화면을 새로고침해 주세요.");
        }
    }

    private void validateInterval(int interval) {
        if (interval < 0 || interval > 600) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "서버 간 배포 간격은 0~600초로 입력해 주세요.");
        }
    }

    private void validatePolicy(com.portfolio.deployops.dto.ServerGroupForm form) {
        validateInterval(form.getRollingIntervalSec());
        if (form.getName() == null || form.getName().isBlank()
            || form.getEnvironment() == null
            || form.getEnvironment() == com.portfolio.deployops.domain.ServerEnvironment.DEV) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "그룹명과 환경을 지정해 주세요. 개발·검증 서버는 TEST, 운영 서버는 PROD를 사용합니다.");
        }
        if (form.getMinAvailable() < 0 || form.getMinAvailable() > 1000
            || form.getMaxUnavailable() != 1
            || form.getObservationSeconds() < 0 || form.getObservationSeconds() > 600
            || form.getFailurePolicy() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "최소 가용 서버는 0~1000대, 동시 중단은 1대, 안정화 관찰은 0~600초로 지정해 주세요.");
        }
    }

    /**
     * 받은 순서대로 표시 순서를 0부터 다시 매긴다.
     *
     * <p>클라이언트가 계산한 번호를 그대로 쓰지 않는다 — 중간에 그룹이 삭제됐거나 다른 사람이
     * 먼저 순서를 바꿨으면 번호가 어긋난다. 목록의 "자리"만 신뢰하고 번호는 서버가 다시 붙인다.
     */
    @Transactional
    public void reorder(List<Long> orderedIds) {
        int order = 0;
        for (Long id : orderedIds) {
            serverGroupRepository.updateDisplayOrder(id, order++);
        }
    }
}
