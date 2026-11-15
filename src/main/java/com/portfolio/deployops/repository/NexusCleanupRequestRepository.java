package com.portfolio.deployops.repository;

import com.portfolio.deployops.domain.NexusCleanupRequest;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface NexusCleanupRequestRepository {
    String COLUMNS = """
        SELECT n.*, p.name AS project_name,
               requester.name AS requester_name, approver.name AS approver_name
        FROM nexus_cleanup_request n
        JOIN project p ON p.id = n.project_id
        JOIN users requester ON requester.id = n.requested_by_id
        LEFT JOIN users approver ON approver.id = n.approved_by_id
        """;

    @Select(COLUMNS + " ORDER BY n.created_at DESC LIMIT 200")
    @Results(id = "nexusCleanupResult", value = {
        @Result(property = "id", column = "id", id = true),
        @Result(property = "project.id", column = "project_id"),
        @Result(property = "project.name", column = "project_name"),
        @Result(property = "requestedBy.id", column = "requested_by_id"),
        @Result(property = "requestedBy.name", column = "requester_name"),
        @Result(property = "approvedBy.id", column = "approved_by_id"),
        @Result(property = "approvedBy.name", column = "approver_name")
    })
    List<NexusCleanupRequest> findRecent();

    @Select(COLUMNS + " WHERE n.id = #{id}")
    @org.apache.ibatis.annotations.ResultMap("nexusCleanupResult")
    Optional<NexusCleanupRequest> findById(Long id);

    @Insert("""
        INSERT INTO nexus_cleanup_request
          (project_id, requested_by_id, status, repository_path, image_repository, image_tag, image_digest, reason, created_at)
        VALUES
          (#{project.id}, #{requestedBy.id}, #{status}, #{repositoryPath}, #{imageRepository}, #{imageTag}, #{imageDigest}, #{reason}, NOW(6))
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(NexusCleanupRequest request);

    @Update("""
        UPDATE nexus_cleanup_request
        SET status='DELETING', approved_by_id=#{approverId}, approved_at=NOW(6), result_message=NULL
        WHERE id=#{id} AND status='PENDING' AND requested_by_id <> #{approverId}
        """)
    int acquireApproval(@Param("id") Long id, @Param("approverId") Long approverId);

    @Update("""
        UPDATE nexus_cleanup_request
        SET status=#{status}, result_message=#{message}, completed_at=NOW(6)
        WHERE id=#{id} AND status='DELETING'
        """)
    int finish(@Param("id") Long id, @Param("status") String status, @Param("message") String message);

    @Update("""
        UPDATE nexus_cleanup_request
        SET status='REJECTED', approved_by_id=#{approverId}, approved_at=NOW(6), result_message=#{reason}
        WHERE id=#{id} AND status='PENDING' AND requested_by_id <> #{approverId}
        """)
    int reject(@Param("id") Long id, @Param("approverId") Long approverId, @Param("reason") String reason);
}
