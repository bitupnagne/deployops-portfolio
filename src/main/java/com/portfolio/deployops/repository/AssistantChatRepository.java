package com.portfolio.deployops.repository;

import com.portfolio.deployops.domain.AssistantChatMessage;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AssistantChatRepository {

    @Insert("""
        INSERT INTO assistant_chat (login_id, role, content, created_at)
        VALUES (#{loginId}, #{role}, #{content}, NOW(6))
        """)
    void insert(@Param("loginId") String loginId, @Param("role") String role, @Param("content") String content);

    /** 사용자의 최근 대화 limit 건을 시간 오름차순으로 반환. */
    @Select("""
        SELECT id, login_id, role, content, created_at FROM (
            SELECT id, login_id, role, content, created_at
            FROM assistant_chat WHERE login_id = #{loginId}
            ORDER BY id DESC LIMIT #{limit}
        ) t ORDER BY id ASC
        """)
    List<AssistantChatMessage> findRecent(@Param("loginId") String loginId, @Param("limit") int limit);
}
