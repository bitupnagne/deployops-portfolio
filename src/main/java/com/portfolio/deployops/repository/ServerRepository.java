package com.portfolio.deployops.repository;

import com.portfolio.deployops.domain.Server;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ServerRepository {

    /** 서버 구성 변경과 그룹 승인 정책 변경을 같은 그룹 행 잠금으로 직렬화한다. */
    @Select("SELECT id FROM server_group ORDER BY id FOR UPDATE")
    List<Long> lockGroupsForServerMutation();

    @Select("""
        SELECT s.id, s.name, s.ip_address, s.environment, s.server_type, s.ssh_port, s.deploy_account,
               s.auth_type, s.control_mode, s.agent_binding_id, s.deploy_path, s.memo, s.last_checked_at, s.status, s.order_index, s.created_at,
               sg.id AS sg_id, sg.name AS sg_name, sg.environment AS sg_environment,
               sg.rolling_interval_sec AS sg_rolling_interval_sec, sg.min_available AS sg_min_available,
               sg.max_unavailable AS sg_max_unavailable, sg.observation_seconds AS sg_observation_seconds,
               sg.failure_policy AS sg_failure_policy, sg.approval_policy_id AS sg_approval_policy_id, sg.was_vip_enabled AS sg_was_vip_enabled, sg.was_vip_address AS sg_was_vip_address, sg.description AS sg_description,
               s.auto_start, s.startup_service
        FROM server s
        LEFT JOIN server_group sg ON s.server_group_id = sg.id
        ORDER BY s.environment ASC, s.server_type ASC, s.order_index ASC, s.name ASC
        """)
    @Results(id = "serverResult", value = {
        @Result(property = "id", column = "id", id = true),
        @Result(property = "name", column = "name"),
        @Result(property = "ipAddress", column = "ip_address"),
        @Result(property = "environment", column = "environment"),
        @Result(property = "serverType", column = "server_type"),
        @Result(property = "sshPort", column = "ssh_port"),
        @Result(property = "deployAccount", column = "deploy_account"),
        @Result(property = "authType", column = "auth_type"),
        @Result(property = "controlMode", column = "control_mode"),
        @Result(property = "agentBindingId", column = "agent_binding_id"),
        @Result(property = "deployPath", column = "deploy_path"),
        @Result(property = "memo", column = "memo"),
        @Result(property = "lastCheckedAt", column = "last_checked_at"),
        @Result(property = "status", column = "status"),
        @Result(property = "orderIndex", column = "order_index"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "autoStart", column = "auto_start"),
        @Result(property = "startupService", column = "startup_service"),
        @Result(property = "serverGroup.id", column = "sg_id"),
        @Result(property = "serverGroup.name", column = "sg_name"),
        @Result(property = "serverGroup.environment", column = "sg_environment"),
        @Result(property = "serverGroup.rollingIntervalSec", column = "sg_rolling_interval_sec"),
        @Result(property = "serverGroup.minAvailable", column = "sg_min_available"),
        @Result(property = "serverGroup.maxUnavailable", column = "sg_max_unavailable"),
        @Result(property = "serverGroup.observationSeconds", column = "sg_observation_seconds"),
        @Result(property = "serverGroup.failurePolicy", column = "sg_failure_policy"),
        @Result(property = "serverGroup.approvalPolicyId", column = "sg_approval_policy_id"),
        @Result(property = "serverGroup.wasVipEnabled", column = "sg_was_vip_enabled"),
        @Result(property = "serverGroup.wasVipAddress", column = "sg_was_vip_address"),
        @Result(property = "serverGroup.description", column = "sg_description")
    })
    List<Server> findAll();

    @Select("""
        SELECT s.id, s.name, s.ip_address, s.environment, s.server_type, s.ssh_port, s.deploy_account,
               s.auth_type, s.control_mode, s.agent_binding_id, s.deploy_path, s.memo, s.last_checked_at, s.status, s.order_index, s.created_at,
               sg.id AS sg_id, sg.name AS sg_name, sg.environment AS sg_environment,
               sg.rolling_interval_sec AS sg_rolling_interval_sec, sg.min_available AS sg_min_available,
               sg.max_unavailable AS sg_max_unavailable, sg.observation_seconds AS sg_observation_seconds,
               sg.failure_policy AS sg_failure_policy, sg.approval_policy_id AS sg_approval_policy_id, sg.was_vip_enabled AS sg_was_vip_enabled, sg.was_vip_address AS sg_was_vip_address, sg.description AS sg_description,
               s.auto_start, s.startup_service
        FROM server s
        LEFT JOIN server_group sg ON s.server_group_id = sg.id
        WHERE s.id = #{id}
        """)
    @Results(id = "serverByIdResult", value = {
        @Result(property = "id", column = "id", id = true),
        @Result(property = "name", column = "name"),
        @Result(property = "ipAddress", column = "ip_address"),
        @Result(property = "environment", column = "environment"),
        @Result(property = "serverType", column = "server_type"),
        @Result(property = "sshPort", column = "ssh_port"),
        @Result(property = "deployAccount", column = "deploy_account"),
        @Result(property = "authType", column = "auth_type"),
        @Result(property = "controlMode", column = "control_mode"),
        @Result(property = "agentBindingId", column = "agent_binding_id"),
        @Result(property = "deployPath", column = "deploy_path"),
        @Result(property = "memo", column = "memo"),
        @Result(property = "lastCheckedAt", column = "last_checked_at"),
        @Result(property = "status", column = "status"),
        @Result(property = "orderIndex", column = "order_index"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "autoStart", column = "auto_start"),
        @Result(property = "startupService", column = "startup_service"),
        @Result(property = "serverGroup.id", column = "sg_id"),
        @Result(property = "serverGroup.name", column = "sg_name"),
        @Result(property = "serverGroup.environment", column = "sg_environment"),
        @Result(property = "serverGroup.rollingIntervalSec", column = "sg_rolling_interval_sec"),
        @Result(property = "serverGroup.minAvailable", column = "sg_min_available"),
        @Result(property = "serverGroup.maxUnavailable", column = "sg_max_unavailable"),
        @Result(property = "serverGroup.observationSeconds", column = "sg_observation_seconds"),
        @Result(property = "serverGroup.failurePolicy", column = "sg_failure_policy"),
        @Result(property = "serverGroup.approvalPolicyId", column = "sg_approval_policy_id"),
        @Result(property = "serverGroup.wasVipEnabled", column = "sg_was_vip_enabled"),
        @Result(property = "serverGroup.wasVipAddress", column = "sg_was_vip_address"),
        @Result(property = "serverGroup.description", column = "sg_description")
    })
    Optional<Server> findById(Long id);

    @Select("""
        SELECT s.id, s.name, s.ip_address, s.environment, s.server_type, s.ssh_port, s.deploy_account,
               s.auth_type, s.control_mode, s.agent_binding_id, s.deploy_path, s.memo, s.last_checked_at, s.status, s.order_index, s.created_at,
               sg.id AS sg_id, sg.name AS sg_name, sg.environment AS sg_environment,
               sg.rolling_interval_sec AS sg_rolling_interval_sec, sg.min_available AS sg_min_available,
               sg.max_unavailable AS sg_max_unavailable, sg.observation_seconds AS sg_observation_seconds,
               sg.failure_policy AS sg_failure_policy, sg.approval_policy_id AS sg_approval_policy_id, sg.was_vip_enabled AS sg_was_vip_enabled, sg.was_vip_address AS sg_was_vip_address, sg.description AS sg_description,
               s.auto_start, s.startup_service
        FROM server s
        LEFT JOIN server_group sg ON s.server_group_id = sg.id
        WHERE s.server_group_id = #{groupId}
        ORDER BY s.order_index ASC, s.name ASC
        """)
    @Results(id = "serverByGroupResult", value = {
        @Result(property = "id", column = "id", id = true),
        @Result(property = "name", column = "name"),
        @Result(property = "ipAddress", column = "ip_address"),
        @Result(property = "environment", column = "environment"),
        @Result(property = "serverType", column = "server_type"),
        @Result(property = "sshPort", column = "ssh_port"),
        @Result(property = "deployAccount", column = "deploy_account"),
        @Result(property = "authType", column = "auth_type"),
        @Result(property = "controlMode", column = "control_mode"),
        @Result(property = "agentBindingId", column = "agent_binding_id"),
        @Result(property = "deployPath", column = "deploy_path"),
        @Result(property = "memo", column = "memo"),
        @Result(property = "lastCheckedAt", column = "last_checked_at"),
        @Result(property = "status", column = "status"),
        @Result(property = "orderIndex", column = "order_index"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "autoStart", column = "auto_start"),
        @Result(property = "startupService", column = "startup_service"),
        @Result(property = "serverGroup.id", column = "sg_id"),
        @Result(property = "serverGroup.name", column = "sg_name"),
        @Result(property = "serverGroup.environment", column = "sg_environment"),
        @Result(property = "serverGroup.rollingIntervalSec", column = "sg_rolling_interval_sec"),
        @Result(property = "serverGroup.minAvailable", column = "sg_min_available"),
        @Result(property = "serverGroup.maxUnavailable", column = "sg_max_unavailable"),
        @Result(property = "serverGroup.observationSeconds", column = "sg_observation_seconds"),
        @Result(property = "serverGroup.failurePolicy", column = "sg_failure_policy"),
        @Result(property = "serverGroup.approvalPolicyId", column = "sg_approval_policy_id"),
        @Result(property = "serverGroup.wasVipEnabled", column = "sg_was_vip_enabled"),
        @Result(property = "serverGroup.wasVipAddress", column = "sg_was_vip_address"),
        @Result(property = "serverGroup.description", column = "sg_description")
    })
    List<Server> findByServerGroupId(Long groupId);

    @Select("""
        SELECT s.id, s.name, s.ip_address, s.environment, s.server_type, s.ssh_port, s.deploy_account,
               s.auth_type, s.control_mode, s.agent_binding_id, s.deploy_path, s.memo, s.last_checked_at, s.status, s.order_index, s.created_at,
               sg.id AS sg_id, sg.name AS sg_name, sg.environment AS sg_environment,
               sg.rolling_interval_sec AS sg_rolling_interval_sec, sg.min_available AS sg_min_available,
               sg.max_unavailable AS sg_max_unavailable, sg.observation_seconds AS sg_observation_seconds,
               sg.failure_policy AS sg_failure_policy, sg.approval_policy_id AS sg_approval_policy_id, sg.was_vip_enabled AS sg_was_vip_enabled, sg.was_vip_address AS sg_was_vip_address, sg.description AS sg_description,
               s.auto_start, s.startup_service
        FROM server s
        LEFT JOIN server_group sg ON s.server_group_id = sg.id
        ORDER BY s.id FOR UPDATE
        """)
    @org.apache.ibatis.annotations.ResultMap("serverResult")
    List<Server> findAllForMutation();

    @Select("""
        SELECT s.id, s.name, s.ip_address, s.environment, s.server_type, s.ssh_port, s.deploy_account,
               s.auth_type, s.control_mode, s.agent_binding_id, s.deploy_path, s.memo, s.last_checked_at, s.status, s.order_index, s.created_at,
               sg.id AS sg_id, sg.name AS sg_name, sg.environment AS sg_environment,
               sg.rolling_interval_sec AS sg_rolling_interval_sec, sg.min_available AS sg_min_available,
               sg.max_unavailable AS sg_max_unavailable, sg.observation_seconds AS sg_observation_seconds,
               sg.failure_policy AS sg_failure_policy, sg.approval_policy_id AS sg_approval_policy_id, sg.was_vip_enabled AS sg_was_vip_enabled, sg.was_vip_address AS sg_was_vip_address, sg.description AS sg_description,
               s.auto_start, s.startup_service
        FROM server s
        LEFT JOIN server_group sg ON s.server_group_id = sg.id
        WHERE s.id = #{id} FOR UPDATE
        """)
    @org.apache.ibatis.annotations.ResultMap("serverByIdResult")
    Optional<Server> findByIdForMutation(Long id);

    @Insert("""
        INSERT INTO server (
            auth_type, control_mode, deploy_account, deploy_path, environment, ip_address, last_checked_at, memo,
            name, order_index, server_type, ssh_port, status, server_group_id, created_at, auto_start, startup_service
        ) VALUES (
            #{authType}, #{controlMode}, #{deployAccount}, #{deployPath}, #{environment}, #{ipAddress}, #{lastCheckedAt}, #{memo},
            #{name}, #{orderIndex}, #{serverType}, #{sshPort}, #{status}, #{serverGroup.id}, NOW(6), #{autoStart}, #{startupService}
        )
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Server server);

    @Update("""
        UPDATE server
        SET auth_type = #{authType},
            control_mode = #{controlMode},
            deploy_account = #{deployAccount},
            deploy_path = #{deployPath},
            environment = #{environment},
            ip_address = #{ipAddress},
            last_checked_at = #{lastCheckedAt},
            memo = #{memo},
            name = #{name},
            order_index = #{orderIndex},
            server_type = #{serverType},
            ssh_port = #{sshPort},
            status = #{status},
            server_group_id = #{serverGroup.id},
            auto_start = #{autoStart}, startup_service = #{startupService}
        WHERE id = #{id}
        """)
    int update(Server server);

    @Update("UPDATE server SET status = #{status}, last_checked_at = #{lastCheckedAt} WHERE id = #{id}")
    int updateConnectivityStatus(Server server);

    @Delete("DELETE FROM server WHERE id = #{id}")
    int deleteById(Long id);

    default Server save(Server server) {
        if (server.getId() == null) {
            insert(server);
        } else {
            update(server);
        }
        return server;
    }

    default void delete(Server server) {
        if (server != null && server.getId() != null) {
            deleteById(server.getId());
        }
    }
}
