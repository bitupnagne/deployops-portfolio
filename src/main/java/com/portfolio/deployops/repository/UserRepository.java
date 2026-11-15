package com.portfolio.deployops.repository;

import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.domain.UserRole;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserRepository {

    String USER_SELECT = """
        SELECT u.id, u.login_id, u.password, u.name, u.role, u.active,
               u.last_login_at, u.password_changed_at, u.force_password_change,
               u.created_at, u.gitlab_token, u.gitlab_username,
               u.otp_secret, u.otp_enabled,
               t.id AS team_id, t.name AS team_name, t.description AS team_description,
               t.active AS team_active, t.created_at AS team_created_at
        FROM users u
        LEFT JOIN teams t ON u.team_id = t.id
        """;

    @Select(USER_SELECT + " WHERE u.id = #{id}")
    @Results(id = "userWithTeamResult", value = {
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
        @Result(property = "otpSecret", column = "otp_secret"),
        @Result(property = "otpEnabled", column = "otp_enabled"),
        @Result(property = "team.id", column = "team_id"),
        @Result(property = "team.name", column = "team_name"),
        @Result(property = "team.description", column = "team_description"),
        @Result(property = "team.active", column = "team_active"),
        @Result(property = "team.createdAt", column = "team_created_at")
    })
    Optional<User> findById(Long id);

    @Select(USER_SELECT + " WHERE u.id = #{id} FOR UPDATE")
    @ResultMap("userWithTeamResult")
    Optional<User> findByIdForUpdate(Long id);

    @Select(USER_SELECT + " WHERE u.login_id = #{loginId}")
    @ResultMap("userWithTeamResult")
    Optional<User> findByLoginId(String loginId);

    @Select(USER_SELECT + " WHERE u.active = b'1' ORDER BY u.name ASC")
    @ResultMap("userWithTeamResult")
    List<User> findByActiveTrue();

    @Select(USER_SELECT + " ORDER BY u.name ASC")
    @ResultMap("userWithTeamResult")
    List<User> findAllWithTeam();

    @Select(USER_SELECT + " WHERE u.gitlab_token IS NOT NULL AND u.gitlab_token <> '' ORDER BY u.login_id ASC")
    @ResultMap("userWithTeamResult")
    List<User> findUsersWithGitlabToken();

    @Select(USER_SELECT + " WHERE u.gitlab_username = #{gitlabUsername} ORDER BY u.id ASC LIMIT 1")
    @ResultMap("userWithTeamResult")
    Optional<User> findByGitlabUsername(String gitlabUsername);

    @Update("UPDATE users SET otp_secret = #{otpSecret}, otp_enabled = #{otpEnabled} WHERE id = #{id}")
    int updateOtp(@Param("id") Long id, @Param("otpSecret") String otpSecret, @Param("otpEnabled") boolean otpEnabled);

    @Update("UPDATE users SET otp_secret = #{otpSecret}, otp_enabled = b'1' "
            + "WHERE id = #{id} AND active = b'1' AND otp_enabled = b'0'")
    int enableOtpIfDisabled(@Param("id") Long id, @Param("otpSecret") String otpSecret);

    @Select(USER_SELECT + " WHERE u.role = #{role} AND u.active = b'1' ORDER BY u.name ASC")
    @ResultMap("userWithTeamResult")
    List<User> findByRoleAndActiveTrue(UserRole role);

    @Select(USER_SELECT + " WHERE u.team_id = #{teamId} ORDER BY u.name ASC")
    @ResultMap("userWithTeamResult")
    List<User> findByTeamId(Long teamId);

    @Select("""
        <script>
        """ + USER_SELECT + """
        WHERE u.id IN
        <foreach collection="ids" item="id" open="(" separator="," close=")">
            #{id}
        </foreach>
        ORDER BY u.name ASC
        </script>
        """)
    @ResultMap("userWithTeamResult")
    List<User> findAllByIds(@Param("ids") List<Long> ids);

    @Select("SELECT COUNT(*) FROM users")
    long count();

    @Select("SELECT COUNT(*) > 0 FROM users WHERE login_id = #{loginId}")
    boolean existsByLoginId(String loginId);

    @Insert("""
        INSERT INTO users (
            login_id, password, name, team_id, role, active,
            last_login_at, password_changed_at, force_password_change,
            created_at, gitlab_token, gitlab_username
        ) VALUES (
            #{loginId}, #{password}, #{name}, #{team.id}, #{role}, #{active},
            #{lastLoginAt}, #{passwordChangedAt}, #{forcePasswordChange},
            NOW(6), #{gitlabToken}, #{gitlabUsername}
        )
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(User user);

    @Update("""
        UPDATE users
        SET name = #{name},
            team_id = #{team.id},
            role = #{role},
            active = #{active},
            last_login_at = #{lastLoginAt},
            gitlab_token = #{gitlabToken},
            gitlab_username = #{gitlabUsername}
        WHERE id = #{id}
        """)
    int update(User user);

    /** 자격증명은 잠근 최신 행을 검증한 비밀번호 변경 경로에서만 갱신한다. */
    @Update("""
        UPDATE users
        SET password = #{password},
            force_password_change = #{forcePasswordChange},
            password_changed_at = #{passwordChangedAt}
        WHERE id = #{id}
        """)
    int updatePassword(User user);

    @Update("UPDATE users SET last_login_at = #{at} WHERE id = #{id} AND active = b'1'")
    int updateLastLogin(@Param("id") Long id, @Param("at") java.time.LocalDateTime at);

    @Update("UPDATE users SET team_id = #{teamId} WHERE id = #{id}")
    int updateTeam(@Param("id") Long id, @Param("teamId") Long teamId);

    @Update("""
        UPDATE users
        SET gitlab_token = #{gitlabToken}
        WHERE id = #{id}
        """)
    int updateGitlabToken(@Param("id") Long id, @Param("gitlabToken") String gitlabToken);

    default User save(User user) {
        if (user.getId() == null) {
            insert(user);
        } else {
            update(user);
        }
        return user;
    }

    default List<User> saveAll(Iterable<User> users) {
        List<User> saved = new ArrayList<>();
        for (User user : users) {
            save(user);
            saved.add(user);
        }
        return saved;
    }

    default List<User> findAllById(Iterable<Long> ids) {
        List<Long> idList = new ArrayList<>();
        ids.forEach(idList::add);
        return idList.isEmpty() ? Collections.emptyList() : findAllByIds(idList);
    }
}
