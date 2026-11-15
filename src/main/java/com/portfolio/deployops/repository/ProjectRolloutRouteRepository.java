package com.portfolio.deployops.repository;

import com.portfolio.deployops.domain.DeployEnvironment;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ProjectRolloutRouteRepository {
    record Binding(Long projectId, DeployEnvironment environment, Long webServerId, String policyName) { }

    @Select("SELECT project_id,environment,web_server_id,policy_name FROM project_rollout_route WHERE project_id=#{projectId} AND environment=#{environment} ORDER BY web_server_id,policy_name")
    List<Binding> find(@Param("projectId") Long projectId, @Param("environment") DeployEnvironment environment);

    @Select("SELECT project_id FROM project_rollout_route WHERE environment=#{environment} AND web_server_id=#{webServerId} AND policy_name=#{policyName}")
    Long owner(@Param("environment") DeployEnvironment environment, @Param("webServerId") Long webServerId,
               @Param("policyName") String policyName);

    @Delete("DELETE FROM project_rollout_route WHERE project_id=#{projectId} AND environment=#{environment}")
    int delete(@Param("projectId") Long projectId, @Param("environment") DeployEnvironment environment);

    @Insert("INSERT INTO project_rollout_route(project_id,environment,web_server_id,policy_name) VALUES(#{projectId},#{environment},#{webServerId},#{policyName})")
    int insert(Binding binding);
}
