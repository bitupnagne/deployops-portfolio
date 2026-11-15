package com.portfolio.deployops.repository;

import com.portfolio.deployops.domain.ManualServerExecution;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ManualServerExecutionRepository {

    @Select("SELECT COUNT(*) FROM manual_server_execution WHERE server_id = #{serverId}")
    long countByServerId(Long serverId);

    @Select("""
        SELECT deploy_request_id, server_id, status, executed_by_id, error_message, started_at, finished_at
        FROM manual_server_execution
        WHERE deploy_request_id = #{requestId}
        ORDER BY server_id
        """)
    List<ManualServerExecution> findByRequestId(Long requestId);

    @Insert("""
        INSERT IGNORE INTO manual_server_execution
            (deploy_request_id, server_id, status, executed_by_id, started_at)
        VALUES (#{requestId}, #{serverId}, 'RUNNING', #{userId}, NOW(6))
        """)
    int insertRunning(@Param("requestId") Long requestId, @Param("serverId") Long serverId, @Param("userId") Long userId);

    @Update("""
        UPDATE manual_server_execution
        SET status = 'RUNNING', executed_by_id = #{userId}, error_message = NULL,
            started_at = NOW(6), finished_at = NULL
        WHERE deploy_request_id = #{requestId} AND server_id = #{serverId} AND status = 'FAILED'
        """)
    int retryFailed(@Param("requestId") Long requestId, @Param("serverId") Long serverId, @Param("userId") Long userId);

    @Update("""
        UPDATE manual_server_execution
        SET status = #{status}, error_message = #{errorMessage}, finished_at = NOW(6)
        WHERE deploy_request_id = #{requestId} AND server_id = #{serverId} AND status = 'RUNNING'
        """)
    int finish(@Param("requestId") Long requestId, @Param("serverId") Long serverId,
               @Param("status") String status, @Param("errorMessage") String errorMessage);
}
