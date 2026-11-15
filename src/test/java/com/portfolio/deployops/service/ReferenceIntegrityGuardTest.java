package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.deployops.domain.Server;
import com.portfolio.deployops.domain.ServerGroup;
import com.portfolio.deployops.repository.ProjectRepository;
import com.portfolio.deployops.repository.RemoteCommandLogRepository;
import com.portfolio.deployops.repository.ManualServerExecutionRepository;
import com.portfolio.deployops.repository.ServerGroupRepository;
import com.portfolio.deployops.repository.ServerRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 삭제 전 참조 검사.
 *
 * <p>서버·서버그룹은 hard delete 인데 이들을 가리키는 FK 가 셋 있다
 * ({@code remote_command_log.server_id} · {@code project_server_groups.server_group_id} ·
 * {@code server.server_group_id}). 검사 없이 지우면 DB 가 거절하고,
 * 그 {@code DataIntegrityViolationException} 이 전역 핸들러까지 올라가 <b>500 + log.error</b> 가 된다.
 *
 * <p>배포시스템은 전사 유일 배포 경로라 "500 발생률"이 곧 장애 신호다.
 * 운영자가 삭제 버튼을 잘못 누른 것까지 거기 섞이면 그 신호가 죽는다 —
 * 2026-07-28 에 405/400/415 를 같은 이유로 걷어냈던 것과 같은 부류다.
 */
class ReferenceIntegrityGuardTest {

    private final ServerRepository serverRepository = Mockito.mock(ServerRepository.class);
    private final ServerGroupRepository serverGroupRepository = Mockito.mock(ServerGroupRepository.class);
    private final ProjectRepository projectRepository = Mockito.mock(ProjectRepository.class);
    private final RemoteCommandLogRepository logRepository = Mockito.mock(RemoteCommandLogRepository.class);

    private final ManualServerExecutionRepository manualExecutionRepository = Mockito.mock(ManualServerExecutionRepository.class);
    private final ServerService serverService = new ServerService(serverRepository, logRepository, manualExecutionRepository);
    private final ServerGroupService serverGroupService =
        new ServerGroupService(serverGroupRepository, serverRepository, projectRepository);

    private Server server(long id, String name) {
        return Server.builder().id(id).name(name).ipAddress("10.10.1." + id).build();
    }

    private ServerGroup group(long id, String name) {
        return ServerGroup.builder().id(id).name(name).build();
    }

    @Test
    @DisplayName("배포 실행 기록이 있는 서버는 409 로 거절한다 — 500 이 아니라")
    void serverWithDeployHistoryIsRejectedWithConflict() {
        when(serverRepository.findByIdForMutation(7L)).thenReturn(Optional.of(server(7L, "prd-was1")));
        when(logRepository.countByServerId(7L)).thenReturn(42L);

        assertThatThrownBy(() -> serverService.delete(7L))
            .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                // 운영자가 화면에서 읽고 다음 행동을 정할 수 있어야 한다.
                assertThat(ex.getReason()).contains("prd-was1").contains("42");
            });

        verify(serverRepository, never()).delete(any());
    }

    @Test
    @DisplayName("참조가 없는 서버는 그대로 삭제된다")
    void serverWithoutHistoryIsDeleted() {
        Server s = server(8L, "temp-was");
        when(serverRepository.findByIdForMutation(8L)).thenReturn(Optional.of(s));
        when(logRepository.countByServerId(8L)).thenReturn(0L);

        serverService.delete(8L);

        verify(serverRepository).delete(s);
    }

    @Test
    @DisplayName("프로젝트가 쓰는 서버 그룹은 409 로 거절하고 프로젝트 이름을 알려 준다")
    void serverGroupUsedByProjectIsRejectedWithProjectNames() {
        when(serverGroupRepository.findById(3L)).thenReturn(Optional.of(group(3L, "운영 WAS")));
        when(serverRepository.findByServerGroupId(3L)).thenReturn(List.of());
        when(projectRepository.findProjectNamesByServerGroupId(3L))
            .thenReturn(List.of("오피스시스템", "배포시스템"));

        assertThatThrownBy(() -> serverGroupService.delete(3L))
            .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                // 이름을 돌려주지 않으면 운영자는 무엇을 먼저 손봐야 할지 알 수 없다.
                assertThat(ex.getReason()).contains("오피스시스템").contains("배포시스템");
            });

        verify(serverGroupRepository, never()).delete(any());
    }

    @Test
    @DisplayName("서버가 남아 있는 그룹은 409 로 거절한다")
    void serverGroupWithMemberServersIsRejected() {
        when(serverGroupRepository.findById(4L)).thenReturn(Optional.of(group(4L, "개발 WEB")));
        when(projectRepository.findProjectNamesByServerGroupId(4L)).thenReturn(List.of());
        when(serverRepository.findByServerGroupId(4L))
            .thenReturn(List.of(server(11L, "devweb1"), server(12L, "devweb2")));

        assertThatThrownBy(() -> serverGroupService.delete(4L))
            .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(ex.getReason()).contains("devweb1").contains("devweb2");
            });

        verify(serverGroupRepository, never()).delete(any());
    }

    @Test
    @DisplayName("참조가 하나도 없는 그룹은 삭제된다")
    void emptyServerGroupIsDeleted() {
        ServerGroup sg = group(5L, "폐기 예정");
        when(serverGroupRepository.findById(5L)).thenReturn(Optional.of(sg));
        when(projectRepository.findProjectNamesByServerGroupId(5L)).thenReturn(List.of());
        when(serverRepository.findByServerGroupId(5L)).thenReturn(List.of());

        serverGroupService.delete(5L);

        verify(serverGroupRepository).delete(sg);
    }
}
