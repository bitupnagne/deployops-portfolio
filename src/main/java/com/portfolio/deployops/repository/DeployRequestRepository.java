package com.portfolio.deployops.repository;

import com.portfolio.deployops.domain.DeployEnvironment;
import com.portfolio.deployops.domain.DeployRequest;
import com.portfolio.deployops.domain.DeployStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

@Mapper
public interface DeployRequestRepository {

    @Select("""
        <script>
        SELECT COUNT(*) FROM (
          SELECT deploy_request_id,server_id,created_at FROM remote_command_log
          UNION ALL SELECT deploy_request_id,server_id,created_at FROM target_deployment_attempt
        ) l
        JOIN deploy_request r ON r.id = l.deploy_request_id
        WHERE r.id != #{requestId} AND r.project_id = #{projectId} AND r.environment = #{environment}
          AND l.created_at &gt;= #{since}
          AND l.server_id IN
          <foreach collection="targetIds" item="targetId" open="(" separator="," close=")">#{targetId}</foreach>
        </script>
        """)
    long countSupersedingTargetAttempts(@Param("requestId") Long requestId,
        @Param("projectId") Long projectId, @Param("environment") String environment,
        @Param("targetIds") java.util.List<Long> targetIds,
        @Param("since") java.time.LocalDateTime since);

    @org.apache.ibatis.annotations.Select("""
        SELECT COUNT(*) FROM deploy_request
        WHERE project_id = #{projectId} AND gitlab_pipeline_id = #{pipelineId}
        """)
    int countByProjectAndPipelineId(@org.apache.ibatis.annotations.Param("projectId") Long projectId,
                                    @org.apache.ibatis.annotations.Param("pipelineId") Long pipelineId);

    @Update("""
        UPDATE deploy_request SET gitlab_pipeline_id = #{pipelineId}, updated_at = NOW(6)
        WHERE id = #{requestId} AND gitlab_pipeline_id IS NULL AND status = 'TEST_BUILDING'
        """)
    int bindCreatedPipeline(@Param("requestId") Long requestId, @Param("pipelineId") Long pipelineId);

    String DEPLOY_REQUEST_SELECT = """
        SELECT r.id, r.req_no, r.project_id, r.environment, r.deploy_scope, r.deploy_strategy, r.selected_server_group_ids, r.branch, r.image_tag, r.image_digest,
               r.gitlab_pipeline_id, r.commit_sha, r.expected_commit_sha, r.source_mr_iid,
               r.mr_evidence_id, r.immutable_build_ref, r.reason, r.failure_reason, r.withdrawal_reason,
               r.plan_snapshot_json, r.plan_snapshot_hash, r.plan_snapshot_version,
               r.approval_decision_source, r.execution_trigger, r.target_attempt_tracking_started_at,
               r.release_verification_status, r.release_verification_job_id,
               r.release_verification_repository, r.release_verified_at,
               r.release_verification_evidence_hash, r.release_manifest_sha256,
               r.auto_dispatch_blocked_at, r.auto_dispatch_block_reason,
               r.status, r.requested_by_id,
               r.test_confirmed_by_id, r.executed_by_id, r.promoted_from_request_id,
               r.test_confirmed_at, r.created_at, r.updated_at,
               p.name AS project_name, p.container_name AS project_container_name, p.description AS project_description, p.gitlab_repo_url AS project_gitlab_repo_url,
               p.gitlab_project_id AS project_gitlab_project_id, p.default_branch AS project_default_branch,
               p.pipeline_type AS project_pipeline_type, p.build_tool AS project_build_tool,
               p.build_file_path AS project_build_file_path,
               p.build_command AS project_build_command,
               p.artifact_path AS project_artifact_path,
               p.dockerfile_path AS project_dockerfile_path,
               p.docker_context_path AS project_docker_context_path,
               p.image_repository AS project_image_repository,
               p.image_tag_pattern AS project_image_tag_pattern,
               p.nexus_docker_repository AS project_nexus_docker_repository,
               p.nexus_maven_release_repository AS project_nexus_maven_release_repository,
               p.nexus_maven_snapshot_repository AS project_nexus_maven_snapshot_repository,
               p.health_check_mode AS project_health_check_mode,
               p.health_check_path AS project_health_check_path,
               p.health_check_port AS project_health_check_port, p.was_vip_port AS project_was_vip_port,
               p.remote_deploy_script_path AS project_remote_deploy_script_path,
               p.docker_network AS project_docker_network,
               p.env_file_path AS project_env_file_path,
               ap.id AS project_approval_policy_id, ap.name AS project_approval_policy_name,
               ap.policy_type AS project_approval_policy_type,
               ap.allow_self_approval AS project_approval_policy_allow_self_approval,
               u.id AS requested_by_user_id, u.login_id AS requested_by_login_id, u.name AS requested_by_name,
               u.role AS requested_by_role, u.active AS requested_by_active,
               tc.id AS test_confirmed_by_user_id, tc.login_id AS test_confirmed_by_login_id,
               tc.name AS test_confirmed_by_name,
               tc.role AS test_confirmed_by_role, tc.active AS test_confirmed_by_active,
               eb.id AS executed_by_user_id, eb.login_id AS executed_by_login_id,
               eb.name AS executed_by_name,
               eb.role AS executed_by_role, eb.active AS executed_by_active
        FROM deploy_request r
        JOIN project p ON r.project_id = p.id
        LEFT JOIN approval_policy ap ON p.approval_policy_id = ap.id
        LEFT JOIN users u ON r.requested_by_id = u.id
        LEFT JOIN users tc ON r.test_confirmed_by_id = tc.id
        LEFT JOIN users eb ON r.executed_by_id = eb.id
        """;

    @Results(id = "deployRequestResult", value = {
        @Result(property = "id", column = "id", id = true),
        @Result(property = "reqNo", column = "req_no"),
        @Result(property = "environment", column = "environment"),
        @Result(property = "deployScope", column = "deploy_scope"),
        @Result(property = "deployStrategy", column = "deploy_strategy"),
        @Result(property = "selectedServerGroupIds", column = "selected_server_group_ids", typeHandler = LongIdListTypeHandler.class),
        @Result(property = "branch", column = "branch"),
        @Result(property = "imageTag", column = "image_tag"),
        @Result(property = "imageDigest", column = "image_digest"),
        @Result(property = "gitlabPipelineId", column = "gitlab_pipeline_id"),
        @Result(property = "commitSha", column = "commit_sha"),
        @Result(property = "expectedCommitSha", column = "expected_commit_sha"),
        @Result(property = "sourceMrIid", column = "source_mr_iid"),
        @Result(property = "mrEvidenceId", column = "mr_evidence_id"),
        @Result(property = "immutableBuildRef", column = "immutable_build_ref"),
        @Result(property = "reason", column = "reason"),
        @Result(property = "failureReason", column = "failure_reason"),
        @Result(property = "withdrawalReason", column = "withdrawal_reason"),
        @Result(property = "planSnapshotJson", column = "plan_snapshot_json"),
        @Result(property = "planSnapshotHash", column = "plan_snapshot_hash"),
        @Result(property = "planSnapshotVersion", column = "plan_snapshot_version"),
        @Result(property = "approvalDecisionSource", column = "approval_decision_source"),
        @Result(property = "executionTrigger", column = "execution_trigger"),
        @Result(property = "targetAttemptTrackingStartedAt", column = "target_attempt_tracking_started_at"),
        @Result(property = "releaseVerificationStatus", column = "release_verification_status"),
        @Result(property = "releaseVerificationJobId", column = "release_verification_job_id"),
        @Result(property = "releaseVerificationRepository", column = "release_verification_repository"),
        @Result(property = "releaseVerifiedAt", column = "release_verified_at"),
        @Result(property = "releaseVerificationEvidenceHash", column = "release_verification_evidence_hash"),
        @Result(property = "releaseManifestSha256", column = "release_manifest_sha256"),
        @Result(property = "autoDispatchBlockedAt", column = "auto_dispatch_blocked_at"),
        @Result(property = "autoDispatchBlockReason", column = "auto_dispatch_block_reason"),
        @Result(property = "status", column = "status"),
        @Result(property = "testConfirmedAt", column = "test_confirmed_at"),
        @Result(property = "promotedFromRequest.id", column = "promoted_from_request_id"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at"),
        @Result(property = "project.id", column = "project_id"),
        @Result(property = "project.name", column = "project_name"),
        @Result(property = "project.containerName", column = "project_container_name"),
        @Result(property = "project.description", column = "project_description"),
        @Result(property = "project.gitlabRepoUrl", column = "project_gitlab_repo_url"),
        @Result(property = "project.gitlabProjectId", column = "project_gitlab_project_id"),
        @Result(property = "project.defaultBranch", column = "project_default_branch"),
        @Result(property = "project.pipelineType", column = "project_pipeline_type"),
        @Result(property = "project.buildTool", column = "project_build_tool"),
        @Result(property = "project.buildFilePath", column = "project_build_file_path"),
        @Result(property = "project.buildCommand", column = "project_build_command"),
        @Result(property = "project.artifactPath", column = "project_artifact_path"),
        @Result(property = "project.dockerfilePath", column = "project_dockerfile_path"),
        @Result(property = "project.dockerContextPath", column = "project_docker_context_path"),
        @Result(property = "project.imageRepository", column = "project_image_repository"),
        @Result(property = "project.imageTagPattern", column = "project_image_tag_pattern"),
        @Result(property = "project.nexusDockerRepository", column = "project_nexus_docker_repository"),
        @Result(property = "project.nexusMavenReleaseRepository", column = "project_nexus_maven_release_repository"),
        @Result(property = "project.nexusMavenSnapshotRepository", column = "project_nexus_maven_snapshot_repository"),
        @Result(property = "project.healthCheckMode", column = "project_health_check_mode"),
        @Result(property = "project.healthCheckPath", column = "project_health_check_path"),
        @Result(property = "project.healthCheckPort", column = "project_health_check_port"),
        @Result(property = "project.wasVipPort", column = "project_was_vip_port"),
        @Result(property = "project.remoteDeployScriptPath", column = "project_remote_deploy_script_path"),
        @Result(property = "project.dockerNetwork", column = "project_docker_network"),
        @Result(property = "project.envFilePath", column = "project_env_file_path"),
        @Result(property = "project.approvalPolicy.id", column = "project_approval_policy_id"),
        @Result(property = "project.approvalPolicy.name", column = "project_approval_policy_name"),
        @Result(property = "project.approvalPolicy.policyType", column = "project_approval_policy_type"),
        @Result(property = "project.approvalPolicy.allowSelfApproval", column = "project_approval_policy_allow_self_approval"),
        @Result(property = "requestedBy.id", column = "requested_by_user_id"),
        @Result(property = "requestedBy.loginId", column = "requested_by_login_id"),
        @Result(property = "requestedBy.name", column = "requested_by_name"),
        @Result(property = "requestedBy.role", column = "requested_by_role"),
        @Result(property = "requestedBy.active", column = "requested_by_active"),
        @Result(property = "testConfirmedBy.id", column = "test_confirmed_by_user_id"),
        @Result(property = "testConfirmedBy.loginId", column = "test_confirmed_by_login_id"),
        @Result(property = "testConfirmedBy.name", column = "test_confirmed_by_name"),
        @Result(property = "testConfirmedBy.role", column = "test_confirmed_by_role"),
        @Result(property = "testConfirmedBy.active", column = "test_confirmed_by_active"),
        @Result(property = "executedBy.id", column = "executed_by_user_id"),
        @Result(property = "executedBy.loginId", column = "executed_by_login_id"),
        @Result(property = "executedBy.name", column = "executed_by_name"),
        @Result(property = "executedBy.role", column = "executed_by_role"),
        @Result(property = "executedBy.active", column = "executed_by_active")
    })
    @Select(DEPLOY_REQUEST_SELECT + " WHERE r.req_no = #{reqNo}")
    Optional<DeployRequest> findByReqNo(String reqNo);

    @Select("""
        SELECT COUNT(*) > 0
        FROM deploy_request
        WHERE req_no = #{reqNo}
        """)
    boolean existsByReqNo(@Param("reqNo") String reqNo);

    @Select(DEPLOY_REQUEST_SELECT + " WHERE r.id = #{id}")
    @ResultMap("deployRequestResult")
    Optional<DeployRequest> findById(Long id);

    @Select("""
        <script>
        """ + DEPLOY_REQUEST_SELECT + """
        WHERE r.project_id = #{projectId}
          AND r.environment = #{environment}
          AND r.status IN
          <foreach collection="statuses" item="status" open="(" separator="," close=")">
              #{status}
          </foreach>
        ORDER BY r.id DESC
        </script>
        """)
    @ResultMap("deployRequestResult")
    List<DeployRequest> findByProjectIdAndEnvironmentAndStatusInWithProject(
        @Param("projectId") Long projectId,
        @Param("environment") DeployEnvironment environment,
        @Param("statuses") List<DeployStatus> statuses
    );

    /** 업그레이드 이전 실행 코드의 누락된 원격 이력도 실패 안전하게 판정한다. */
    @Select(DEPLOY_REQUEST_SELECT + """
        WHERE r.id != #{requestId} AND r.project_id = #{projectId} AND r.environment = #{environment}
          AND r.target_attempt_tracking_started_at IS NULL
          AND EXISTS (SELECT 1 FROM status_history h WHERE h.deploy_request_id = r.id
              AND h.status IN ('TEST_DEPLOYING','PROD_DEPLOYING') AND h.created_at >= #{since})
        """)
    @ResultMap("deployRequestResult")
    List<DeployRequest> findUntrackedExecutionsSince(@Param("requestId") Long requestId,
        @Param("projectId") Long projectId, @Param("environment") DeployEnvironment environment,
        @Param("since") LocalDateTime since);

    /** 승인/반려 판정 동안 같은 요청의 상태와 승인 기록을 직렬화한다. */
    @Select("SELECT id FROM deploy_request WHERE id = #{id} FOR UPDATE")
    Long lockById(Long id);

    default Optional<DeployRequest> findByIdWithProject(Long id) {
        return findById(id);
    }

    @Select("""
        <script>
        SELECT COUNT(*)
        FROM deploy_request r
        WHERE r.created_at BETWEEN #{from} AND #{to}
        <if test="status != null">
          AND r.status = #{status}
        </if>
        <if test="projectId != null">
          AND r.project_id = #{projectId}
        </if>
        </script>
        """)
    long countWithFilter(@Param("status") DeployStatus status,
                         @Param("projectId") Long projectId,
                         @Param("from") LocalDateTime from,
                         @Param("to") LocalDateTime to);

    @Select("""
        <script>
        """ + DEPLOY_REQUEST_SELECT + """
        WHERE r.created_at BETWEEN #{from} AND #{to}
        <if test="status != null">
          AND r.status = #{status}
        </if>
        <if test="projectId != null">
          AND r.project_id = #{projectId}
        </if>
        ORDER BY r.created_at DESC
        LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    @ResultMap("deployRequestResult")
    List<DeployRequest> findWithFilterPage(@Param("status") DeployStatus status,
                                           @Param("projectId") Long projectId,
                                           @Param("from") LocalDateTime from,
                                           @Param("to") LocalDateTime to,
                                           @Param("limit") int limit,
                                           @Param("offset") long offset);

    @Select("""
        <script>
        SELECT COUNT(*)
        FROM deploy_request r
        WHERE r.created_at BETWEEN #{from} AND #{to}
          AND r.project_id IN
          <foreach collection="projectIds" item="projectId" open="(" separator="," close=")">
              #{projectId}
          </foreach>
        <if test="status != null">
          AND r.status = #{status}
        </if>
        </script>
        """)
    long countWithFilterForProjectIds(@Param("status") DeployStatus status,
                                      @Param("projectIds") List<Long> projectIds,
                                      @Param("from") LocalDateTime from,
                                      @Param("to") LocalDateTime to);

    @Select("""
        <script>
        """ + DEPLOY_REQUEST_SELECT + """
        WHERE r.created_at BETWEEN #{from} AND #{to}
          AND r.project_id IN
          <foreach collection="projectIds" item="projectId" open="(" separator="," close=")">
              #{projectId}
          </foreach>
        <if test="status != null">
          AND r.status = #{status}
        </if>
        ORDER BY r.created_at DESC
        LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    @ResultMap("deployRequestResult")
    List<DeployRequest> findWithFilterPageForProjectIds(@Param("status") DeployStatus status,
                                                        @Param("projectIds") List<Long> projectIds,
                                                        @Param("from") LocalDateTime from,
                                                        @Param("to") LocalDateTime to,
                                                        @Param("limit") int limit,
                                                        @Param("offset") long offset);

    @Select("""
        SELECT COUNT(*)
        FROM deploy_request
        WHERE status = #{status}
          AND created_at BETWEEN #{from} AND #{to}
        """)
    long countByStatusAndCreatedAtBetween(DeployStatus status, LocalDateTime from, LocalDateTime to);

    @Select("""
        <script>
        SELECT COUNT(*)
        FROM deploy_request
        WHERE status = #{status}
          AND created_at BETWEEN #{from} AND #{to}
          AND project_id IN
          <foreach collection="projectIds" item="projectId" open="(" separator="," close=")">
              #{projectId}
          </foreach>
        </script>
        """)
    long countByStatusAndCreatedAtBetweenForProjectIds(@Param("status") DeployStatus status,
                                                       @Param("from") LocalDateTime from,
                                                       @Param("to") LocalDateTime to,
                                                       @Param("projectIds") List<Long> projectIds);

    @Select("""
        SELECT COUNT(*)
        FROM deploy_request
        WHERE created_at BETWEEN #{from} AND #{to}
        """)
    long countByCreatedAtBetween(LocalDateTime from, LocalDateTime to);

    @Select("""
        <script>
        SELECT COUNT(*)
        FROM deploy_request
        WHERE created_at BETWEEN #{from} AND #{to}
          AND project_id IN
          <foreach collection="projectIds" item="projectId" open="(" separator="," close=")">
              #{projectId}
          </foreach>
        </script>
        """)
    long countByCreatedAtBetweenForProjectIds(@Param("from") LocalDateTime from,
                                              @Param("to") LocalDateTime to,
                                              @Param("projectIds") List<Long> projectIds);

    /**
     * 대시보드 차트용 집계 — 날짜 × 환경 × 상태 별 건수.
     *
     * <p>기간 전체를 한 번에 긁어 와 화면에서 조립한다. 날짜마다 질의하면 14일치에 14번을 쏘게 되고,
     * 그 사이 자정을 넘기면 앞뒤 날짜가 어긋난다.
     *
     * <p><b>비어 있는 날은 행이 없다.</b> GROUP BY 는 데이터가 없는 날짜를 만들어 내지 않는다 —
     * 배포가 없던 날을 0 으로 채우는 것은 서비스 계층 몫이고, 안 채우면 차트에서 그 날이
     * 통째로 빠져 추이가 왜곡된다.
     *
     * <p>{@code projectIds} 가 비면 <b>전체</b>다. 호출부(SUPER_ADMIN 여부)가 이미 판단한 뒤 넘긴다 —
     * 빈 목록을 "권한 없음"으로 해석하면 SUPER_ADMIN 조회가 막히므로 그 구분은 서비스가 한다.
     */
    @Select("""
        <script>
        SELECT DATE(created_at) AS day,
               environment      AS environment,
               status           AS status,
               COUNT(*)         AS cnt
        FROM deploy_request
        WHERE created_at &gt;= #{from} AND created_at &lt; #{to}
        <if test="projectIds != null and projectIds.size() > 0">
          AND project_id IN
          <foreach collection="projectIds" item="projectId" open="(" separator="," close=")">
              #{projectId}
          </foreach>
        </if>
        GROUP BY DATE(created_at), environment, status
        ORDER BY day
        </script>
        """)
    @Results({
        @Result(property = "day", column = "day"),
        @Result(property = "environment", column = "environment"),
        @Result(property = "status", column = "status"),
        @Result(property = "count", column = "cnt")
    })
    List<DeployStatBucket> findDailyStats(@Param("from") LocalDateTime from,
                                          @Param("to") LocalDateTime to,
                                          @Param("projectIds") List<Long> projectIds);

    @Select("""
        <script>
        SELECT COUNT(*)
        FROM deploy_request
        WHERE status IN
        <foreach collection="statuses" item="status" open="(" separator="," close=")">
            #{status}
        </foreach>
        </script>
        """)
    long countByStatusIn(@Param("statuses") List<DeployStatus> statuses);

    @Select("""
        <script>
        SELECT COUNT(*)
        FROM deploy_request
        WHERE status IN
        <foreach collection="statuses" item="status" open="(" separator="," close=")">
            #{status}
        </foreach>
          AND project_id IN
        <foreach collection="projectIds" item="projectId" open="(" separator="," close=")">
            #{projectId}
        </foreach>
        </script>
        """)
    long countByStatusInForProjectIds(@Param("statuses") List<DeployStatus> statuses,
                                      @Param("projectIds") List<Long> projectIds);

    @Select(DEPLOY_REQUEST_SELECT + " ORDER BY r.created_at DESC LIMIT 10")
    @ResultMap("deployRequestResult")
    List<DeployRequest> findTop10WithProjectByOrderByCreatedAtDesc();

    @Select("""
        <script>
        SELECT COUNT(*) > 0
        FROM deploy_request
        WHERE project_id = #{projectId}
          AND status IN
          <foreach collection="statuses" item="status" open="(" separator="," close=")">
              #{status}
          </foreach>
        </script>
        """)
    boolean existsByProjectIdAndStatusIn(@Param("projectId") Long projectId,
                                         @Param("statuses") List<DeployStatus> statuses);

    @Select(DEPLOY_REQUEST_SELECT + " WHERE r.project_id = #{projectId} ORDER BY r.created_at DESC LIMIT 1")
    @ResultMap("deployRequestResult")
    Optional<DeployRequest> findLatestByProjectId(@Param("projectId") Long projectId);

    @Select(DEPLOY_REQUEST_SELECT + """
         WHERE r.project_id = #{projectId}
           AND r.environment = #{environment}
         ORDER BY r.created_at DESC
         LIMIT 1
        """)
    @ResultMap("deployRequestResult")
    Optional<DeployRequest> findLatestByProjectIdAndEnvironment(@Param("projectId") Long projectId,
                                                                @Param("environment") DeployEnvironment environment);

    @Select(DEPLOY_REQUEST_SELECT + """
         WHERE r.project_id = #{projectId}
           AND r.environment = #{environment}
           AND r.status = #{status}
         ORDER BY r.created_at DESC
         LIMIT 1
        """)
    @ResultMap("deployRequestResult")
    Optional<DeployRequest> findLatestByProjectIdAndEnvironmentAndStatus(@Param("projectId") Long projectId,
                                                                         @Param("environment") DeployEnvironment environment,
                                                                         @Param("status") DeployStatus status);

    @Select(DEPLOY_REQUEST_SELECT + """
         WHERE r.project_id = #{projectId}
           AND r.environment = 'TEST'
           AND r.status = 'TEST_CONFIRMED'
         ORDER BY r.test_confirmed_at DESC, r.id DESC
        """)
    @ResultMap("deployRequestResult")
    List<DeployRequest> findConfirmedTestSources(@Param("projectId") Long projectId);

    @Select("""
        <script>
        """ + DEPLOY_REQUEST_SELECT + """
        WHERE r.status IN
        <foreach collection="statuses" item="status" open="(" separator="," close=")">
            #{status}
        </foreach>
        ORDER BY r.created_at DESC
        </script>
        """)
    @ResultMap("deployRequestResult")
    List<DeployRequest> findByStatusInWithProject(@Param("statuses") List<DeployStatus> statuses);

    /** 자동 보정 스캔은 keyset pagination과 영구 차단 필터로 DB/로그 폭주를 막는다. */
    @Select("""
        <script>
        """ + DEPLOY_REQUEST_SELECT + """
        WHERE r.status IN
        <foreach collection="statuses" item="status" open="(" separator="," close=")">
            #{status}
        </foreach>
          AND r.auto_dispatch_blocked_at IS NULL
          AND r.id &gt; #{afterId}
        ORDER BY r.id ASC
        LIMIT #{limit}
        </script>
        """)
    @ResultMap("deployRequestResult")
    List<DeployRequest> findAutoDispatchCandidatesAfterId(
        @Param("statuses") List<DeployStatus> statuses,
        @Param("afterId") long afterId,
        @Param("limit") int limit
    );

    @Select("""
        <script>
        """ + DEPLOY_REQUEST_SELECT + """
        WHERE r.status IN
        <foreach collection="statuses" item="status" open="(" separator="," close=")">
            #{status}
        </foreach>
          AND r.project_id IN
        <foreach collection="projectIds" item="projectId" open="(" separator="," close=")">
            #{projectId}
        </foreach>
        ORDER BY r.created_at DESC
        </script>
        """)
    @ResultMap("deployRequestResult")
    List<DeployRequest> findByStatusInAndProjectIdsWithProject(@Param("statuses") List<DeployStatus> statuses,
                                                               @Param("projectIds") List<Long> projectIds);

    default List<DeployRequest> findByStatusIn(List<DeployStatus> statuses) {
        return findByStatusInWithProject(statuses);
    }

    @Insert("""
        INSERT INTO deploy_request (
            req_no, project_id, environment, deploy_scope, deploy_strategy, selected_server_group_ids, branch, image_tag, image_digest, gitlab_pipeline_id,
            commit_sha, expected_commit_sha, source_mr_iid, mr_evidence_id, immutable_build_ref,
            reason, plan_snapshot_json, plan_snapshot_hash, plan_snapshot_version,
            approval_decision_source, execution_trigger,
            release_verification_status, release_verification_job_id,
            release_verification_repository, release_verified_at, release_verification_evidence_hash, release_manifest_sha256,
            auto_dispatch_blocked_at, auto_dispatch_block_reason,
            status, requested_by_id, test_confirmed_by_id,
            promoted_from_request_id, test_confirmed_at, created_at, updated_at
        ) VALUES (
            #{reqNo}, #{project.id}, #{environment}, #{deployScope}, #{deployStrategy}, #{selectedServerGroupIds,typeHandler=com.portfolio.deployops.repository.LongIdListTypeHandler}, #{branch}, #{imageTag}, #{imageDigest}, #{gitlabPipelineId},
            #{commitSha}, #{expectedCommitSha}, #{sourceMrIid}, #{mrEvidenceId}, #{immutableBuildRef},
            #{reason}, #{planSnapshotJson}, #{planSnapshotHash}, #{planSnapshotVersion},
            #{approvalDecisionSource}, #{executionTrigger},
            #{releaseVerificationStatus}, #{releaseVerificationJobId},
            #{releaseVerificationRepository}, #{releaseVerifiedAt}, #{releaseVerificationEvidenceHash}, #{releaseManifestSha256},
            #{autoDispatchBlockedAt}, #{autoDispatchBlockReason},
            #{status}, #{requestedBy.id}, #{testConfirmedBy.id},
            #{promotedFromRequest.id}, #{testConfirmedAt}, NOW(6), NOW(6)
        )
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(DeployRequest request);

    @Update("""
        UPDATE deploy_request
        SET project_id = #{project.id},
            environment = #{environment},
            deploy_scope = #{deployScope},
            deploy_strategy = #{deployStrategy},
            selected_server_group_ids = #{selectedServerGroupIds,typeHandler=com.portfolio.deployops.repository.LongIdListTypeHandler},
            branch = #{branch},
            image_tag = #{imageTag},
            image_digest = #{imageDigest},
            gitlab_pipeline_id = #{gitlabPipelineId},
            commit_sha = #{commitSha},
            expected_commit_sha = #{expectedCommitSha},
            source_mr_iid = #{sourceMrIid},
            mr_evidence_id = #{mrEvidenceId},
            immutable_build_ref = #{immutableBuildRef},
            reason = #{reason},
            plan_snapshot_json = #{planSnapshotJson},
            plan_snapshot_hash = #{planSnapshotHash},
            plan_snapshot_version = #{planSnapshotVersion},
            approval_decision_source = #{approvalDecisionSource},
            execution_trigger = #{executionTrigger},
            release_verification_status = #{releaseVerificationStatus},
            release_verification_job_id = #{releaseVerificationJobId},
            release_verification_repository = #{releaseVerificationRepository},
            release_verified_at = #{releaseVerifiedAt},
            release_verification_evidence_hash = #{releaseVerificationEvidenceHash},
            release_manifest_sha256 = #{releaseManifestSha256},
            auto_dispatch_blocked_at = #{autoDispatchBlockedAt},
            auto_dispatch_block_reason = #{autoDispatchBlockReason},
            status = #{status},
            requested_by_id = #{requestedBy.id},
            test_confirmed_by_id = #{testConfirmedBy.id},
            promoted_from_request_id = #{promotedFromRequest.id},
            test_confirmed_at = #{testConfirmedAt},
            updated_at = NOW(6)
        WHERE id = #{id}
        """)
    int update(DeployRequest request);

    @Update("UPDATE deploy_request SET executed_by_id = #{userId}, updated_at = NOW(6) WHERE id = #{id}")
    int updateExecutedBy(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 기대 상태일 때만 전이시키고, 실제로 바꾼 행 수를 돌려준다(0 = 이미 남이 가져감).
     *
     * <p>"상태를 읽어서 검사한 뒤 갱신"은 두 요청이 동시에 오면 둘 다 검사를 통과한다.
     * 배포에서는 그게 같은 서버에 배포가 두 번 뜨는 것을 뜻한다 — WAS 는 컨테이너 stop/run 이
     * 교차하고 WEB 은 심볼릭 링크 교체가 뒤엉킨다. 검사와 갱신을 DB 한 문장으로 묶어
     * 먼저 도착한 쪽만 통과시킨다.
     */
    @Update("""
        UPDATE deploy_request
        SET status = #{to}, updated_at = NOW(6)
        WHERE id = #{id} AND status = #{from}
        """)
    int compareAndSetStatus(
        @Param("id") Long id,
        @Param("from") DeployStatus from,
        @Param("to") DeployStatus to
    );

    @Update("""
        UPDATE deploy_request
        SET status = 'CANCELLED', withdrawal_reason = #{reason}, updated_at = NOW(6)
        WHERE id = #{id} AND status = #{expected}
          AND status IN ('TEST_REQUESTED', 'APPROVAL_PENDING', 'APPROVAL_COMPLETED')
        """)
    int withdrawPending(@Param("id") Long id, @Param("expected") DeployStatus expected,
                        @Param("reason") String reason);

    @Update("""
        UPDATE deploy_request
        SET status = #{to}, failure_reason = #{failureReason}, updated_at = NOW(6)
        WHERE id = #{id} AND status = #{from}
        """)
    int compareAndSetFailureStatus(
        @Param("id") Long id,
        @Param("from") DeployStatus from,
        @Param("to") DeployStatus to,
        @Param("failureReason") String failureReason
    );

    @Update("""
        UPDATE deploy_request
        SET status = #{to}, approval_decision_source = #{source}, updated_at = NOW(6)
        WHERE id = #{id} AND status = #{from}
        """)
    int compareAndSetApprovalStatus(
        @Param("id") Long id,
        @Param("from") DeployStatus from,
        @Param("to") DeployStatus to,
        @Param("source") com.portfolio.deployops.domain.ApprovalDecisionSource source
    );

    @Update("""
        UPDATE deploy_request
        SET status = #{to}, execution_trigger = #{trigger}, updated_at = NOW(6),
            target_attempt_tracking_started_at = CASE
                WHEN #{from} IN ('TEST_REQUESTED', 'APPROVAL_COMPLETED')
                THEN COALESCE(target_attempt_tracking_started_at, NOW(6))
                ELSE target_attempt_tracking_started_at END
        WHERE id = #{id} AND status = #{from}
        """)
    int compareAndSetExecutionStart(
        @Param("id") Long id,
        @Param("from") DeployStatus from,
        @Param("to") DeployStatus to,
        @Param("trigger") com.portfolio.deployops.domain.ExecutionTrigger trigger
    );

    @Update("""
        UPDATE deploy_request
        SET auto_dispatch_blocked_at = NOW(6),
            auto_dispatch_block_reason = #{reason},
            updated_at = NOW(6)
        WHERE id = #{id}
          AND status = #{expectedStatus}
          AND auto_dispatch_blocked_at IS NULL
        """)
    int blockAutomaticDispatch(
        @Param("id") Long id,
        @Param("expectedStatus") DeployStatus expectedStatus,
        @Param("reason") String reason
    );

    default DeployRequest save(DeployRequest request) {
        if (request.getId() == null) {
            insert(request);
        } else {
            update(request);
        }
        return request;
    }

    default Page<DeployRequest> findWithFilter(DeployStatus status,
                                               Long projectId,
                                               LocalDateTime from,
                                               LocalDateTime to,
                                               Pageable pageable) {
        long total = countWithFilter(status, projectId, from, to);
        List<DeployRequest> content = findWithFilterPage(
            status, projectId, from, to, pageable.getPageSize(), pageable.getOffset()
        );
        return new PageImpl<>(content, pageable, total);
    }

    default Page<DeployRequest> findWithFilterForProjectIds(DeployStatus status,
                                                            List<Long> projectIds,
                                                            LocalDateTime from,
                                                            LocalDateTime to,
                                                            Pageable pageable) {
        long total = countWithFilterForProjectIds(status, projectIds, from, to);
        List<DeployRequest> content = findWithFilterPageForProjectIds(
            status, projectIds, from, to, pageable.getPageSize(), pageable.getOffset()
        );
        return new PageImpl<>(content, pageable, total);
    }

    /** Same predicate for count and page; ownership never grants access to another environment. */
    String VISIBLE_OR_OWNED_FILTER = """
        WHERE r.created_at BETWEEN #{from} AND #{to}
        <if test="status != null">AND r.status = #{status}</if>
        <if test="projectId != null">AND r.project_id = #{projectId}</if>
        AND (
          <if test="viewProjectIds != null and !viewProjectIds.isEmpty()">
            r.project_id IN
            <foreach collection="viewProjectIds" item="visibleId" open="(" separator="," close=")">
              #{visibleId}
            </foreach>
            OR
          </if>
          (r.requested_by_id = #{userId} AND EXISTS (
            SELECT 1 FROM project_user_permissions access_grant
            WHERE access_grant.user_id = #{userId}
              AND access_grant.project_id = r.project_id
              AND access_grant.permission = 'DEPLOY_REQUEST'
              AND access_grant.active = TRUE
              AND (access_grant.environment = 'ALL' OR access_grant.environment = r.environment)
          ))
        )
        """;

    @Select("<script>SELECT COUNT(*) FROM deploy_request r " + VISIBLE_OR_OWNED_FILTER + "</script>")
    long countVisibleOrOwned(@Param("status") DeployStatus status, @Param("projectId") Long projectId,
        @Param("viewProjectIds") List<Long> viewProjectIds, @Param("userId") Long userId,
        @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Select("<script>" + DEPLOY_REQUEST_SELECT + VISIBLE_OR_OWNED_FILTER
        + " ORDER BY r.created_at DESC, r.id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    @ResultMap("deployRequestResult")
    List<DeployRequest> findVisibleOrOwnedPage(@Param("status") DeployStatus status,
        @Param("projectId") Long projectId, @Param("viewProjectIds") List<Long> viewProjectIds,
        @Param("userId") Long userId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
        @Param("limit") int limit, @Param("offset") long offset);

    default Page<DeployRequest> findVisibleOrOwnedWithFilter(DeployStatus status, Long projectId,
        List<Long> viewProjectIds, Long userId, LocalDateTime from, LocalDateTime to, Pageable pageable) {
        long total = countVisibleOrOwned(status, projectId, viewProjectIds, userId, from, to);
        List<DeployRequest> rows = findVisibleOrOwnedPage(status, projectId, viewProjectIds, userId,
            from, to, pageable.getPageSize(), pageable.getOffset());
        return new PageImpl<>(rows, pageable, total);
    }
}
