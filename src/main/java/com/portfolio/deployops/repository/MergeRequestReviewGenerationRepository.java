package com.portfolio.deployops.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface MergeRequestReviewGenerationRepository {
    @Insert("""
        INSERT IGNORE INTO merge_request_review_generation
          (project_id, gitlab_project_id, merge_request_iid, generation)
        VALUES (#{projectId}, #{gitlabProjectId}, #{mergeRequestIid}, 0)
        """)
    int ensureExists(Long projectId, Long gitlabProjectId, Long mergeRequestIid);

    @Select("""
        SELECT generation FROM merge_request_review_generation
        WHERE project_id=#{projectId} AND gitlab_project_id=#{gitlabProjectId}
          AND merge_request_iid=#{mergeRequestIid} FOR UPDATE
        """)
    Long lockGeneration(Long projectId, Long gitlabProjectId, Long mergeRequestIid);

    @Update("""
        UPDATE merge_request_review_generation SET generation=generation+1
        WHERE project_id=#{projectId} AND gitlab_project_id=#{gitlabProjectId}
          AND merge_request_iid=#{mergeRequestIid}
        """)
    int advanceGeneration(Long projectId, Long gitlabProjectId, Long mergeRequestIid);
}
