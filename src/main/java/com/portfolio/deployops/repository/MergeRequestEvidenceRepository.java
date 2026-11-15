package com.portfolio.deployops.repository;

import com.portfolio.deployops.domain.MergeRequestEvidence;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface MergeRequestEvidenceRepository {

    @Insert("""
        INSERT INTO merge_request_evidence (
          project_id, gitlab_project_id, merge_request_iid, source_sha, target_branch,
          diff_base_sha, diff_start_sha, reviewer_id, reviewer_gitlab_user_id, reviewer_gitlab_username,
          token_source, status, approved_at, created_at, updated_at
        ) VALUES (
          #{projectId}, #{gitlabProjectId}, #{mergeRequestIid}, #{sourceSha}, #{targetBranch},
          #{diffBaseSha}, #{diffStartSha}, #{reviewerId}, #{reviewerGitlabUserId}, #{reviewerGitlabUsername},
          #{tokenSource}, #{status}, NOW(6), NOW(6), NOW(6)
        )
        ON DUPLICATE KEY UPDATE
          target_branch=VALUES(target_branch), diff_base_sha=VALUES(diff_base_sha),
          diff_start_sha=VALUES(diff_start_sha), reviewer_id=VALUES(reviewer_id),
          reviewer_gitlab_user_id=VALUES(reviewer_gitlab_user_id),
          reviewer_gitlab_username=VALUES(reviewer_gitlab_username), token_source=VALUES(token_source),
          status='APPROVED', merged_result_sha=NULL, merger_id=NULL, merged_at=NULL,
          approved_at=NOW(6), updated_at=NOW(6)
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int saveApproved(MergeRequestEvidence evidence);

    @Select("""
        SELECT * FROM merge_request_evidence
        WHERE project_id=#{projectId} AND gitlab_project_id=#{gitlabProjectId}
          AND merge_request_iid=#{mergeRequestIid}
          AND source_sha=#{sourceSha} AND status='APPROVED'
        LIMIT 1
        """)
    @Results(id = "mrEvidence", value = {
        @Result(property="id", column="id", id=true),
        @Result(property="projectId", column="project_id"),
        @Result(property="gitlabProjectId", column="gitlab_project_id"),
        @Result(property="mergeRequestIid", column="merge_request_iid"),
        @Result(property="sourceSha", column="source_sha"),
        @Result(property="targetBranch", column="target_branch"),
        @Result(property="diffBaseSha", column="diff_base_sha"),
        @Result(property="diffStartSha", column="diff_start_sha"),
        @Result(property="reviewerId", column="reviewer_id"),
        @Result(property="reviewerGitlabUserId", column="reviewer_gitlab_user_id"),
        @Result(property="reviewerGitlabUsername", column="reviewer_gitlab_username"),
        @Result(property="tokenSource", column="token_source"),
        @Result(property="status", column="status"),
        @Result(property="mergedResultSha", column="merged_result_sha"),
        @Result(property="mergerId", column="merger_id"),
        @Result(property="approvedAt", column="approved_at"),
        @Result(property="mergedAt", column="merged_at"),
        @Result(property="createdAt", column="created_at"),
        @Result(property="updatedAt", column="updated_at")
    })
    Optional<MergeRequestEvidence> findApproved(
        Long projectId, Long gitlabProjectId, Long mergeRequestIid, String sourceSha);

    @Select("""
        SELECT * FROM merge_request_evidence
        WHERE project_id=#{projectId} AND gitlab_project_id=#{gitlabProjectId}
          AND merge_request_iid=#{mergeRequestIid} AND status='MERGED'
        ORDER BY merged_at DESC, id DESC
        LIMIT 1
        """)
    @org.apache.ibatis.annotations.ResultMap("mrEvidence")
    Optional<MergeRequestEvidence> findMerged(Long projectId, Long gitlabProjectId, Long mergeRequestIid);

    @Update("""
        UPDATE merge_request_evidence SET status='MERGED', merged_result_sha=#{mergedResultSha},
          merger_id=#{mergerId}, merged_at=NOW(6), updated_at=NOW(6)
        WHERE id=#{id} AND status='APPROVED'
        """)
    int markMerged(MergeRequestEvidence evidence);

    @Update("""
        UPDATE merge_request_evidence SET status='REJECTED', updated_at=NOW(6)
        WHERE project_id=#{projectId} AND gitlab_project_id=#{gitlabProjectId}
          AND merge_request_iid=#{mergeRequestIid} AND status='APPROVED'
        """)
    int invalidateApprovals(Long projectId, Long gitlabProjectId, Long mergeRequestIid);
}
