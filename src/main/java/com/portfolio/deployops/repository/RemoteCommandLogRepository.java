package com.portfolio.deployops.repository;

import com.portfolio.deployops.domain.RemoteCommandLog;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RemoteCommandLogRepository {

    /** 같은 요청의 대상별 최신 성공만 재시도에서 유지한다. 이력 본문은 읽지 않는다. */
    @Select("""
        SELECT l.server_id FROM remote_command_log l
        JOIN (SELECT server_id, MAX(id) AS id FROM remote_command_log
              WHERE deploy_request_id = #{requestId} GROUP BY server_id) latest ON latest.id = l.id
        LEFT JOIN (SELECT server_id, MAX(id) AS id FROM target_deployment_attempt
                   WHERE deploy_request_id = #{requestId} GROUP BY server_id) attempt ON attempt.server_id = l.server_id
        WHERE l.deploy_request_id = #{requestId} AND l.success = TRUE AND l.exit_code = 0
          AND (attempt.id IS NULL OR l.target_attempt_id = attempt.id)
        """)
    List<Long> findLatestSuccessfulServerIds(Long requestId);

    @Insert("""
        INSERT INTO target_deployment_attempt (deploy_request_id,server_id,plan_hash,artifact_hash,created_at)
        SELECT id, #{serverId}, #{planHash}, #{artifactHash}, NOW(6) FROM deploy_request
        WHERE id = #{deployRequestId} AND plan_snapshot_hash = #{planHash}
          AND status IN ('TEST_DEPLOYING','PROD_DEPLOYING')
          AND target_attempt_tracking_started_at IS NOT NULL
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertTargetAttempt(com.portfolio.deployops.domain.TargetDeploymentAttempt attempt);

    /** Bounded stored request logs; content was masked at persistence, reader masks once more. */
    @Select("""
        SELECT id, deploy_request_id, target_attempt_id, server_id, LEFT(server_name, 200) AS server_name,
               LEFT(command_display, 1025) AS command_display,
               LEFT(stdout_text, 16385) AS stdout_text, LEFT(stderr_text, 16385) AS stderr_text,
               exit_code, success, started_at, finished_at, duration_ms, created_at
        FROM remote_command_log WHERE deploy_request_id = #{requestId}
        ORDER BY id DESC LIMIT #{limit}
        """)
    List<RemoteCommandLog> findBoundedByRequest(@Param("requestId") Long requestId, @Param("limit") int limit);
    @Select("""
        SELECT id, deploy_request_id, target_attempt_id, server_id, server_name, command_display,
               stdout_text, stderr_text, exit_code, success,
               started_at, finished_at, duration_ms, created_at
        FROM remote_command_log
        WHERE deploy_request_id = #{deployRequestId}
        ORDER BY id
        """)
    List<RemoteCommandLog> findByDeployRequestId(Long deployRequestId);

    /**
     * 이 서버를 참조하는 실행 기록 수 — 서버 삭제 전 확인용.
     *
     * <p>{@code fk_remote_command_log_server} 때문에 하나라도 있으면 DELETE 가 실패한다.
     * 그 실패를 예외로 맞기 전에 세어 보고 사람이 읽을 수 있는 이유를 돌려주기 위한 것이다.
     */
    @Select("SELECT COUNT(*) FROM remote_command_log WHERE server_id = #{serverId}")
    long countByServerId(Long serverId);

    /**
     * 해당 프로젝트에서 그 서버의 최근 실행 결과만 (성공 여부). 구조도의 "가끔 오류" 판정에 쓴다.
     *
     * <p>지금 연결이 되는지(TCP)와 <b>요즘 잘 돌아가는지</b>는 다른 질문이다.
     * 붙기는 붙는데 최근 실패가 섞여 있는 구간을 초록으로 칠하면, 화면이 정상이라고
     * 말하는 동안 실제로는 반복 실패하고 있는 상태를 놓친다.
     *
     * <p>본문(stdout/stderr)은 가져오지 않는다 — 판정에 필요한 것은 성공 여부뿐이고,
     * 서버 수만큼 조회하므로 본문까지 끌면 그대로 응답 크기가 된다.
     */
    @Select("""
        SELECT l.success
        FROM remote_command_log l
        JOIN deploy_request r ON r.id = l.deploy_request_id
        WHERE r.project_id = #{projectId}
          AND l.server_id = #{serverId}
        ORDER BY l.id DESC
        LIMIT #{limit}
        """)
    List<Boolean> findRecentOutcomesByProjectIdAndServerId(@Param("projectId") Long projectId,
                                                            @Param("serverId") Long serverId,
                                                            @Param("limit") int limit);

    /**
     * 프로젝트와 서버가 모두 일치하는 최근 실행 기록 — 구조도의 [로그] 버튼이 쓴다.
     *
     * <p>stdout/stderr 는 저장 시점에 이미 마스킹되어 있다({@code RemoteCommandLogService}).
     * 그래도 화면이 감당할 만큼만 가져온다 — 배포 로그 전체는 별도 패널이 담당하고,
     * 여기서 필요한 것은 "이 서버에서 최근 무슨 명령이 어떻게 끝났나" 다.
     */
    @Select("""
        SELECT l.id, l.deploy_request_id, l.target_attempt_id, l.server_id, l.server_name, l.command_display,
               l.stdout_text, l.stderr_text, l.exit_code, l.success,
               l.started_at, l.finished_at, l.duration_ms, l.created_at
        FROM remote_command_log l
        JOIN deploy_request r ON r.id = l.deploy_request_id
        WHERE r.project_id = #{projectId}
          AND l.server_id = #{serverId}
        ORDER BY l.id DESC
        LIMIT #{limit}
        """)
    List<RemoteCommandLog> findRecentByProjectIdAndServerId(@Param("projectId") Long projectId,
                                                         @Param("serverId") Long serverId,
                                                         @Param("limit") int limit);

    /**
     * 프로젝트의 최근 원격 명령 기록. 프로젝트 상세의 "배포 로그" 패널이 쓴다.
     *
     * <p>stdout/stderr 는 저장 시점에 이미 마스킹되어 있다({@code RemoteCommandLogService}).
     * 이 패널의 내용이 그대로 AI 분석에 넘어가므로, 여기서 새로 읽어 오는 값에도 같은 보장이 필요하다.
     */
    @Select("""
        SELECT l.id, l.deploy_request_id, l.target_attempt_id, l.server_id, l.server_name, l.command_display,
               l.stdout_text, l.stderr_text, l.exit_code, l.success,
               l.started_at, l.finished_at, l.duration_ms, l.created_at
        FROM remote_command_log l
        JOIN deploy_request r ON r.id = l.deploy_request_id
        WHERE r.project_id = #{projectId}
        ORDER BY l.id DESC
        LIMIT #{limit}
        """)
    List<RemoteCommandLog> findRecentByProjectId(@Param("projectId") Long projectId,
                                                 @Param("limit") int limit);

    @Insert("""
        INSERT INTO remote_command_log (
            deploy_request_id, target_attempt_id, server_id, server_name, command_display,
            stdout_text, stderr_text, exit_code, success,
            started_at, finished_at, duration_ms, created_at
        ) VALUES (
            #{deployRequestId}, #{targetAttemptId}, #{serverId}, #{serverName}, #{commandDisplay},
            #{stdoutText}, #{stderrText}, #{exitCode}, #{success},
            #{startedAt}, #{finishedAt}, #{durationMs}, NOW(6)
        )
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(RemoteCommandLog log);
}
