package com.portfolio.deployops.repository;

import com.portfolio.deployops.domain.ProjectPermissionEnvironment;
import com.portfolio.deployops.domain.ProjectPermissionType;
import com.portfolio.deployops.domain.ProjectUserPermission;
import java.util.List;
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
public interface ProjectUserPermissionRepository {

    String SELECT_PERMISSIONS = """
        SELECT pup.id, pup.project_id, pup.user_id, pup.permission, pup.environment,
               pup.active, pup.created_at, pup.updated_at,
               p.name AS project_name,
               u.login_id AS user_login_id, u.name AS user_name, u.role AS user_role
        FROM project_user_permissions pup
        JOIN project p ON pup.project_id = p.id
        JOIN users u ON pup.user_id = u.id
        """;

    @Results(id = "projectUserPermissionResult", value = {
        @Result(property = "id", column = "id", id = true),
        @Result(property = "project.id", column = "project_id"),
        @Result(property = "project.name", column = "project_name"),
        @Result(property = "user.id", column = "user_id"),
        @Result(property = "user.loginId", column = "user_login_id"),
        @Result(property = "user.name", column = "user_name"),
        @Result(property = "user.role", column = "user_role"),
        @Result(property = "permission", column = "permission"),
        @Result(property = "environment", column = "environment"),
        @Result(property = "active", column = "active"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
    })
    @Select(SELECT_PERMISSIONS + " WHERE pup.project_id = #{projectId} ORDER BY u.name ASC, pup.permission ASC")
    List<ProjectUserPermission> findByProjectId(@Param("projectId") Long projectId);

    @Select(SELECT_PERMISSIONS + " WHERE pup.user_id = #{userId} ORDER BY p.name ASC, pup.permission ASC")
    @ResultMap("projectUserPermissionResult")
    List<ProjectUserPermission> findByUserId(@Param("userId") Long userId);

    @Select(SELECT_PERMISSIONS + " WHERE pup.project_id = #{projectId} AND pup.user_id = #{userId} ORDER BY pup.permission, pup.environment")
    @ResultMap("projectUserPermissionResult")
    List<ProjectUserPermission> findByProjectIdAndUserId(@Param("projectId") Long projectId, @Param("userId") Long userId);

    @Select(SELECT_PERMISSIONS + " WHERE pup.project_id = #{projectId} AND pup.user_id = #{userId} ORDER BY pup.permission, pup.environment FOR UPDATE")
    @ResultMap("projectUserPermissionResult")
    List<ProjectUserPermission> findByProjectIdAndUserIdForUpdate(@Param("projectId") Long projectId, @Param("userId") Long userId);

    @Update("""
        UPDATE project_user_permissions SET active = #{active}, updated_at = NOW(6)
        WHERE project_id = #{projectId} AND user_id = #{userId}
          AND permission = #{permission} AND environment = #{environment}
        """)
    int setActive(@Param("projectId") Long projectId, @Param("userId") Long userId,
                  @Param("permission") ProjectPermissionType permission,
                  @Param("environment") ProjectPermissionEnvironment environment, @Param("active") boolean active);

    @Select("""
        SELECT COUNT(*) > 0
        FROM project_user_permissions
        WHERE user_id = #{userId}
          AND project_id = #{projectId}
          AND permission = #{permission}
          AND active = b'1'
          AND (environment = 'ALL' OR environment = #{environment})
        """)
    boolean existsActivePermission(
        @Param("userId") Long userId,
        @Param("projectId") Long projectId,
        @Param("permission") ProjectPermissionType permission,
        @Param("environment") ProjectPermissionEnvironment environment
    );

    @Select("""
        SELECT DISTINCT project_id
        FROM project_user_permissions
        WHERE user_id = #{userId}
          AND permission = #{permission}
          AND active = b'1'
          AND (environment = 'ALL' OR environment = #{environment})
        ORDER BY project_id ASC
        """)
    List<Long> findProjectIdsByUserAndPermission(
        @Param("userId") Long userId,
        @Param("permission") ProjectPermissionType permission,
        @Param("environment") ProjectPermissionEnvironment environment
    );

    @Select("""
        SELECT DISTINCT user_id
        FROM project_user_permissions
        WHERE project_id = #{projectId}
          AND permission = #{permission}
          AND active = b'1'
          AND (environment = 'ALL' OR environment = #{environment})
        ORDER BY user_id ASC
        """)
    List<Long> findUserIdsByProjectAndPermission(
        @Param("projectId") Long projectId,
        @Param("permission") ProjectPermissionType permission,
        @Param("environment") ProjectPermissionEnvironment environment
    );

    @Insert("""
        INSERT INTO project_user_permissions (
            project_id, user_id, permission, environment, active, created_at, updated_at
        ) VALUES (
            #{project.id}, #{user.id}, #{permission}, #{environment}, #{active}, NOW(6), NOW(6)
        )
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ProjectUserPermission permission);

    @Update("""
        UPDATE project_user_permissions
        SET permission = #{permission},
            environment = #{environment},
            active = #{active},
            updated_at = NOW(6)
        WHERE id = #{id}
        """)
    int update(ProjectUserPermission permission);

    @Delete("DELETE FROM project_user_permissions WHERE project_id = #{projectId} AND user_id = #{userId}")
    int deleteByProjectIdAndUserId(@Param("projectId") Long projectId, @Param("userId") Long userId);

    default ProjectUserPermission save(ProjectUserPermission permission) {
        if (permission.getId() == null) {
            insert(permission);
        } else {
            update(permission);
        }
        return permission;
    }
}
