package com.portfolio.deployops.repository;

import com.portfolio.deployops.domain.Team;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface TeamRepository {

    String TEAM_SELECT = """
        SELECT t.id, t.name, t.description, t.active, t.created_at,
               l.id AS leader_id, l.login_id AS leader_login_id, l.name AS leader_name,
               l.role AS leader_role, l.active AS leader_active
        FROM teams t
        LEFT JOIN users l ON t.leader_id = l.id
        """;

    @Select(TEAM_SELECT + " WHERE t.active = b'1' ORDER BY t.name ASC")
    @Results(id = "teamWithLeaderResult", value = {
        @Result(property = "id", column = "id", id = true),
        @Result(property = "name", column = "name"),
        @Result(property = "description", column = "description"),
        @Result(property = "active", column = "active"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "leader.id", column = "leader_id"),
        @Result(property = "leader.loginId", column = "leader_login_id"),
        @Result(property = "leader.name", column = "leader_name"),
        @Result(property = "leader.role", column = "leader_role"),
        @Result(property = "leader.active", column = "leader_active")
    })
    List<Team> findByActiveTrueOrderByNameAsc();

    @Select(TEAM_SELECT + " WHERE t.id = #{id}")
    @ResultMap("teamWithLeaderResult")
    Optional<Team> findById(Long id);

    default Optional<Team> findByIdWithMembers(Long id) {
        return findById(id);
    }

    @Select("SELECT COUNT(*) > 0 FROM teams WHERE name = #{name}")
    boolean existsByName(String name);

    @Insert("""
        INSERT INTO teams (
            name, description, leader_id, active, created_at
        ) VALUES (
            #{name}, #{description}, #{leader.id}, #{active}, NOW(6)
        )
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Team team);

    @Update("""
        UPDATE teams
        SET name = #{name},
            description = #{description},
            leader_id = #{leader.id},
            active = #{active}
        WHERE id = #{id}
        """)
    int update(Team team);

    default Team save(Team team) {
        if (team.getId() == null) {
            insert(team);
        } else {
            update(team);
        }
        return team;
    }
}
