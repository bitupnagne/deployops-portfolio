package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.Server;
import com.portfolio.deployops.repository.ServerRepository;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 등록 전에 만들어진 별칭도 요청 생성·실행 계획 검증에서 그룹 추가 승인을 우회할 수 없다. */
@Service
@RequiredArgsConstructor
public class ServerApprovalBoundaryService {
    private final ServerRepository serverRepository;

    /**
     * 호출자는 요청 환경·선택 그룹·배포 범위를 반영한 실제 대상만 넘긴다.
     * 환경/포트/계정이 달라도 같은 IP의 미선택 보호 그룹은 생략할 수 없다.
     * DNS 동등성 및 서로 다른 NIC IP의 물리 호스트 동등성은 추측하지 않는다.
     */
    @Transactional(readOnly = true)
    public void assertSelectedTargetsCovered(List<Server> actualTargets, Collection<Long> selectedGroupIds) {
        if (actualTargets == null || actualTargets.isEmpty()) return;
        if (selectedGroupIds == null) throw new ResponseStatusException(HttpStatus.CONFLICT,
            "선택한 서버 그룹을 확인할 수 없습니다. 배포 요청을 다시 만들어 주세요.");
        for (Server registered : serverRepository.findAll()) {
            var group = registered.getServerGroup();
            if (group == null || group.getApprovalPolicyId() == null || selectedGroupIds.contains(group.getId())) continue;
            String protectedHost = ServerHostIdentity.canonical(registered.getIpAddress());
            for (Server target : actualTargets) {
                String targetHost = ServerHostIdentity.canonical(target.getIpAddress());
                if (protectedHost == null || targetHost == null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "미선택 추가 승인 그룹과 배포 대상의 IP 동일성을 확인할 수 없습니다. 최고 관리자가 DNS·별칭 등록을 정리한 뒤 다시 요청해 주세요.");
                }
                if (protectedHost.equals(targetHost)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "배포 대상이 선택하지 않은 추가 승인 그룹(" + group.getId()
                            + ")의 등록 호스트와 같습니다. 해당 그룹의 승인 정책을 생략할 수 없습니다. 그룹 선택 또는 중복 서버 등록을 확인해 주세요.");
                }
            }
        }
    }
}
