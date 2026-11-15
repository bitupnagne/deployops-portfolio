package com.portfolio.deployops.repository;

import com.portfolio.deployops.domain.NexusPromotionRequest;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.*;

@Mapper
public interface NexusPromotionRequestRepository {
    String QUERY = "SELECT p.*,u.name requested_by,a.name approved_by FROM nexus_promotion_request p JOIN users u ON u.id=p.requested_by_id LEFT JOIN users a ON a.id=p.approved_by_id";
    @Select(QUERY+" ORDER BY p.id DESC LIMIT 200") List<NexusPromotionRequest> recent();
    @Select(QUERY+" WHERE p.id=#{id}") Optional<NexusPromotionRequest> find(Long id);
    @Select(QUERY+" WHERE p.status IN ('QUEUED','RUNNING','RECOVERY_REQUIRED') ORDER BY p.id") List<NexusPromotionRequest> unfinished();
    @Select("SELECT COUNT(*) FROM nexus_promotion_request WHERE restore_request_id=#{id} AND status NOT IN ('FAILED','REJECTED')") long activeForRestore(Long id);
    @Insert("INSERT INTO nexus_promotion_request(restore_request_id,requested_by_id,reason,status,created_at) VALUES(#{restoreRequestId},#{requestedById},#{reason},'PENDING',NOW(6))")
    @Options(useGeneratedKeys=true,keyProperty="id") int insert(NexusPromotionRequest request);
    @Update("UPDATE nexus_promotion_request SET status='QUEUED',approved_by_id=#{actor},approved_at=NOW(6) WHERE id=#{id} AND status='PENDING' AND requested_by_id<>#{actor}")
    int approve(@Param("id") Long id,@Param("actor") Long actor);
    @Update("UPDATE nexus_promotion_request SET status='REJECTED',approved_by_id=#{actor},rejection_reason=#{reason},approved_at=NOW(6),completed_at=NOW(6) WHERE id=#{id} AND status='PENDING' AND requested_by_id<>#{actor}")
    int reject(@Param("id") Long id,@Param("actor") Long actor,@Param("reason") String reason);
    @Update("UPDATE nexus_promotion_request SET submitted=TRUE,status='RUNNING' WHERE id=#{id} AND status='QUEUED' AND submitted=FALSE") int submit(Long id);
    @Update("UPDATE nexus_promotion_request SET status=#{status},canonical_container_id=#{canonicalContainerId},rollback_container_id=#{rollbackContainerId},error_code=#{errorCode},completed_at=#{completedAt} WHERE id=#{id} AND submitted=TRUE AND status IN ('RUNNING','RECOVERY_REQUIRED')")
    int result(NexusPromotionRequest request);
    @Update("UPDATE nexus_promotion_request SET status='RECOVERY_REQUIRED',error_code='RESULT_UNCERTAIN' WHERE id=#{id} AND submitted=TRUE AND status='RUNNING'") int uncertain(Long id);
}
