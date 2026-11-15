package com.portfolio.deployops.repository;

import com.portfolio.deployops.domain.ApprovalPolicy;
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
public interface ApprovalPolicyRepository {

    @Select("""
        SELECT id, name, policy_type, allow_self_approval, description
        FROM approval_policy
        ORDER BY id ASC
        """)
    @Results(id = "approvalPolicyResult", value = {
        @Result(property = "id", column = "id", id = true),
        @Result(property = "name", column = "name"),
        @Result(property = "policyType", column = "policy_type"),
        @Result(property = "allowSelfApproval", column = "allow_self_approval"),
        @Result(property = "description", column = "description")
    })
    List<ApprovalPolicy> findAll();

    @Select("""
        SELECT id, name, policy_type, allow_self_approval, description
        FROM approval_policy
        WHERE id = #{id}
        """)
    @Results(id = "approvalPolicyByIdResult", value = {
        @Result(property = "id", column = "id", id = true),
        @Result(property = "name", column = "name"),
        @Result(property = "policyType", column = "policy_type"),
        @Result(property = "allowSelfApproval", column = "allow_self_approval"),
        @Result(property = "description", column = "description")
    })
    Optional<ApprovalPolicy> findById(Long id);

    @Insert("""
        INSERT INTO approval_policy (
            name, policy_type, allow_self_approval, description
        ) VALUES (
            #{name}, #{policyType}, #{allowSelfApproval}, #{description}
        )
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ApprovalPolicy policy);

    @Update("""
        UPDATE approval_policy
        SET name = #{name},
            policy_type = #{policyType},
            allow_self_approval = #{allowSelfApproval},
            description = #{description}
        WHERE id = #{id}
        """)
    int update(ApprovalPolicy policy);

    @Delete("DELETE FROM approval_policy WHERE id = #{id}")
    int deleteById(Long id);

    default ApprovalPolicy save(ApprovalPolicy policy) {
        if (policy.getId() == null) {
            insert(policy);
        } else {
            update(policy);
        }
        return policy;
    }

    default void delete(ApprovalPolicy policy) {
        if (policy != null && policy.getId() != null) {
            deleteById(policy.getId());
        }
    }
}
