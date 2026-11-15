package com.portfolio.deployops.repository;

import com.portfolio.deployops.domain.StatusHistory;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface StatusHistoryRepository {

    @Select("""
        SELECT MIN(created_at) FROM status_history WHERE deploy_request_id = #{requestId}
        AND status IN ('TEST_DEPLOYING', 'PROD_DEPLOYING')
        """)
    java.time.LocalDateTime findFirstDeploymentStartedAt(Long requestId);

    @Select("""
        SELECT id, deploy_request_id, status, created_at
        FROM status_history
        WHERE deploy_request_id = #{deployRequestId}
        ORDER BY created_at ASC, id ASC
        """)
    @Results(id = "statusHistoryResult", value = {
        @Result(property = "id", column = "id", id = true),
        @Result(property = "deployRequest.id", column = "deploy_request_id"),
        @Result(property = "status", column = "status"),
        @Result(property = "createdAt", column = "created_at")
    })
    List<StatusHistory> findByDeployRequestId(Long deployRequestId);

    @Insert("""
        INSERT INTO status_history (
            deploy_request_id, status, created_at
        ) VALUES (
            #{deployRequest.id}, #{status}, NOW(6)
        )
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(StatusHistory statusHistory);

    default StatusHistory save(StatusHistory statusHistory) {
        insert(statusHistory);
        return statusHistory;
    }
}
