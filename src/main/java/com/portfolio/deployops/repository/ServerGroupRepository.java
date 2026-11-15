package com.portfolio.deployops.repository;

import com.portfolio.deployops.domain.ServerGroup;
import java.util.Collections;
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
public interface ServerGroupRepository {

    @Select("""
        SELECT id, name, environment, rolling_interval_sec, min_available, max_unavailable, observation_seconds, failure_policy, approval_policy_id, was_vip_enabled, was_vip_address, description, display_order
        FROM server_group
        ORDER BY display_order ASC, environment ASC, name ASC
        """)
    @Results(id = "serverGroupResult", value = {
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
        @Result(property = "description", column = "description"),
        @Result(property = "displayOrder", column = "display_order")
    })
    List<ServerGroup> findAll();

    @Select("""
        SELECT id, name, environment, rolling_interval_sec, min_available, max_unavailable, observation_seconds, failure_policy, approval_policy_id, was_vip_enabled, was_vip_address, description, display_order
        FROM server_group
        WHERE id = #{id}
        """)
    @Results(id = "serverGroupByIdResult", value = {
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
        @Result(property = "description", column = "description"),
        @Result(property = "displayOrder", column = "display_order")
    })
    Optional<ServerGroup> findById(Long id);

    @Select("SELECT id, name, environment, rolling_interval_sec, min_available, max_unavailable, observation_seconds, failure_policy, approval_policy_id, was_vip_enabled, was_vip_address, description, display_order FROM server_group WHERE id = #{id} FOR UPDATE")
    @ResultMap("serverGroupByIdResult")
    Optional<ServerGroup> findByIdForUpdate(Long id);

    @Select("""
        <script>
        SELECT id, name, environment, rolling_interval_sec, min_available, max_unavailable, observation_seconds, failure_policy, approval_policy_id, was_vip_enabled, was_vip_address, description, display_order
        FROM server_group
        WHERE id IN
        <foreach collection="ids" item="id" open="(" separator="," close=")">
            #{id}
        </foreach>
        ORDER BY display_order ASC, environment ASC, name ASC
        </script>
        """)
    @Results(id = "serverGroupByIdsResult", value = {
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
        @Result(property = "description", column = "description"),
        @Result(property = "displayOrder", column = "display_order")
    })
    List<ServerGroup> findAllByIds(@Param("ids") List<Long> ids);

    @Insert("""
        INSERT INTO server_group (
            name, environment, rolling_interval_sec, min_available, max_unavailable, observation_seconds, failure_policy, approval_policy_id, was_vip_enabled, was_vip_address, description, display_order
        ) VALUES (
            #{name}, #{environment}, #{rollingIntervalSec}, #{minAvailable}, #{maxUnavailable}, #{observationSeconds}, #{failurePolicy}, #{approvalPolicyId}, #{wasVipEnabled}, #{wasVipAddress}, #{description},
            COALESCE((SELECT next_order FROM (SELECT MAX(display_order) + 1 AS next_order FROM server_group) t), 0)
        )
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ServerGroup serverGroup);

    @Update("""
        UPDATE server_group
        SET name = #{name},
            environment = #{environment},
            rolling_interval_sec = #{rollingIntervalSec},
            min_available = #{minAvailable}, max_unavailable = #{maxUnavailable},
            observation_seconds = #{observationSeconds}, failure_policy = #{failurePolicy},
            approval_policy_id = #{approvalPolicyId}, was_vip_enabled = #{wasVipEnabled}, was_vip_address = #{wasVipAddress},
            description = #{description}
        WHERE id = #{id}
        """)
    int update(ServerGroup serverGroup);

    /** 표시 순서만 바꾼다. 이름·설정을 건드리지 않으므로 순서 변경이 다른 값을 덮어쓸 일이 없다. */
    @Update("UPDATE server_group SET display_order = #{displayOrder} WHERE id = #{id}")
    int updateDisplayOrder(@Param("id") Long id, @Param("displayOrder") int displayOrder);

    @Delete("DELETE FROM server_group WHERE id = #{id}")
    int deleteById(Long id);

    default ServerGroup save(ServerGroup serverGroup) {
        if (serverGroup.getId() == null) {
            insert(serverGroup);
        } else {
            update(serverGroup);
        }
        return serverGroup;
    }

    default void delete(ServerGroup serverGroup) {
        if (serverGroup != null && serverGroup.getId() != null) {
            deleteById(serverGroup.getId());
        }
    }

    default List<ServerGroup> findAllById(Iterable<Long> ids) {
        List<Long> idList = new java.util.ArrayList<>();
        ids.forEach(idList::add);
        return idList.isEmpty() ? Collections.emptyList() : findAllByIds(idList);
    }
}
