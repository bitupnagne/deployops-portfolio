package com.portfolio.deployops.repository;

import com.portfolio.deployops.domain.DeployComponent;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface DeployComponentRepository {

    String COMPONENT_SELECT = """
        SELECT id, project_id, code, display_name, role, artifact_type, image_repository, web_root,
               build_tool, dockerfile_path, docker_context_path, build_command, artifact_path,
               health_check_mode, health_check_path, health_check_port, proxy_health_host, proxy_health_host_test, proxy_health_host_prod, container_user, container_privileged,
               container_cap_add, container_read_only_root_fs, deploy_order, active
        FROM deploy_component
        """;

    @Results(id = "deployComponentResult", value = {
        @Result(property = "id", column = "id", id = true),
        @Result(property = "projectId", column = "project_id"),
        @Result(property = "code", column = "code"),
        @Result(property = "displayName", column = "display_name"),
        @Result(property = "role", column = "role"),
        @Result(property = "artifactType", column = "artifact_type"),
        @Result(property = "imageRepository", column = "image_repository"),
        @Result(property = "webRoot", column = "web_root"),
        @Result(property = "buildTool", column = "build_tool"),
        @Result(property = "dockerfilePath", column = "dockerfile_path"),
        @Result(property = "dockerContextPath", column = "docker_context_path"),
        @Result(property = "buildCommand", column = "build_command"),
        @Result(property = "artifactPath", column = "artifact_path"),
        @Result(property = "healthCheckMode", column = "health_check_mode"),
        @Result(property = "healthCheckPath", column = "health_check_path"),
        @Result(property = "healthCheckPort", column = "health_check_port"),
        @Result(property = "proxyHealthHost", column = "proxy_health_host"),
        @Result(property = "proxyHealthHostTest", column = "proxy_health_host_test"),
        @Result(property = "proxyHealthHostProd", column = "proxy_health_host_prod"),
        @Result(property = "containerUser", column = "container_user"),
        @Result(property = "containerPrivileged", column = "container_privileged"),
        @Result(property = "containerCapAdd", column = "container_cap_add"),
        @Result(property = "containerReadOnlyRootFs", column = "container_read_only_root_fs"),
        @Result(property = "deployOrder", column = "deploy_order"),
        @Result(property = "active", column = "active")
    })
    @Select(COMPONENT_SELECT + " WHERE project_id = #{projectId} ORDER BY deploy_order ASC, id ASC")
    List<DeployComponent> findByProjectId(Long projectId);

    @Select(COMPONENT_SELECT + " WHERE project_id = #{projectId} AND active = b'1' ORDER BY deploy_order ASC, id ASC")
    @ResultMap("deployComponentResult")
    List<DeployComponent> findActiveByProjectId(Long projectId);

    @Select(COMPONENT_SELECT + " WHERE id = #{id}")
    @ResultMap("deployComponentResult")
    Optional<DeployComponent> findById(Long id);

    @Insert("""
        INSERT INTO deploy_component (
            project_id, code, display_name, role, artifact_type, image_repository, web_root,
            build_tool, dockerfile_path, docker_context_path, build_command, artifact_path,
            health_check_mode, health_check_path, health_check_port, proxy_health_host, proxy_health_host_test, proxy_health_host_prod, container_user, container_privileged,
            container_cap_add, container_read_only_root_fs, deploy_order, active
        ) VALUES (
            #{projectId}, #{code}, #{displayName}, #{role}, #{artifactType}, #{imageRepository}, #{webRoot},
            #{buildTool}, #{dockerfilePath}, #{dockerContextPath}, #{buildCommand}, #{artifactPath},
            #{healthCheckMode}, #{healthCheckPath}, #{healthCheckPort}, #{proxyHealthHost}, #{proxyHealthHostTest}, #{proxyHealthHostProd}, #{containerUser}, #{containerPrivileged},
            #{containerCapAdd}, #{containerReadOnlyRootFs}, #{deployOrder}, #{active}
        )
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(DeployComponent component);

    @Update("""
        UPDATE deploy_component SET
            code = #{code},
            display_name = #{displayName},
            role = #{role},
            artifact_type = #{artifactType},
            image_repository = #{imageRepository},
            web_root = #{webRoot},
            build_tool = #{buildTool},
            dockerfile_path = #{dockerfilePath},
            docker_context_path = #{dockerContextPath},
            build_command = #{buildCommand},
            artifact_path = #{artifactPath},
            health_check_mode = #{healthCheckMode},
            health_check_path = #{healthCheckPath},
            health_check_port = #{healthCheckPort},
            proxy_health_host = #{proxyHealthHost},
            proxy_health_host_test = #{proxyHealthHostTest},
            proxy_health_host_prod = #{proxyHealthHostProd},
            container_user = #{containerUser},
            container_privileged = #{containerPrivileged},
            container_cap_add = #{containerCapAdd},
            container_read_only_root_fs = #{containerReadOnlyRootFs},
            deploy_order = #{deployOrder},
            active = #{active}
        WHERE id = #{id}
        """)
    int update(DeployComponent component);

    @Delete("DELETE FROM deploy_component WHERE id = #{id}")
    int deleteById(Long id);

    @Delete("DELETE FROM deploy_component WHERE project_id = #{projectId}")
    int deleteByProjectId(Long projectId);

    default DeployComponent save(DeployComponent component) {
        if (component.getId() == null) {
            insert(component);
        } else {
            update(component);
        }
        return component;
    }
}
