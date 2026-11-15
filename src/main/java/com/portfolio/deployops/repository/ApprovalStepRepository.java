package com.portfolio.deployops.repository;

import com.portfolio.deployops.domain.ApprovalStep;
import com.portfolio.deployops.domain.User;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ApprovalStepRepository {

    @Select("""
        SELECT id, policy_id, step_order, required_count
        FROM approval_step
        WHERE policy_id = #{policyId}
        ORDER BY step_order ASC, id ASC
        """)
    @Results(id = "approvalStepResult", value = {
        @Result(property = "id", column = "id", id = true),
        @Result(property = "policy.id", column = "policy_id"),
        @Result(property = "stepOrder", column = "step_order"),
        @Result(property = "requiredCount", column = "required_count")
    })
    List<ApprovalStep> findByPolicyId(Long policyId);

    @Select("""
        SELECT u.id, u.login_id, u.password, u.name, u.role, u.active,
               u.last_login_at, u.password_changed_at, u.force_password_change,
               u.created_at, u.gitlab_token, u.gitlab_username
        FROM approval_step_approvers asa
        JOIN users u ON asa.user_id = u.id
        WHERE asa.step_id = #{stepId}
        ORDER BY u.name ASC
        """)
    @Results(id = "approvalStepApproverUserResult", value = {
        @Result(property = "id", column = "id", id = true),
        @Result(property = "loginId", column = "login_id"),
        @Result(property = "password", column = "password"),
        @Result(property = "name", column = "name"),
        @Result(property = "role", column = "role"),
        @Result(property = "active", column = "active"),
        @Result(property = "lastLoginAt", column = "last_login_at"),
        @Result(property = "passwordChangedAt", column = "password_changed_at"),
        @Result(property = "forcePasswordChange", column = "force_password_change"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "gitlabToken", column = "gitlab_token"),
        @Result(property = "gitlabUsername", column = "gitlab_username")
    })
    List<User> findApproversByStepId(Long stepId);

    @Insert("""
        INSERT INTO approval_step (
            policy_id, step_order, required_count
        ) VALUES (
            #{policy.id}, #{stepOrder}, #{requiredCount}
        )
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ApprovalStep step);

    @Insert("""
        INSERT INTO approval_step_approvers (
            step_id, user_id
        ) VALUES (
            #{stepId}, #{userId}
        )
        """)
    int insertApprover(@Param("stepId") Long stepId, @Param("userId") Long userId);

    @Delete("DELETE FROM approval_step_approvers WHERE step_id = #{stepId}")
    int deleteApproversByStepId(Long stepId);

    @Delete("""
        DELETE asa FROM approval_step_approvers asa
        INNER JOIN approval_step aps ON asa.step_id = aps.id
        WHERE aps.policy_id = #{policyId}
        """)
    int deleteApproversByPolicyId(Long policyId);

    @Delete("DELETE FROM approval_step WHERE policy_id = #{policyId}")
    int deleteByPolicyId(Long policyId);
}
