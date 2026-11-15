package com.portfolio.deployops.repository;

import com.portfolio.deployops.domain.ProjectImageHistory;
import com.portfolio.deployops.domain.ProjectImageHistoryStatus;
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
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProjectImageHistoryRepository {

    @Select("""
        SELECT DISTINCT gitlab_pipeline_id, LOWER(TRIM(commit_sha)) AS commit_sha
        FROM project_image_history
        WHERE project_id = #{projectId} AND image_repository = #{repository}
          AND image_tag = #{tag} AND image_digest = #{digest}
          AND gitlab_pipeline_id > 0 AND commit_sha IS NOT NULL AND TRIM(commit_sha) <> ''
        ORDER BY gitlab_pipeline_id, commit_sha LIMIT 2
        """)
    @ResultMap("projectImageHistoryResult")
    List<ProjectImageHistory> findExactImageProvenance(
        @Param("projectId") Long projectId, @Param("repository") String repository,
        @Param("tag") String tag, @Param("digest") String digest);

    @Select("""
        SELECT history.id, history.project_id, history.deploy_request_id, history.environment,
               history.branch, history.image_repository, history.image_tag, history.image_digest,
               history.gitlab_pipeline_id, history.commit_sha, history.history_status,
               history.created_at, history.updated_at,
               source.deploy_scope AS source_deploy_scope,
               source.deploy_strategy AS source_deploy_strategy
        FROM project_image_history history
        LEFT JOIN deploy_request source ON source.id = history.deploy_request_id
        WHERE history.id = #{id}
        """)
    @ResultMap("projectImageHistoryResult")
    Optional<ProjectImageHistory> findById(Long id);

    @Select("""
        SELECT id, project_id, deploy_request_id, environment, branch, image_repository, image_tag,
               image_digest, gitlab_pipeline_id, commit_sha, history_status, created_at, updated_at
        FROM project_image_history
        WHERE project_id = #{projectId}
        ORDER BY created_at DESC, id DESC
        LIMIT #{limit}
        """)
    @Results(id = "projectImageHistoryResult", value = {
        @Result(property = "id", column = "id", id = true),
        @Result(property = "project.id", column = "project_id"),
        @Result(property = "deployRequest.id", column = "deploy_request_id"),
        @Result(property = "deployRequest.deployScope", column = "source_deploy_scope"),
        @Result(property = "deployRequest.deployStrategy", column = "source_deploy_strategy"),
        @Result(property = "environment", column = "environment"),
        @Result(property = "branch", column = "branch"),
        @Result(property = "imageRepository", column = "image_repository"),
        @Result(property = "imageTag", column = "image_tag"),
        @Result(property = "imageDigest", column = "image_digest"),
        @Result(property = "gitlabPipelineId", column = "gitlab_pipeline_id"),
        @Result(property = "commitSha", column = "commit_sha"),
        @Result(property = "historyStatus", column = "history_status"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
    })
    List<ProjectImageHistory> findRecentByProjectId(@Param("projectId") Long projectId, @Param("limit") int limit);

    /** 현재 이미지와 화면에 노출되는 프로젝트·환경별 최근 복구 이력의 digest를 보호한다. */
    @Select("""
        SELECT protected.image_digest FROM (
          SELECT h.image_digest
          FROM project_image_history h
          WHERE h.image_repository = #{imageRepository}
            AND h.history_status IN ('TEST_DEPLOYED','PROD_DEPLOYED','ROLLED_BACK')
            AND h.image_digest IS NOT NULL AND h.image_digest <> ''
            AND h.id = (
              SELECT h2.id FROM project_image_history h2
              WHERE h2.project_id = h.project_id AND h2.environment = h.environment
                AND h2.image_repository = h.image_repository
                AND h2.history_status IN ('TEST_DEPLOYED','PROD_DEPLOYED','ROLLED_BACK')
              ORDER BY h2.updated_at DESC, h2.id DESC LIMIT 1
            )
          UNION
          SELECT recent.image_digest FROM (
            SELECT image_digest,
                   ROW_NUMBER() OVER (
                     PARTITION BY project_id, environment
                     ORDER BY created_at DESC, id DESC
                   ) AS history_rank
            FROM project_image_history
            WHERE image_repository = #{imageRepository}
              AND history_status IN ('TEST_DEPLOYED','PROD_DEPLOYED','ROLLED_BACK')
              AND image_digest IS NOT NULL AND image_digest <> ''
          ) recent
          WHERE recent.history_rank <= #{limit}
        ) protected
        """)
    List<String> findProtectedDigests(@Param("imageRepository") String imageRepository, @Param("limit") int limit);

    @Select("""
        SELECT id, project_id, deploy_request_id, environment, branch, image_repository, image_tag,
               image_digest, gitlab_pipeline_id, commit_sha, history_status, created_at, updated_at
        FROM project_image_history
        WHERE deploy_request_id = #{deployRequestId}
          AND history_status = #{historyStatus}
        LIMIT 1
        """)
    @ResultMap("projectImageHistoryResult")
    Optional<ProjectImageHistory> findByDeployRequestIdAndHistoryStatus(
        @Param("deployRequestId") Long deployRequestId,
        @Param("historyStatus") ProjectImageHistoryStatus historyStatus
    );

    @SelectProvider(type = ProjectImageHistorySqlProvider.class, method = "buildFindLatestStableSql")
    @ResultMap("projectImageHistoryResult")
    Optional<ProjectImageHistory> findLatestStableByProjectIdAndEnvironmentAndStatuses(
        @Param("projectId") Long projectId,
        @Param("environment") String environment,
        @Param("statuses") List<String> statuses
    );

    @Insert("""
        INSERT INTO project_image_history (
            project_id, deploy_request_id, environment, branch, image_repository, image_tag,
            image_digest, gitlab_pipeline_id, commit_sha, history_status, created_at, updated_at
        ) VALUES (
            #{project.id}, #{deployRequest.id}, #{environment}, #{branch}, #{imageRepository}, #{imageTag},
            #{imageDigest}, #{gitlabPipelineId}, #{commitSha}, #{historyStatus}, NOW(6), NOW(6)
        )
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ProjectImageHistory history);

    @Update("""
        UPDATE project_image_history
        SET project_id = #{project.id},
            deploy_request_id = #{deployRequest.id},
            environment = #{environment},
            branch = #{branch},
            image_repository = #{imageRepository},
            image_tag = #{imageTag},
            image_digest = #{imageDigest},
            gitlab_pipeline_id = #{gitlabPipelineId},
            commit_sha = #{commitSha},
            history_status = #{historyStatus},
            updated_at = NOW(6)
        WHERE id = #{id}
        """)
    int update(ProjectImageHistory history);

    default ProjectImageHistory save(ProjectImageHistory history) {
        if (history.getId() == null) {
            insert(history);
        } else {
            update(history);
        }
        return history;
    }

    class ProjectImageHistorySqlProvider {

        public String buildFindLatestStableSql(java.util.Map<String, Object> params) {
            @SuppressWarnings("unchecked")
            List<String> statuses = (List<String>) params.get("statuses");
            StringBuilder sql = new StringBuilder("""
                SELECT id, project_id, deploy_request_id, environment, branch, image_repository, image_tag,
                       image_digest, gitlab_pipeline_id, commit_sha, history_status, created_at, updated_at
                FROM project_image_history
                WHERE project_id = #{projectId}
                  AND environment = #{environment}
                """);

            if (statuses != null && !statuses.isEmpty()) {
                sql.append(" AND history_status IN (");
                for (int i = 0; i < statuses.size(); i++) {
                    if (i > 0) {
                        sql.append(", ");
                    }
                    sql.append("#{statuses[").append(i).append("]}");
                }
                sql.append(')');
            }

            sql.append(" ORDER BY created_at DESC, id DESC LIMIT 1");
            return sql.toString();
        }
    }
}
