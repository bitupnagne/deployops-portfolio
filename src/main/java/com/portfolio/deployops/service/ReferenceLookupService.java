package com.portfolio.deployops.service;

import com.portfolio.deployops.dto.ReferenceReport;
import com.portfolio.deployops.repository.ReferenceRepository;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 무엇이 삭제를 막고 있는지 모아 준다.
 *
 * <p>삭제 거절 메시지에 이름을 나열하는 것까지는 이미 하고 있었다. 부족한 것은
 * <b>거기서 무엇을 할 수 있는가</b>였다 — 이름만 봐서는 그 프로젝트가 어디 있는지,
 * 몇 개가 더 남았는지, 어떤 것은 손대면 안 되는지 알 수 없다.
 *
 * <p>그래서 막는 것을 종류별로 묶고, 각 항목에 갈 화면을 붙이고,
 * <b>사람이 치워도 되는 것과 남겨야 하는 것을 구분해서</b> 돌려준다.
 * 배포 실행 기록은 후자다 — 어느 장비에서 무엇이 일어났는지가 감사 자료라
 * "가서 지우세요" 라고 안내하면 안 된다. 그런 서버는 그룹에서 빼 두는 쪽이 맞다.
 *
 * <p>여기서 지우지는 않는다. 조회만 한다 — 실제 삭제는 각 서비스의 기존 경로를 그대로 탄다.
 */
@Service
@RequiredArgsConstructor
public class ReferenceLookupService {

    /** 목록에 실어 보낼 최대 건수. 나머지는 개수로만 알린다. */
    private static final int SAMPLE_LIMIT = 50;

    private final ReferenceRepository referenceRepository;

    @Transactional(readOnly = true)
    public ReferenceReport lookup(String type, Long id) {
        return switch (type) {
            case "server" -> server(id);
            case "server-group" -> serverGroup(id);
            case "policy" -> policy(id);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "참조를 조회할 수 없는 종류입니다: " + type);
        };
    }

    private ReferenceReport server(Long id) {
        String name = required(referenceRepository.serverName(id), "서버");
        List<ReferenceReport.Blocker> blockers = new ArrayList<>();

        long logs = referenceRepository.countCommandLogsByServerId(id);
        if (logs > 0) {
            blockers.add(ReferenceReport.Blocker.builder()
                .title("이 서버에 남은 배포 실행 기록")
                .why("어느 장비에서 무엇이 실행됐는지가 감사 자료라 서버를 지우면 근거가 사라집니다.")
                .howTo("기록은 지우지 마세요. 서버를 더 쓰지 않는다면 서버 그룹에서 빼 두면 "
                    + "배포 대상에서 제외되고 기록은 그대로 남습니다.")
                .page("servers")
                .total(logs)
                .items(referenceRepository.findCommandLogsByServerId(id, SAMPLE_LIMIT))
                .resolvable(false)
                .build());
        }
        return report("server", id, name, blockers);
    }

    private ReferenceReport serverGroup(Long id) {
        String name = required(referenceRepository.serverGroupName(id), "서버 그룹");
        List<ReferenceReport.Blocker> blockers = new ArrayList<>();

        List<ReferenceReport.Item> projects = referenceRepository.findProjectsByServerGroupId(id);
        if (!projects.isEmpty()) {
            blockers.add(ReferenceReport.Blocker.builder()
                .title("이 그룹을 배포 대상으로 쓰는 프로젝트")
                .why("배포 대상이 사라지면 그 프로젝트는 어디로 나가야 할지 알 수 없게 됩니다.")
                .howTo("각 프로젝트에서 배포 대상을 다른 그룹으로 바꾸거나 이 그룹을 빼 주세요.")
                .page("project-detail")
                .total(projects.size())
                .items(projects)
                .resolvable(true)
                .build());
        }

        List<ReferenceReport.Item> servers = referenceRepository.findServersByServerGroupId(id);
        if (!servers.isEmpty()) {
            blockers.add(ReferenceReport.Blocker.builder()
                .title("이 그룹에 속한 서버")
                .why("서버가 소속을 잃으면 어느 환경의 장비인지 판단할 수 없게 됩니다.")
                .howTo("서버를 다른 그룹으로 옮기거나, 더 쓰지 않는 서버라면 먼저 삭제해 주세요.")
                .page("servers")
                .total(servers.size())
                .items(servers)
                .resolvable(true)
                .build());
        }
        return report("server-group", id, name, blockers);
    }

    private ReferenceReport policy(Long id) {
        String name = required(referenceRepository.policyName(id), "승인 정책");
        List<ReferenceReport.Blocker> blockers = new ArrayList<>();

        List<ReferenceReport.Item> projects = referenceRepository.findProjectsByPolicyId(id);
        if (!projects.isEmpty()) {
            blockers.add(ReferenceReport.Blocker.builder()
                .title("이 정책을 쓰는 프로젝트")
                .why("승인 정책이 사라지면 그 프로젝트의 배포 요청이 누구에게 가야 할지 정해지지 않습니다.")
                .howTo("각 프로젝트에서 다른 승인 정책을 지정해 주세요.")
                .page("project-detail")
                .total(projects.size())
                .items(projects)
                .resolvable(true)
                .build());
        }
        return report("policy", id, name, blockers);
    }

    private ReferenceReport report(String type, Long id, String name,
                                   List<ReferenceReport.Blocker> blockers) {
        return ReferenceReport.builder()
            .type(type).id(id).name(name)
            .deletable(blockers.isEmpty())
            .blockers(blockers)
            .build();
    }

    private String required(String name, String what) {
        if (name == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, what + "을(를) 찾을 수 없습니다.");
        }
        return name;
    }
}
