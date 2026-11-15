package com.portfolio.deployops.repository;

import com.portfolio.deployops.domain.AuditLog;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

@Mapper
public interface AuditLogRepository {

    @Insert("""
        INSERT INTO audit_log (
            actor, action, target_type, target_id, target_desc, result, ip_address, created_at,
            previous_hash, entry_hash
        ) VALUES (
            #{actor}, #{action}, #{targetType}, #{targetId}, #{targetDesc}, #{result}, #{ipAddress}, #{createdAt},
            #{previousHash}, #{entryHash}
        )
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AuditLog auditLog);

    @Select("""
        <script>
        SELECT COUNT(*)
        FROM audit_log
        WHERE created_at BETWEEN #{from} AND #{to}
        <if test="actor != null and actor != ''">
          AND actor LIKE CONCAT('%', #{actor}, '%')
        </if>
        </script>
        """)
    long countWithFilter(@Param("actor") String actor,
                         @Param("from") LocalDateTime from,
                         @Param("to") LocalDateTime to);

    @Select("""
        <script>
        SELECT id, actor, action, target_type, target_id, target_desc, result, ip_address, created_at,
               previous_hash, entry_hash
        FROM audit_log
        WHERE created_at BETWEEN #{from} AND #{to}
        <if test="actor != null and actor != ''">
          AND actor LIKE CONCAT('%', #{actor}, '%')
        </if>
        ORDER BY created_at DESC
        LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    @Results(id = "auditLogResult", value = {
        @Result(property = "id", column = "id", id = true),
        @Result(property = "actor", column = "actor"),
        @Result(property = "action", column = "action"),
        @Result(property = "targetType", column = "target_type"),
        @Result(property = "targetId", column = "target_id"),
        @Result(property = "targetDesc", column = "target_desc"),
        @Result(property = "result", column = "result"),
        @Result(property = "ipAddress", column = "ip_address"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "previousHash", column = "previous_hash"),
        @Result(property = "entryHash", column = "entry_hash")
    })
    List<AuditLog> findWithFilterPage(@Param("actor") String actor,
                                      @Param("from") LocalDateTime from,
                                      @Param("to") LocalDateTime to,
                                      @Param("limit") int limit,
                                      @Param("offset") long offset);

    @Select("""
        SELECT id, actor, action, target_type, target_id, target_desc, result, ip_address, created_at,
               previous_hash, entry_hash
        FROM audit_log
        WHERE entry_hash IS NULL
        ORDER BY id ASC
        """)
    @ResultMap("auditLogResult")
    List<AuditLog> findLegacyEntries();

    @Select("""
        SELECT id, actor, action, target_type, target_id, target_desc, result, ip_address, created_at,
               previous_hash, entry_hash
        FROM audit_log
        WHERE entry_hash IS NOT NULL
        ORDER BY id ASC
        """)
    @ResultMap("auditLogResult")
    List<AuditLog> findChainedEntries();

    default AuditLog save(AuditLog auditLog) {
        insert(auditLog);
        return auditLog;
    }

    default Page<AuditLog> findWithFilter(String actor, LocalDateTime from, LocalDateTime to, Pageable pageable) {
        long total = countWithFilter(actor, from, to);
        List<AuditLog> content = findWithFilterPage(actor, from, to, pageable.getPageSize(), pageable.getOffset());
        return new PageImpl<>(content, pageable, total);
    }
}
