package com.portfolio.deployops.repository;

import com.portfolio.deployops.domain.ApprovalRecord;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ApprovalRecordRepository {

    @Select("""
        SELECT ar.id, ar.deploy_request_id, ar.step_order, ar.action, ar.comment, ar.processed_at,
               u.id AS approver_id, u.login_id AS approver_login_id, u.name AS approver_name,
               u.role AS approver_role, u.active AS approver_active
        FROM approval_record ar
        LEFT JOIN users u ON ar.approver_id = u.id
        WHERE ar.deploy_request_id = #{deployRequestId}
        ORDER BY ar.step_order ASC, ar.processed_at ASC
        """)
    @Results(id = "approvalRecordResult", value = {
        @Result(property = "id", column = "id", id = true),
        @Result(property = "stepOrder", column = "step_order"),
        @Result(property = "action", column = "action"),
        @Result(property = "comment", column = "comment"),
        @Result(property = "processedAt", column = "processed_at"),
        @Result(property = "deployRequest.id", column = "deploy_request_id"),
        @Result(property = "approver.id", column = "approver_id"),
        @Result(property = "approver.loginId", column = "approver_login_id"),
        @Result(property = "approver.name", column = "approver_name"),
        @Result(property = "approver.role", column = "approver_role"),
        @Result(property = "approver.active", column = "approver_active")
    })
    List<ApprovalRecord> findByDeployRequestId(Long deployRequestId);

    @Select("""
        SELECT COUNT(*) > 0
        FROM approval_record
        WHERE deploy_request_id = #{deployRequestId}
          AND approver_id = #{approverId}
        """)
    boolean existsByDeployRequestIdAndApproverId(Long deployRequestId, Long approverId);

    @Select("""
        SELECT COUNT(*) > 0 FROM approval_record
        WHERE deploy_request_id = #{deployRequestId} AND step_order = #{stepOrder}
          AND approver_id = #{approverId}
        """)
    boolean existsByDeployRequestIdAndStepOrderAndApproverId(Long deployRequestId, int stepOrder, Long approverId);

    @Insert("""
        INSERT INTO approval_record (
            deploy_request_id, step_order, approver_id, action, comment, processed_at
        ) VALUES (
            #{deployRequest.id}, #{stepOrder}, #{approver.id}, #{action}, #{comment}, NOW(6)
        )
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ApprovalRecord record);

    default ApprovalRecord save(ApprovalRecord record) {
        insert(record);
        return record;
    }
}
