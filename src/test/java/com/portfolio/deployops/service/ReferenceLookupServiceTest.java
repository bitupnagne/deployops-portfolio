package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.portfolio.deployops.dto.ReferenceReport;
import com.portfolio.deployops.repository.ReferenceRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;

/**
 * 삭제를 막는 것들을 어떻게 설명하는지 고정한다.
 *
 * <p>여기서 중요한 것은 개수가 아니라 <b>사용자에게 무엇을 시키는가</b>다.
 * 배포 실행 기록은 감사 자료라 지우면 안 되는데, 다른 항목과 똑같이 "가서 정리하세요" 로
 * 보이면 지우지 말아야 할 것을 지우게 된다. 그래서 그 구분({@code resolvable})을
 * 화면이 아니라 여기서 정한다 — 화면이 늘어나도 판단이 갈리지 않게.
 */
class ReferenceLookupServiceTest {

    private final ReferenceRepository repository = Mockito.mock(ReferenceRepository.class);
    private final ReferenceLookupService service = new ReferenceLookupService(repository);

    private ReferenceReport.Item item(long id, String name) {
        return ReferenceReport.Item.builder().id(id).name(name).detail("-").build();
    }

    @Test
    @DisplayName("배포 실행 기록은 '치우라'고 하지 않는다 — 감사 자료다")
    void deployHistoryIsNotPresentedAsSomethingToDelete() {
        when(repository.serverName(7L)).thenReturn("prd-was1");
        when(repository.countCommandLogsByServerId(7L)).thenReturn(42L);
        when(repository.findCommandLogsByServerId(anyLong(), anyInt()))
            .thenReturn(List.of(item(1L, "docker restart")));

        ReferenceReport report = service.lookup("server", 7L);

        assertThat(report.isDeletable()).isFalse();
        assertThat(report.getBlockers()).hasSize(1);
        ReferenceReport.Blocker blocker = report.getBlockers().get(0);
        assertThat(blocker.isResolvable())
            .as("true 면 화면이 '바로 가기' 링크를 만들어 지워도 되는 것처럼 보인다")
            .isFalse();
        assertThat(blocker.getTotal()).isEqualTo(42L);
        assertThat(blocker.getHowTo()).contains("그룹에서 빼");
    }

    @Test
    @DisplayName("서버 그룹은 막는 것을 종류별로 나눠 준다")
    void serverGroupSeparatesProjectsFromMemberServers() {
        when(repository.serverGroupName(3L)).thenReturn("PROD-WAS");
        when(repository.findProjectsByServerGroupId(3L))
            .thenReturn(List.of(item(11L, "worksystem-server")));
        when(repository.findServersByServerGroupId(3L))
            .thenReturn(List.of(item(21L, "prd-was1"), item(22L, "prd-was2")));

        ReferenceReport report = service.lookup("server-group", 3L);

        assertThat(report.getBlockers()).hasSize(2);
        assertThat(report.getBlockers()).allMatch(ReferenceReport.Blocker::isResolvable);
        // 화면 id 가 붙어 있어야 목록에서 그 자리로 보낼 수 있다.
        assertThat(report.getBlockers().get(0).getPage()).isEqualTo("project-detail");
        assertThat(report.getBlockers().get(1).getPage()).isEqualTo("servers");
    }

    @Test
    @DisplayName("막는 것이 없으면 deletable 로 알려 준다")
    void nothingBlockingIsReportedAsDeletable() {
        when(repository.policyName(5L)).thenReturn("2단계 승인");
        when(repository.findProjectsByPolicyId(5L)).thenReturn(List.of());

        ReferenceReport report = service.lookup("policy", 5L);

        assertThat(report.isDeletable()).isTrue();
        assertThat(report.getBlockers()).isEmpty();
    }

    @Test
    @DisplayName("모르는 종류는 400 으로 거절한다 — 500 이 아니라")
    void unknownTypeIsRejectedAsBadRequest() {
        assertThatThrownBy(() -> service.lookup("banana", 1L))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("400");
    }

    @Test
    @DisplayName("없는 대상은 404 로 거절한다")
    void missingTargetIsReportedAsNotFound() {
        when(repository.serverName(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.lookup("server", 99L))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("404");
    }
}
