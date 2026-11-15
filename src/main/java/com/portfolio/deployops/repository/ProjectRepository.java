package com.portfolio.deployops.repository;

import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.ServerGroup;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProjectRepository {

    String PROJECT_SELECT = """
        SELECT p.id, p.name, p.container_name, p.description, p.gitlab_repo_url, p.gitlab_project_id, p.default_branch,
               p.mr_target_branch, p.mr_assignee_gitlab_username, p.mr_reviewer_gitlab_usernames,
               p.auto_merge_on_approval, p.delete_source_branch_on_merge,
               p.pipeline_type, p.rendering_type, p.build_tool, p.build_config_mode, p.build_file_path, p.build_command,
               p.source_entry_path, p.compiler_options, p.runtime_base_image, p.artifact_path,
               p.dockerfile_path, p.docker_context_path, p.image_repository, p.image_tag_pattern,
               p.nexus_maven_release_repository, p.nexus_maven_snapshot_repository, p.nexus_docker_repository,
               p.health_check_mode, p.health_check_path, p.health_check_port, p.was_vip_port, p.remote_deploy_script_path,
               p.docker_network, p.env_file_path, p.container_ports, p.container_user, p.container_privileged,
               p.container_cap_add, p.container_read_only_root_fs,
               p.active,
               ap.id AS approval_policy_id, ap.name AS approval_policy_name,
               ap.policy_type AS approval_policy_type, ap.allow_self_approval AS approval_policy_allow_self_approval,
               ap.description AS approval_policy_description
        FROM project p
        LEFT JOIN approval_policy ap ON p.approval_policy_id = ap.id
        """;

    @Results(id = "projectResult", value = {
        @Result(property = "id", column = "id", id = true),
        @Result(property = "name", column = "name"),
        @Result(property = "containerName", column = "container_name"),
        @Result(property = "description", column = "description"),
        @Result(property = "gitlabRepoUrl", column = "gitlab_repo_url"),
        @Result(property = "gitlabProjectId", column = "gitlab_project_id"),
        @Result(property = "defaultBranch", column = "default_branch"),
        @Result(property = "mrTargetBranch", column = "mr_target_branch"),
        @Result(property = "mrAssigneeGitlabUsername", column = "mr_assignee_gitlab_username"),
        @Result(property = "mrReviewerGitlabUsernames", column = "mr_reviewer_gitlab_usernames"),
        @Result(property = "autoMergeOnApproval", column = "auto_merge_on_approval"),
        @Result(property = "deleteSourceBranchOnMerge", column = "delete_source_branch_on_merge"),
        @Result(property = "pipelineType", column = "pipeline_type"),
        @Result(property = "renderingType", column = "rendering_type"),
        @Result(property = "buildTool", column = "build_tool"),
        @Result(property = "buildConfigMode", column = "build_config_mode"),
        @Result(property = "buildFilePath", column = "build_file_path"),
        @Result(property = "buildCommand", column = "build_command"),
        @Result(property = "sourceEntryPath", column = "source_entry_path"),
        @Result(property = "compilerOptions", column = "compiler_options"),
        @Result(property = "runtimeBaseImage", column = "runtime_base_image"),
        @Result(property = "artifactPath", column = "artifact_path"),
        @Result(property = "dockerfilePath", column = "dockerfile_path"),
        @Result(property = "dockerContextPath", column = "docker_context_path"),
        @Result(property = "imageRepository", column = "image_repository"),
        @Result(property = "imageTagPattern", column = "image_tag_pattern"),
        @Result(property = "nexusMavenReleaseRepository", column = "nexus_maven_release_repository"),
        @Result(property = "nexusMavenSnapshotRepository", column = "nexus_maven_snapshot_repository"),
        @Result(property = "nexusDockerRepository", column = "nexus_docker_repository"),
        @Result(property = "healthCheckMode", column = "health_check_mode"),
        @Result(property = "healthCheckPath", column = "health_check_path"),
        @Result(property = "healthCheckPort", column = "health_check_port"),
        @Result(property = "wasVipPort", column = "was_vip_port"),
        @Result(property = "remoteDeployScriptPath", column = "remote_deploy_script_path"),
        @Result(property = "dockerNetwork", column = "docker_network"),
        @Result(property = "envFilePath", column = "env_file_path"),
        @Result(property = "containerPorts", column = "container_ports"),
        @Result(property = "containerUser", column = "container_user"),
        @Result(property = "containerPrivileged", column = "container_privileged"),
        @Result(property = "containerCapAdd", column = "container_cap_add"),
        @Result(property = "containerReadOnlyRootFs", column = "container_read_only_root_fs"),
        @Result(property = "active", column = "active"),
        @Result(property = "approvalPolicy.id", column = "approval_policy_id"),
        @Result(property = "approvalPolicy.name", column = "approval_policy_name"),
        @Result(property = "approvalPolicy.policyType", column = "approval_policy_type"),
        @Result(property = "approvalPolicy.allowSelfApproval", column = "approval_policy_allow_self_approval"),
        @Result(property = "approvalPolicy.description", column = "approval_policy_description")
    })
    @Select(PROJECT_SELECT + " WHERE p.active = b'1' ORDER BY p.name ASC")
    List<Project> findByActiveTrue();

    @Select("""
        <script>
        """ + PROJECT_SELECT + """
        WHERE p.active = b'1'
          AND p.id IN
          <foreach collection="projectIds" item="projectId" open="(" separator="," close=")">
              #{projectId}
          </foreach>
        ORDER BY p.name ASC
        </script>
        """)
    @ResultMap("projectResult")
    List<Project> findActiveByIds(@Param("projectIds") List<Long> projectIds);

    @Select(PROJECT_SELECT + " ORDER BY p.name ASC")
    @ResultMap("projectResult")
    List<Project> findAll();

    @Select(PROJECT_SELECT + " WHERE p.gitlab_project_id = #{gitlabProjectId} ORDER BY p.id ASC LIMIT 1")
    @ResultMap("projectResult")
    java.util.Optional<Project> findByGitlabProjectId(Long gitlabProjectId);

    @Select("""
        <script>
        """ + PROJECT_SELECT + """
        WHERE p.id IN
          <foreach collection="projectIds" item="projectId" open="(" separator="," close=")">
              #{projectId}
          </foreach>
        ORDER BY p.name ASC
        </script>
        """)
    @ResultMap("projectResult")
    List<Project> findAllByIds(@Param("projectIds") List<Long> projectIds);

    @Select(PROJECT_SELECT + " WHERE p.id = #{id}")
    @ResultMap("projectResult")
    Optional<Project> findById(Long id);

    /** 같은 프로젝트의 요청 생성 check/insert 구간을 트랜잭션별로 직렬화한다. */
    @Select("SELECT id FROM project WHERE id = #{id} FOR UPDATE")
    Long lockById(Long id);

    /** One-time identity assignment; ordinary project updates never change this value. */
    @Update("UPDATE project SET container_name = #{containerName} WHERE id = #{id} AND container_name IS NULL")
    int initializeContainerName(@Param("id") Long id, @Param("containerName") String containerName);

    /** Include inactive projects: deactivation does not remove their existing containers. */
    @Select(PROJECT_SELECT + """
        WHERE p.id IN (
            SELECT psg.project_id FROM project_server_groups psg
            JOIN server s ON s.server_group_id = psg.server_group_id
            WHERE s.id = #{serverId} OR s.ip_address = #{ipAddress}
        )
        """)
    @ResultMap("projectResult")
    List<Project> findSharingTargetServer(@Param("serverId") Long serverId, @Param("ipAddress") String ipAddress);

    default Optional<Project> findByIdWithDetails(Long id) {
        return findById(id);
    }

    @Select("SELECT COUNT(*) > 0 FROM project WHERE name = #{name}")
    boolean existsByName(String name);

    @Select("""
        SELECT sg.id, sg.name, sg.environment, sg.rolling_interval_sec, sg.min_available, sg.max_unavailable, sg.observation_seconds, sg.failure_policy, sg.approval_policy_id, sg.was_vip_enabled, sg.was_vip_address, sg.description
        FROM project_server_groups psg
        JOIN server_group sg ON psg.server_group_id = sg.id
        WHERE psg.project_id = #{projectId}
        ORDER BY sg.environment ASC, sg.name ASC
        """)
    @Results(id = "projectServerGroupResult", value = {
        @Result(property = "id", column = "id", id = true),
        @Result(property = "name", column = "name"),
        @Result(property = "environment", column = "environment"),
        @Result(property = "rollingIntervalSec", column = "rolling_interval_sec"),
        @Result(property = "minAvailable", column = "min_available"),
        @Result(property = "maxUnavailable", column = "max_unavailable"),
        @Result(property = "observationSeconds", column = "observation_seconds"),
        @Result(property = "failurePolicy", column = "failure_policy"),
        @Result(property = "approvalPolicyId", column = "approval_policy_id"),
        @Result(property = "wasVipEnabled", column = "was_vip_enabled"),
        @Result(property = "wasVipAddress", column = "was_vip_address"),
        @Result(property = "description", column = "description")
    })
    List<ServerGroup> findServerGroupsByProjectId(Long projectId);

    @Insert("""
        INSERT INTO project (
            active, default_branch, description, gitlab_project_id, gitlab_repo_url, name,
            mr_target_branch, mr_assignee_gitlab_username, mr_reviewer_gitlab_usernames,
            auto_merge_on_approval, delete_source_branch_on_merge,
            pipeline_type, rendering_type, approval_policy_id, artifact_path, build_command, build_file_path, build_tool,
            build_config_mode, source_entry_path, compiler_options, runtime_base_image,
            docker_context_path, dockerfile_path, health_check_mode, health_check_path, health_check_port, was_vip_port,
            image_repository, image_tag_pattern, nexus_docker_repository, nexus_maven_release_repository,
            nexus_maven_snapshot_repository, remote_deploy_script_path, docker_network, env_file_path, container_ports,
            container_user, container_privileged, container_cap_add, container_read_only_root_fs
        ) VALUES (
            #{active}, #{defaultBranch}, #{description}, #{gitlabProjectId}, #{gitlabRepoUrl}, #{name},
            #{mrTargetBranch}, #{mrAssigneeGitlabUsername}, #{mrReviewerGitlabUsernames},
            #{autoMergeOnApproval}, #{deleteSourceBranchOnMerge},
            #{pipelineType}, #{renderingType}, #{approvalPolicy.id}, #{artifactPath}, #{buildCommand}, #{buildFilePath}, #{buildTool},
            #{buildConfigMode}, #{sourceEntryPath}, #{compilerOptions}, #{runtimeBaseImage},
            #{dockerContextPath}, #{dockerfilePath}, #{healthCheckMode}, #{healthCheckPath}, #{healthCheckPort}, #{wasVipPort},
            #{imageRepository}, #{imageTagPattern}, #{nexusDockerRepository}, #{nexusMavenReleaseRepository},
            #{nexusMavenSnapshotRepository}, #{remoteDeployScriptPath}, #{dockerNetwork}, #{envFilePath}, #{containerPorts},
            #{containerUser}, #{containerPrivileged}, #{containerCapAdd}, #{containerReadOnlyRootFs}
        )
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Project project);

    @Update("""
        UPDATE project
        SET active = #{active},
            default_branch = #{defaultBranch},
            description = #{description},
            gitlab_project_id = #{gitlabProjectId},
            gitlab_repo_url = #{gitlabRepoUrl},
            mr_target_branch = #{mrTargetBranch},
            mr_assignee_gitlab_username = #{mrAssigneeGitlabUsername},
            mr_reviewer_gitlab_usernames = #{mrReviewerGitlabUsernames},
            auto_merge_on_approval = #{autoMergeOnApproval},
            delete_source_branch_on_merge = #{deleteSourceBranchOnMerge},
            name = #{name},
            pipeline_type = #{pipelineType},
            rendering_type = #{renderingType},
            approval_policy_id = #{approvalPolicy.id},
            artifact_path = #{artifactPath},
            build_command = #{buildCommand},
            build_file_path = #{buildFilePath},
            build_tool = #{buildTool},
            build_config_mode = #{buildConfigMode},
            docker_context_path = #{dockerContextPath},
            dockerfile_path = #{dockerfilePath},
            source_entry_path = #{sourceEntryPath},
            compiler_options = #{compilerOptions},
            runtime_base_image = #{runtimeBaseImage},
            health_check_mode = #{healthCheckMode},
            health_check_path = #{healthCheckPath},
            health_check_port = #{healthCheckPort}, was_vip_port = #{wasVipPort},
            image_repository = #{imageRepository},
            image_tag_pattern = #{imageTagPattern},
            nexus_docker_repository = #{nexusDockerRepository},
            nexus_maven_release_repository = #{nexusMavenReleaseRepository},
            nexus_maven_snapshot_repository = #{nexusMavenSnapshotRepository},
            remote_deploy_script_path = #{remoteDeployScriptPath},
            docker_network = #{dockerNetwork},
            env_file_path = #{envFilePath},
            container_ports = #{containerPorts},
            container_user = #{containerUser},
            container_privileged = #{containerPrivileged},
            container_cap_add = #{containerCapAdd},
            container_read_only_root_fs = #{containerReadOnlyRootFs}
        WHERE id = #{id}
        """)
    int update(Project project);

    @Delete("DELETE FROM project_server_groups WHERE project_id = #{projectId}")
    int deleteServerGroupMappings(Long projectId);

    /**
     * 이 서버 그룹을 배포 대상으로 쓰는 프로젝트 이름들 — 서버 그룹 삭제 전 확인용.
     *
     * <p>{@code fk_project_server_groups_server_group} 때문에 하나라도 있으면 DELETE 가 실패한다.
     * 개수만 세지 않고 이름을 돌려주는 이유는, 운영자가 "무엇을 먼저 손봐야 하는지" 알아야
     * 그 화면에서 다음 행동을 할 수 있기 때문이다.
     */
    @Select("""
        SELECT p.name
        FROM project_server_groups psg
        JOIN project p ON p.id = psg.project_id
        WHERE psg.server_group_id = #{serverGroupId}
        ORDER BY p.name
        """)
    List<String> findProjectNamesByServerGroupId(Long serverGroupId);

    @Insert("""
        INSERT INTO project_server_groups (
            project_id, server_group_id
        ) VALUES (
            #{projectId}, #{serverGroupId}
        )
        """)
    int insertServerGroupMapping(@Param("projectId") Long projectId, @Param("serverGroupId") Long serverGroupId);

    default Project save(Project project) {
        if (project.getId() == null) {
            insert(project);
        } else {
            update(project);
        }
        return project;
    }

    default void replaceServerGroupMappings(Long projectId, List<Long> serverGroupIds) {
        deleteServerGroupMappings(projectId);
        if (serverGroupIds == null) {
            return;
        }
        for (Long serverGroupId : serverGroupIds) {
            insertServerGroupMapping(projectId, serverGroupId);
        }
    }
}
