package com.portfolio.deployops.repository;

import com.portfolio.deployops.dto.ReferenceReport;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

/**
 * 삭제를 막는 것들을 <b>이름과 함께</b> 찾아온다.
 *
 * <p>기존 저장소에도 비슷한 조회가 있지만 개수만 세거나 이름만 준다
 * ({@code countByServerId} · {@code findProjectNamesByServerGroupId}).
 * 화면에서 "그 항목으로 이동" 을 하려면 id 가 필요해 여기서 따로 읽는다.
 *
 * <p>목록은 {@code limit} 으로 끊고 전체 건수는 따로 센다. 배포 실행 기록은
 * 수만 건이 될 수 있어 전부 내려보내면 화면이 멈춘다.
 */
@Mapper
public interface ReferenceRepository {

    /* ── 서버 ── */

    @Select("SELECT COUNT(*) FROM remote_command_log WHERE server_id = #{serverId}")
    long countCommandLogsByServerId(Long serverId);

    @Select("""
        SELECT id,
               COALESCE(command_display, '명령 기록') AS name,
               CONCAT(DATE_FORMAT(COALESCE(started_at, created_at), '%Y-%m-%d %H:%i'),
                      ' · ', CASE WHEN success = b'1' THEN '성공' ELSE '실패' END) AS detail
        FROM remote_command_log
        WHERE server_id = #{serverId}
        ORDER BY COALESCE(started_at, created_at) DESC
        LIMIT #{limit}
        """)
    @Results({
        @Result(property = "id", column = "id"),
        @Result(property = "name", column = "name"),
        @Result(property = "detail", column = "detail")
    })
    List<ReferenceReport.Item> findCommandLogsByServerId(@Param("serverId") Long serverId,
                                                         @Param("limit") int limit);

    /* ── 서버 그룹 ── */

    @Select("""
        SELECT p.id AS id, p.name AS name,
               CASE WHEN p.active = b'1' THEN '사용 중' ELSE '비활성' END AS detail
        FROM project p
        JOIN project_server_groups psg ON psg.project_id = p.id
        WHERE psg.server_group_id = #{groupId}
        ORDER BY p.name ASC
        """)
    @Results({
        @Result(property = "id", column = "id"),
        @Result(property = "name", column = "name"),
        @Result(property = "detail", column = "detail")
    })
    List<ReferenceReport.Item> findProjectsByServerGroupId(Long groupId);

    @Select("""
        SELECT id, name,
               -- order_index 가 곧 호기 번호다. 0 은 '미지정' 이라 붙이지 않는다.
               CONCAT(COALESCE(environment, '-'), ' · ', ip_address,
                      CASE WHEN order_index > 0 THEN CONCAT(' · ', order_index, '호기') ELSE '' END) AS detail
        FROM server
        WHERE server_group_id = #{groupId}
        ORDER BY order_index ASC, name ASC
        """)
    @Results({
        @Result(property = "id", column = "id"),
        @Result(property = "name", column = "name"),
        @Result(property = "detail", column = "detail")
    })
    List<ReferenceReport.Item> findServersByServerGroupId(Long groupId);

    /* ── 승인 정책 ── */

    @Select("""
        SELECT id, name,
               CASE WHEN active = b'1' THEN '사용 중' ELSE '비활성' END AS detail
        FROM project
        WHERE approval_policy_id = #{policyId}
        ORDER BY name ASC
        """)
    @Results({
        @Result(property = "id", column = "id"),
        @Result(property = "name", column = "name"),
        @Result(property = "detail", column = "detail")
    })
    List<ReferenceReport.Item> findProjectsByPolicyId(Long policyId);

    @Select("SELECT name FROM server_group WHERE approval_policy_id = #{policyId} ORDER BY id")
    List<String> findServerGroupNamesByPolicyId(Long policyId);

    /* ── 이름 (모달 제목에 쓴다) ── */

    @Select("SELECT name FROM server WHERE id = #{id}")
    String serverName(Long id);

    @Select("SELECT name FROM server_group WHERE id = #{id}")
    String serverGroupName(Long id);

    @Select("SELECT name FROM approval_policy WHERE id = #{id}")
    String policyName(Long id);
}
