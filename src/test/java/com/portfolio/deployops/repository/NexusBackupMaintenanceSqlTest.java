package com.portfolio.deployops.repository;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import static org.assertj.core.api.Assertions.*;

class NexusBackupMaintenanceSqlTest {
    SqlSessionFactory factory(String name)throws Exception{
        var ds=new UnpooledDataSource("org.h2.Driver","jdbc:h2:mem:"+name+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000","sa","");
        try(var c=ds.getConnection();var s=c.createStatement()){
            s.execute("CREATE TABLE users(id BIGINT PRIMARY KEY,name VARCHAR(100)); INSERT INTO users VALUES(10,'요청자'),(20,'승인자')");
            s.execute("CREATE TABLE deployment_execution_guard(id INT PRIMARY KEY); INSERT INTO deployment_execution_guard VALUES(1)");
            s.execute("CREATE TABLE nexus_cleanup_request(id BIGINT PRIMARY KEY,status VARCHAR(32))");
            ScriptUtils.executeSqlScript(c,new FileSystemResource("src/main/resources/db/migration/V44__nexus_dependency_upload_sessions.sql"));
            ScriptUtils.executeSqlScript(c,new FileSystemResource("src/main/resources/db/migration/V37__nexus_backup_requests.sql"));
            // H2 cannot parse MariaDB's multiple ADD clauses in one ALTER.
            // Split only this exact source syntax, preserving column and FK.
            // This fixture adaptation is not actual MariaDB verification.
            String restoreMigration = java.nio.file.Files.readString(java.nio.file.Path.of(
                    "src/main/resources/db/migration/V38__nexus_isolated_restore.sql")).replace("\r\n", "\n");
            String combinedAlter = "ALTER TABLE deployment_execution_guard ADD COLUMN nexus_restore_request_id BIGINT NULL,\n "
                    + "ADD CONSTRAINT fk_guard_nexus_restore FOREIGN KEY (nexus_restore_request_id) REFERENCES nexus_restore_request(id);";
            assertThat(restoreMigration).contains(combinedAlter);
            String portableAlter = "ALTER TABLE deployment_execution_guard ADD COLUMN nexus_restore_request_id BIGINT NULL;\n"
                    + "ALTER TABLE deployment_execution_guard ADD CONSTRAINT fk_guard_nexus_restore "
                    + "FOREIGN KEY (nexus_restore_request_id) REFERENCES nexus_restore_request(id);";
            ScriptUtils.executeSqlScript(c, new org.springframework.core.io.ByteArrayResource(
                    restoreMigration.replace(combinedAlter, portableAlter).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            String promotionMigration = java.nio.file.Files.readString(java.nio.file.Path.of(
                    "src/main/resources/db/migration/V42__nexus_restored_instance_promotion.sql")).replace("\r\n", "\n");
            String promotionAlter = "ALTER TABLE deployment_execution_guard ADD COLUMN nexus_promotion_request_id BIGINT NULL,\n "
                    + "ADD CONSTRAINT fk_guard_nexus_promotion FOREIGN KEY (nexus_promotion_request_id) REFERENCES nexus_promotion_request(id);";
            assertThat(promotionMigration).contains(promotionAlter);
            String portablePromotion = "ALTER TABLE deployment_execution_guard ADD COLUMN nexus_promotion_request_id BIGINT NULL;\n"
                    + "ALTER TABLE deployment_execution_guard ADD CONSTRAINT fk_guard_nexus_promotion "
                    + "FOREIGN KEY (nexus_promotion_request_id) REFERENCES nexus_promotion_request(id);";
            ScriptUtils.executeSqlScript(c, new org.springframework.core.io.ByteArrayResource(
                    promotionMigration.replace(promotionAlter, portablePromotion).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        var config=new org.apache.ibatis.session.Configuration(new Environment("test",new JdbcTransactionFactory(),ds));config.setMapUnderscoreToCamelCase(true);config.addMapper(ExecutionGuardRepository.class);config.addMapper(NexusBackupRequestRepository.class);return new SqlSessionFactoryBuilder().build(config);
    }
    @Test void publishingAndUnconfirmedUploadsBlockAllMaintenanceReservationsUntilResolved()throws Exception{
        var f=factory("upload_maintenance"+System.nanoTime());
        try(var session=f.openSession(false)){
            var g=session.getMapper(ExecutionGuardRepository.class);g.lockSingleton();
            try(var s=session.getConnection().createStatement()){
                s.execute("INSERT INTO nexus_dependency_upload(id,project_id,owner_id,repository_name,repository_format,nexus_base_url,target_path,total_bytes,status) VALUES('upload-1',1,10,'releases','maven2','http://nexus','example/a.jar',1,'PUBLISHING')");
                assertThat(g.reserveNexusMaintenance(7L)).isZero();
                assertThat(g.reserveNexusRestore(8L)).isZero();
                assertThat(g.reserveNexusPromotion(9L)).isZero();
                s.execute("UPDATE nexus_dependency_upload SET status='VERIFY_REQUIRED' WHERE id='upload-1'");
                assertThat(g.reserveNexusMaintenance(7L)).isZero();
                assertThat(g.reserveNexusRestore(8L)).isZero();
                assertThat(g.reserveNexusPromotion(9L)).isZero();
                s.execute("UPDATE nexus_dependency_upload SET status='COMPLETED' WHERE id='upload-1'");
                assertThat(g.reserveNexusMaintenance(7L)).isEqualTo(1);
                assertThat(g.reserveNexusRestore(8L)).isZero();
                assertThat(g.reserveNexusPromotion(9L)).isZero();
            }
            session.rollback();
        }
    }
    @Test void migrationAndDurableReservationSerializeTwoIndependentInstances()throws Exception{
        var f=factory("backup_race"+System.nanoTime());var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{List<Future<Boolean>> tasks=new ArrayList<>();for(long id:new long[]{1L,2L})tasks.add(pool.submit(()->{start.await();try(var session=f.openSession(false)){var g=session.getMapper(ExecutionGuardRepository.class);g.lockSingleton();boolean won=g.nexusMaintenanceOwner()==null&&g.reserveNexusMaintenance(id)==1;session.commit();return won;}}));start.countDown();int won=0;for(var task:tasks)if(task.get(6,TimeUnit.SECONDS))won++;assertThat(won).isEqualTo(1);try(var session=f.openSession()){assertThat(session.getMapper(ExecutionGuardRepository.class).nexusMaintenanceOwner()).isIn(1L,2L);}}finally{pool.shutdownNow();}
    }
    @Test void approvalSubmissionCasAndRollbackAreDurable()throws Exception{
        var f=factory("backup_cas"+System.nanoTime());
        try(var session=f.openSession(false)){var repo=session.getMapper(NexusBackupRequestRepository.class);var r=new com.portfolio.deployops.domain.NexusBackupRequest();r.setRequestedById(10L);r.setReason("유지보수");repo.insert(r);assertThat(repo.approve(r.getId(),10L)).isZero();assertThat(repo.approve(r.getId(),20L)).isEqualTo(1);assertThat(repo.approve(r.getId(),20L)).isZero();assertThat(repo.markSubmitted(r.getId())).isEqualTo(1);assertThat(repo.markSubmitted(r.getId())).isZero();assertThat(repo.findById(r.getId()).orElseThrow().getStatus()).isEqualTo("RECONCILING");session.commit();}
        try(var session=f.openSession(false)){var g=session.getMapper(ExecutionGuardRepository.class);g.lockSingleton();g.reserveNexusMaintenance(7L);session.rollback();}
        try(var session=f.openSession()){assertThat(session.getMapper(ExecutionGuardRepository.class).nexusMaintenanceOwner()).isNull();assertThat(session.getMapper(NexusBackupRequestRepository.class).findUnfinished()).hasSize(1);}
    }
    @Test void twoInstancesSerializeRecoveryCheckAndSubmissionCannotReplay()throws Exception{
        var f=factory("check_race"+System.nanoTime());
        try(var s=f.openSession(false)){var repo=s.getMapper(NexusBackupRequestRepository.class);var r=new com.portfolio.deployops.domain.NexusBackupRequest();r.setRequestedById(10L);r.setReason("백업");repo.insert(r);s.getConnection().createStatement().execute("UPDATE nexus_backup_request SET submitted=TRUE,status='RECOVERY_REQUIRED'");s.getMapper(ExecutionGuardRepository.class).reserveNexusMaintenance(r.getId());s.commit();}
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{List<Future<Long>> tasks=new ArrayList<>();for(int i=0;i<2;i++)tasks.add(pool.submit(()->{start.await();try(var s=f.openSession(false)){var g=s.getMapper(ExecutionGuardRepository.class);g.lockSingleton();var repo=s.getMapper(NexusBackupRequestRepository.class);var r=repo.findById(1L).orElseThrow();if(r.getActiveCheckId()!=null){s.rollback();return 0L;}var check=new com.portfolio.deployops.domain.NexusBackupRecoveryCheck();check.setRequestId(1L);check.setRequestedById(20L);repo.insertCheck(check);assertThat(repo.activateCheck(1L,check.getId())).isEqualTo(1);s.commit();return check.getId();}}));start.countDown();int won=0;for(var t:tasks)if(t.get(6,TimeUnit.SECONDS)>0)won++;assertThat(won).isEqualTo(1);try(var s=f.openSession(false)){var repo=s.getMapper(NexusBackupRequestRepository.class);var id=repo.findById(1L).orElseThrow().getActiveCheckId();assertThat(repo.submitCheck(id)).isEqualTo(1);assertThat(repo.submitCheck(id)).isZero();s.commit();assertThat(repo.unfinishedChecks()).hasSize(1);assertThat(repo.unfinishedChecks().get(0).getStatus()).isEqualTo("SUBMITTED");}}finally{pool.shutdownNow();}
    }
    @Test void concurrentExplicitReissueOnlyReplacesExpectedOldCheckOnce()throws Exception{
        var f=factory("check_reissue"+System.nanoTime());long oldId;
        try(var s=f.openSession(false)){var repo=s.getMapper(NexusBackupRequestRepository.class);var r=new com.portfolio.deployops.domain.NexusBackupRequest();r.setRequestedById(10L);r.setReason("백업");repo.insert(r);s.getConnection().createStatement().execute("UPDATE nexus_backup_request SET submitted=TRUE,status='RECOVERY_REQUIRED'");var c=new com.portfolio.deployops.domain.NexusBackupRecoveryCheck();c.setRequestId(r.getId());c.setRequestedById(20L);repo.insertCheck(c);oldId=c.getId();repo.activateCheck(r.getId(),oldId);repo.submitCheck(oldId);s.getMapper(ExecutionGuardRepository.class).reserveNexusMaintenance(r.getId());s.commit();}
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{List<Future<Boolean>> tasks=new ArrayList<>();for(int i=0;i<2;i++)tasks.add(pool.submit(()->{start.await();try(var s=f.openSession(false)){var g=s.getMapper(ExecutionGuardRepository.class);g.lockSingleton();var repo=s.getMapper(NexusBackupRequestRepository.class);if(!Objects.equals(repo.findById(1L).orElseThrow().getActiveCheckId(),oldId)){s.rollback();return false;}assertThat(repo.supersedeCheck(oldId,1L)).isEqualTo(1);var c=new com.portfolio.deployops.domain.NexusBackupRecoveryCheck();c.setRequestId(1L);c.setRequestedById(20L);repo.insertCheck(c);assertThat(repo.replaceCheck(1L,oldId,c.getId())).isEqualTo(1);s.commit();return true;}}));start.countDown();int won=0;for(var t:tasks)if(t.get(6,TimeUnit.SECONDS))won++;assertThat(won).isEqualTo(1);try(var s=f.openSession()){var repo=s.getMapper(NexusBackupRequestRepository.class);assertThat(repo.findCheck(oldId).orElseThrow().getStatus()).isEqualTo("UNCONFIRMED");assertThat(repo.findById(1L).orElseThrow().getActiveCheckId()).isNotEqualTo(oldId);assertThat(repo.unfinishedChecks()).hasSize(1);assertThat(s.getMapper(ExecutionGuardRepository.class).nexusMaintenanceOwner()).isEqualTo(1L);}}finally{pool.shutdownNow();}
    }
}
