package com.portfolio.deployops.repository;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ExecutionGuardRepository {

    /** 모든 인스턴스가 같은 한 행을 잠가 동시 실행 수 확인과 선점을 직렬화한다. */
    @Select("SELECT id FROM deployment_execution_guard WHERE id = 1 FOR UPDATE")
    Integer lockSingleton();

    @Select("SELECT nexus_maintenance_request_id FROM deployment_execution_guard WHERE id=1")
    Long nexusMaintenanceOwner();

    @org.apache.ibatis.annotations.Update("UPDATE deployment_execution_guard SET nexus_maintenance_request_id=#{id} WHERE id=1 AND nexus_maintenance_request_id IS NULL AND nexus_restore_request_id IS NULL AND nexus_promotion_request_id IS NULL AND NOT EXISTS (SELECT 1 FROM nexus_dependency_upload WHERE status IN ('PUBLISHING','VERIFY_REQUIRED'))")
    int reserveNexusMaintenance(Long id);

    @org.apache.ibatis.annotations.Update("UPDATE deployment_execution_guard SET nexus_maintenance_request_id=NULL WHERE id=1 AND nexus_maintenance_request_id=#{id}")
    int releaseNexusMaintenance(Long id);

    @Select("SELECT COUNT(*) FROM nexus_cleanup_request WHERE status='DELETING'")
    long activeNexusCleanupCount();

    @Select("SELECT nexus_restore_request_id FROM deployment_execution_guard WHERE id=1")
    Long nexusRestoreOwner();
    @org.apache.ibatis.annotations.Update("UPDATE deployment_execution_guard SET nexus_restore_request_id=#{id} WHERE id=1 AND nexus_restore_request_id IS NULL AND nexus_maintenance_request_id IS NULL AND nexus_promotion_request_id IS NULL AND NOT EXISTS (SELECT 1 FROM nexus_dependency_upload WHERE status IN ('PUBLISHING','VERIFY_REQUIRED'))")
    int reserveNexusRestore(Long id);
    @org.apache.ibatis.annotations.Update("UPDATE deployment_execution_guard SET nexus_restore_request_id=NULL WHERE id=1 AND nexus_restore_request_id=#{id}")
    int releaseNexusRestore(Long id);
    @Select("SELECT nexus_promotion_request_id FROM deployment_execution_guard WHERE id=1")
    Long nexusPromotionOwner();
    @org.apache.ibatis.annotations.Update("UPDATE deployment_execution_guard SET nexus_promotion_request_id=#{id} WHERE id=1 AND nexus_restore_request_id IS NULL AND nexus_maintenance_request_id IS NULL AND nexus_promotion_request_id IS NULL AND NOT EXISTS (SELECT 1 FROM nexus_dependency_upload WHERE status IN ('PUBLISHING','VERIFY_REQUIRED'))")
    int reserveNexusPromotion(Long id);
    @org.apache.ibatis.annotations.Update("UPDATE deployment_execution_guard SET nexus_promotion_request_id=NULL WHERE id=1 AND nexus_promotion_request_id=#{id}")
    int releaseNexusPromotion(Long id);
    default void assertNoNexusMaintenance() {
        if(nexusMaintenanceOwner()!=null || nexusRestoreOwner()!=null || nexusPromotionOwner()!=null) throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.CONFLICT,"Nexus 백업 유지보수가 진행 중입니다. 결과와 원본 상태 복구를 확인한 뒤 실행해 주세요.");
    }
}
