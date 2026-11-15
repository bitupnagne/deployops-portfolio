package com.portfolio.deployops.repository;

import com.portfolio.deployops.domain.NexusRestoreRequest;
import com.portfolio.deployops.domain.NexusRestoreRecoveryCheck;
import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface NexusRestoreRequestRepository {
    String QUERY = "SELECT x.*,u.name requested_by,a.name approved_by, (SELECT c.status FROM nexus_restore_recovery_check c WHERE c.request_id=x.id ORDER BY c.id DESC LIMIT 1) last_check_status, (SELECT c.reason_code FROM nexus_restore_recovery_check c WHERE c.request_id=x.id ORDER BY c.id DESC LIMIT 1) last_check_reason FROM nexus_restore_request x JOIN users u ON u.id=x.requested_by_id LEFT JOIN users a ON a.id=x.approved_by_id";
    @Select(QUERY + " ORDER BY x.id DESC LIMIT 200")
    List<NexusRestoreRequest> recent();
    @Select(QUERY + " WHERE x.id=#{id}")
    Optional<NexusRestoreRequest> find(Long id);
    @Select(QUERY + " WHERE x.status IN ('QUEUED','RUNNING','RECONCILING','RECOVERY_REQUIRED') ORDER BY x.id LIMIT 200")
    List<NexusRestoreRequest> unfinished();
    @Insert("""
            INSERT INTO nexus_restore_request(backup_request_id,artifact_id,manifest_sha256,requested_by_id,reason,status,created_at) VALUES(#{backupRequestId},#{artifactId},#{manifestSha256},#{requestedById},#{reason},'PENDING',NOW(6))
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(NexusRestoreRequest r);
    @Update("""
            UPDATE nexus_restore_request
            SET status='QUEUED',approved_by_id=#{actor},approved_at=NOW(6)
            WHERE id=#{id} AND status='PENDING' AND requested_by_id<>#{actor}
            """)
    int approve(@Param("id") Long id, @Param("actor") Long actor);
    @Update("""
            UPDATE nexus_restore_request
            SET status='REJECTED',approved_by_id=#{actor},rejection_reason=#{reason},approved_at=NOW(6),completed_at=NOW(6)
            WHERE id=#{id} AND status='PENDING' AND requested_by_id<>#{actor}
            """)
    int reject(@Param("id") Long id, @Param("actor") Long actor, @Param("reason") String reason);
    @Update("""
            UPDATE nexus_restore_request
            SET submitted=TRUE,status='RECONCILING',error_code='RESULT_UNCERTAIN'
            WHERE id=#{id} AND status='QUEUED' AND submitted=FALSE
            """)
    int submit(Long id);
    @Update("""
            UPDATE nexus_restore_request
            SET status='FAILED',stage='PREFLIGHT',error_code='PREFLIGHT_REJECTED',
                mutation_uncertain=FALSE,original_projection_preserved=FALSE,completed_at=NOW(6)
            WHERE id=#{id} AND submitted=TRUE AND status='RECONCILING'
              AND clone_container_id IS NULL
            """)
    int publicationRejected(Long id);

    @Update("""
            UPDATE nexus_restore_request
            SET status=#{status},stage=#{stage},error_code=#{errorCode},clone_container_id=#{cloneContainerId},clone_image_id=#{cloneImageId},clone_name=#{cloneName},clone_volume=#{cloneVolume},clone_network=#{cloneNetwork},clone_access=#{cloneAccess},expected_artifacts=#{expectedArtifacts},original_projection_preserved=#{originalProjectionPreserved},mutation_uncertain=#{mutationUncertain},completed_at=#{completedAt}
            WHERE id=#{id} AND submitted=TRUE AND status IN ('QUEUED','RUNNING','RECONCILING','RECOVERY_REQUIRED')
            """)
    int result(NexusRestoreRequest r);
    @Update("""
            UPDATE nexus_restore_request
            SET status='RECONCILING',error_code='RESULT_UNCERTAIN',mutation_uncertain=TRUE
            WHERE id=#{id} AND submitted=TRUE AND status IN ('QUEUED','RUNNING','RECONCILING')
            """)
    int uncertain(Long id);
    @Update("""
            UPDATE nexus_restore_request
            SET status='OPERATOR_CONFIRMED',confirmed_by_id=#{confirmedById},confirmed_at=NOW(6),operator_checklist=#{operatorChecklist},artifact_confirmations=#{artifactConfirmations}
            WHERE id=#{id} AND status='HEALTH_READY' AND clone_container_id=#{cloneContainerId} AND manifest_sha256=#{manifestSha256} AND mutation_uncertain=FALSE AND original_projection_preserved=TRUE
            """)
    int confirm(NexusRestoreRequest r);
    @Insert("INSERT INTO nexus_restore_recovery_check(request_id,requested_by_id,status,reason_code,created_at) VALUES(#{requestId},#{requestedById},'QUEUED',#{reasonCode},NOW(6))")
    @Options(useGeneratedKeys=true,keyProperty="id")
    int insertCheck(NexusRestoreRecoveryCheck check);
    @Update("UPDATE nexus_restore_request SET active_check_id=#{checkId} WHERE id=#{id} AND active_check_id IS NULL AND submitted=TRUE AND status IN ('RECOVERY_REQUIRED','RECONCILING')")
    int activateCheck(@Param("id") Long id,@Param("checkId") Long checkId);
    @Select("SELECT * FROM nexus_restore_recovery_check WHERE status IN ('QUEUED','SUBMITTED') ORDER BY id LIMIT 200")
    List<NexusRestoreRecoveryCheck> unfinishedChecks();
    @Select("SELECT * FROM nexus_restore_recovery_check WHERE id=#{id}")
    Optional<NexusRestoreRecoveryCheck> findCheck(Long id);
    @Update("UPDATE nexus_restore_recovery_check SET status='SUBMITTED' WHERE id=#{id} AND status='QUEUED'")
    int submitCheck(Long id);
    @Update("UPDATE nexus_restore_recovery_check SET status=#{status},reason_code=#{reasonCode},completed_at=#{completedAt} WHERE id=#{id} AND status='SUBMITTED'")
    int finishCheck(NexusRestoreRecoveryCheck check);
    @Update("UPDATE nexus_restore_request SET active_check_id=NULL WHERE id=#{id} AND active_check_id=#{checkId}")
    int clearCheck(@Param("id") Long id,@Param("checkId") Long checkId);
}
