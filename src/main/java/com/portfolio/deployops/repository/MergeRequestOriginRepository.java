package com.portfolio.deployops.repository;

import java.util.Optional;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** DeployOps가 시스템 봇/PAT로 만든 MR의 실제 사람 작성자를 보존한다. */
@Mapper
public interface MergeRequestOriginRepository {

    @Insert("""
        INSERT INTO merge_request_origin (
          project_id, gitlab_project_id, merge_request_iid, created_by_id,
          gitlab_author_username, created_at
        ) VALUES (
          #{projectId}, #{gitlabProjectId}, #{mergeRequestIid}, #{createdById},
          #{gitlabAuthorUsername}, NOW(6)
        )
        ON DUPLICATE KEY UPDATE id=id
        """)
    int saveIfAbsent(
        Long projectId,
        Long gitlabProjectId,
        Long mergeRequestIid,
        Long createdById,
        String gitlabAuthorUsername
    );

    @Select("""
        SELECT created_by_id FROM merge_request_origin
        WHERE project_id=#{projectId} AND gitlab_project_id=#{gitlabProjectId}
          AND merge_request_iid=#{mergeRequestIid}
        """)
    Optional<Long> findCreatedById(Long projectId, Long gitlabProjectId, Long mergeRequestIid);

    /**
     * GitLab 저장소 연결이 바뀐 뒤 과거 DeployOps 프로젝트에서 만든 MR이 새 프로젝트에 섞이지 않도록
     * MR 생성 당시 연결된 DeployOps 프로젝트를 찾는다.
     */
    @Select("""
        SELECT DISTINCT project_id FROM merge_request_origin
        WHERE gitlab_project_id=#{gitlabProjectId} AND merge_request_iid=#{mergeRequestIid}
        ORDER BY project_id
        """)
    List<Long> findProjectIdsByGitlabProjectIdAndMergeRequestIid(
        Long gitlabProjectId, Long mergeRequestIid);
}
