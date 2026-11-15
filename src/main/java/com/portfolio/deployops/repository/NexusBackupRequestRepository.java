package com.portfolio.deployops.repository;

import com.portfolio.deployops.domain.NexusBackupRequest;
import com.portfolio.deployops.domain.NexusBackupRecoveryCheck;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.*;

@Mapper
public interface NexusBackupRequestRepository {
    String QUERY = "SELECT b.*,r.name AS requested_by,a.name AS approved_by FROM nexus_backup_request b JOIN users r ON r.id=b.requested_by_id LEFT JOIN users a ON a.id=b.approved_by_id";
    @Select(QUERY+" ORDER BY b.id DESC LIMIT 200") List<NexusBackupRequest> findRecent();
    @Select(QUERY+" WHERE b.id=#{id}") Optional<NexusBackupRequest> findById(Long id);
    @Select(QUERY+" WHERE b.status IN ('QUEUED','RUNNING','RECONCILING','RECOVERY_REQUIRED') ORDER BY b.id LIMIT 200") List<NexusBackupRequest> findUnfinished();
    @Insert("INSERT INTO nexus_backup_request(requested_by_id,reason,status,created_at) VALUES(#{requestedById},#{reason},'PENDING',NOW(6))")
    @Options(useGeneratedKeys=true,keyProperty="id") int insert(NexusBackupRequest request);
    @Update("UPDATE nexus_backup_request SET status='QUEUED',approved_by_id=#{actor},approved_at=NOW(6) WHERE id=#{id} AND status='PENDING' AND requested_by_id<>#{actor}")
    int approve(@Param("id") Long id,@Param("actor") Long actor);
    @Update("UPDATE nexus_backup_request SET status='REJECTED',approved_by_id=#{actor},approved_at=NOW(6),completed_at=NOW(6),error_code='REJECTED',rejection_reason=#{reason} WHERE id=#{id} AND status='PENDING' AND requested_by_id<>#{actor}")
    int reject(@Param("id") Long id,@Param("actor") Long actor,@Param("reason") String reason);
    @Update("UPDATE nexus_backup_request SET submitted=TRUE,status='RECONCILING',error_code='RESULT_UNCERTAIN' WHERE id=#{id} AND status='QUEUED' AND submitted=FALSE") int markSubmitted(Long id);
    @Update("UPDATE nexus_backup_request SET status=#{status},stage=#{stage},error_code=#{errorCode},artifact_id=#{artifactId},manifest_sha256=#{manifestSha256},completed_at=#{completedAt} WHERE id=#{id} AND submitted=TRUE AND status IN ('QUEUED','RUNNING','RECONCILING','RECOVERY_REQUIRED')") int updateResult(NexusBackupRequest result);
    @Insert("INSERT INTO nexus_backup_recovery_check(request_id,requested_by_id,status,created_at) VALUES(#{requestId},#{requestedById},'QUEUED',NOW(6))")
    @Options(useGeneratedKeys=true,keyProperty="id") int insertCheck(NexusBackupRecoveryCheck check);
    @Update("UPDATE nexus_backup_request SET active_check_id=#{checkId} WHERE id=#{id} AND active_check_id IS NULL AND submitted=TRUE AND status IN ('RECOVERY_REQUIRED','RECONCILING')")
    int activateCheck(@Param("id") Long id,@Param("checkId") Long checkId);
    @Select("SELECT * FROM nexus_backup_recovery_check WHERE status IN ('QUEUED','SUBMITTED') ORDER BY id LIMIT 200") List<NexusBackupRecoveryCheck> unfinishedChecks();
    @Select("SELECT * FROM nexus_backup_recovery_check WHERE id=#{id}") Optional<NexusBackupRecoveryCheck> findCheck(Long id);
    @Update("UPDATE nexus_backup_recovery_check SET status='SUBMITTED' WHERE id=#{id} AND status='QUEUED'") int submitCheck(Long id);
    @Update("UPDATE nexus_backup_recovery_check SET status=#{status},completed_at=#{completedAt} WHERE id=#{id} AND status='SUBMITTED'") int finishCheck(NexusBackupRecoveryCheck check);
    @Update("UPDATE nexus_backup_request SET active_check_id=NULL WHERE id=#{id} AND active_check_id=#{checkId}")
    int clearCheck(@Param("id") Long id,@Param("checkId") Long checkId);
    @Update("UPDATE nexus_backup_recovery_check SET status='UNCONFIRMED',completed_at=NOW(6) WHERE id=#{id} AND request_id=#{requestId} AND status IN ('QUEUED','SUBMITTED')")
    int supersedeCheck(@Param("id") Long id,@Param("requestId") Long requestId);
    @Update("UPDATE nexus_backup_request SET active_check_id=#{newCheckId} WHERE id=#{id} AND active_check_id=#{oldCheckId} AND submitted=TRUE AND status IN ('RECOVERY_REQUIRED','RECONCILING')")
    int replaceCheck(@Param("id") Long id,@Param("oldCheckId") Long oldCheckId,@Param("newCheckId") Long newCheckId);
}
