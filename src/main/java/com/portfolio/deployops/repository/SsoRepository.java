package com.portfolio.deployops.repository;

import com.portfolio.deployops.domain.User;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SsoRepository {

    String SSO_USER_SELECT = """
        SELECT u.id, u.login_id, u.password, u.name, u.role, u.active,
               u.last_login_at, u.password_changed_at, u.force_password_change,
               u.created_at, u.gitlab_token, u.gitlab_username,
               t.id AS team_id, t.name AS team_name, t.description AS team_description,
               t.active AS team_active, t.created_at AS team_created_at
        FROM users u
        LEFT JOIN teams t ON t.id = u.team_id
        """;

    @Insert("""
        INSERT INTO sso_link_code (code, deploy_user_id, expires_at, created_at)
        VALUES (#{code}, #{deployUserId}, #{expiresAt}, NOW(6))
        """)
    int insertLinkCode(@Param("code") String code,
                       @Param("deployUserId") Long deployUserId,
                       @Param("expiresAt") LocalDateTime expiresAt);

    @Select("""
        """ + SSO_USER_SELECT + """
        JOIN sso_link_code c ON c.deploy_user_id = u.id
        WHERE c.code = #{code}
          AND c.used_at IS NULL
          AND c.expires_at > NOW(6)
          AND u.active = b'1'
        """)
    @Results(id = "ssoUserResult", value = {
        @Result(property = "id", column = "id", id = true),
        @Result(property = "loginId", column = "login_id"),
        @Result(property = "password", column = "password"),
        @Result(property = "name", column = "name"),
        @Result(property = "role", column = "role"),
        @Result(property = "active", column = "active"),
        @Result(property = "lastLoginAt", column = "last_login_at"),
        @Result(property = "passwordChangedAt", column = "password_changed_at"),
        @Result(property = "forcePasswordChange", column = "force_password_change"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "gitlabToken", column = "gitlab_token"),
        @Result(property = "gitlabUsername", column = "gitlab_username"),
        @Result(property = "team.id", column = "team_id"),
        @Result(property = "team.name", column = "team_name"),
        @Result(property = "team.description", column = "team_description"),
        @Result(property = "team.active", column = "team_active"),
        @Result(property = "team.createdAt", column = "team_created_at")
    })
    Optional<User> findUserByValidLinkCode(String code);

    @Update("UPDATE sso_link_code SET used_at = NOW(6) WHERE code = #{code} AND used_at IS NULL")
    int markLinkCodeUsed(String code);

    @Insert("""
        INSERT INTO office_sso_link (office_user_id, office_username, deploy_user_id, active, linked_at, updated_at)
        VALUES (#{officeUserId}, #{officeUsername}, #{deployUserId}, b'1', NOW(6), NOW(6))
        ON DUPLICATE KEY UPDATE
            office_username = VALUES(office_username),
            deploy_user_id = VALUES(deploy_user_id),
            active = b'1',
            updated_at = NOW(6)
        """)
    int upsertOfficeLink(@Param("officeUserId") Long officeUserId,
                         @Param("officeUsername") String officeUsername,
                         @Param("deployUserId") Long deployUserId);

    @Select("""
        """ + SSO_USER_SELECT + """
        JOIN office_sso_link l ON l.deploy_user_id = u.id
        WHERE l.office_user_id = #{officeUserId}
          AND l.office_username = #{officeUsername}
          AND l.active = b'1'
          AND u.active = b'1'
        """)
    @ResultMap("ssoUserResult")
    Optional<User> findLinkedUser(@Param("officeUserId") Long officeUserId,
                                  @Param("officeUsername") String officeUsername);

    /**
     * 오피스에서 연동을 해제했을 때 이쪽 링크도 끊는다.
     *
     * <p>행을 지우지 않고 {@code active} 만 내린다 — 링크 조회·티켓 발급·알림 수신자 결정이
     * 모두 {@code active = b'1'} 로 거르므로 이것으로 충분하고, 연동했다 끊은 이력은 남는다.
     *
     * <p>사용자명이 아니라 {@code office_user_id} 로만 찾는다. 오피스에서 사용자명이 바뀌어도
     * 해제는 되어야 한다 — 여기서 못 끊으면 해제한 사용자에게 알림이 계속 간다.
     */
    @Update("UPDATE office_sso_link SET active = b'0', updated_at = NOW(6) "
        + "WHERE office_user_id = #{officeUserId} AND active = b'1'")
    int deactivateOfficeLink(@Param("officeUserId") Long officeUserId);

    @Select("""
        SELECT office_user_id
        FROM office_sso_link
        WHERE deploy_user_id = #{deployUserId}
          AND active = b'1'
        """)
    Optional<Long> findOfficeUserIdByDeployUserId(Long deployUserId);

    @Select("""
        <script>
        SELECT office_user_id
        FROM office_sso_link
        WHERE active = b'1'
          AND deploy_user_id IN
          <foreach collection="deployUserIds" item="id" open="(" separator="," close=")">
            #{id}
          </foreach>
        </script>
        """)
    List<Long> findOfficeUserIdsByDeployUserIds(@Param("deployUserIds") List<Long> deployUserIds);

    @Insert("""
        INSERT INTO sso_login_ticket (ticket, office_user_id, deploy_user_id, expires_at, created_at)
        VALUES (#{ticket}, #{officeUserId}, #{deployUserId}, #{expiresAt}, NOW(6))
        """)
    int insertTicket(@Param("ticket") String ticket,
                     @Param("officeUserId") Long officeUserId,
                     @Param("deployUserId") Long deployUserId,
                     @Param("expiresAt") LocalDateTime expiresAt);

    @Select("""
        """ + SSO_USER_SELECT + """
        JOIN sso_login_ticket st ON st.deploy_user_id = u.id
        WHERE st.ticket = #{ticket}
          AND st.used_at IS NULL
          AND st.expires_at > NOW(6)
          AND u.active = b'1'
        """)
    @ResultMap("ssoUserResult")
    Optional<User> findUserByValidTicket(String ticket);

    @Update("UPDATE sso_login_ticket SET used_at = NOW(6) WHERE ticket = #{ticket} AND used_at IS NULL AND expires_at > NOW(6)")
    int markTicketUsed(String ticket);
}
