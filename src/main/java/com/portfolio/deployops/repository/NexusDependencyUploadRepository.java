package com.portfolio.deployops.repository;

import com.portfolio.deployops.domain.NexusDependencyUpload;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface NexusDependencyUploadRepository {
    @Insert("""
        INSERT INTO nexus_dependency_upload
          (id,project_id,owner_id,repository_name,repository_format,nexus_base_url,target_path,total_bytes,status,message)
        VALUES (#{id},#{projectId},#{ownerId},#{repositoryName},#{repositoryFormat},#{nexusBaseUrl},#{targetPath},#{totalBytes},#{status},#{message})
        """)
    void insert(NexusDependencyUpload row);

    @Select("SELECT * FROM nexus_dependency_upload WHERE id=#{id}")
    NexusDependencyUpload find(String id);

    @Select("SELECT * FROM nexus_dependency_upload WHERE project_id=#{projectId} AND (#{ownerId} IS NULL OR owner_id=#{ownerId}) ORDER BY (status IN ('RECEIVING','READY','PUBLISHING','VERIFY_REQUIRED')) DESC, created_at DESC LIMIT 100")
    List<NexusDependencyUpload> recent(@Param("projectId") Long projectId, @Param("ownerId") Long ownerId);

    @Update("""
        UPDATE nexus_dependency_upload SET received_bytes=#{receivedBytes},published_bytes=#{publishedBytes},
          content_sha256=#{contentSha256},status=#{status},message=#{message},updated_at=CURRENT_TIMESTAMP(6)
        WHERE id=#{id}
        """)
    void save(NexusDependencyUpload row);

    @Update("""
        UPDATE nexus_dependency_upload SET status=#{next},message=#{message},updated_at=CURRENT_TIMESTAMP(6)
        WHERE id=#{id} AND status=#{expected}
        """)
    int transition(@Param("id") String id, @Param("expected") String expected,
                   @Param("next") String next, @Param("message") String message);

    @Update("UPDATE nexus_dependency_upload SET published_bytes=#{bytes},updated_at=CURRENT_TIMESTAMP(6) WHERE id=#{id} AND status='PUBLISHING'")
    void progress(@Param("id") String id, @Param("bytes") long bytes);

    @Select("SELECT COUNT(*) FROM nexus_dependency_upload WHERE status IN ('PUBLISHING','VERIFY_REQUIRED')")
    long active();

    // An expired lease is deliberately NOT unlocked: Nexus may already have accepted the bytes.
    @Update("""
        UPDATE nexus_dependency_upload SET status='VERIFY_REQUIRED',
          message='전송 실행기의 응답이 끊겼습니다. Nexus 등록 결과를 확인하세요.',updated_at=CURRENT_TIMESTAMP(6)
        WHERE status='PUBLISHING' AND updated_at < DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 10 MINUTE)
        """)
    void expireLeases();
}
