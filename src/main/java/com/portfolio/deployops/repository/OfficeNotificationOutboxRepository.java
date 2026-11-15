package com.portfolio.deployops.repository;

import com.portfolio.deployops.domain.OfficeNotificationOutbox;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface OfficeNotificationOutboxRepository {

    @Insert("""
        INSERT IGNORE INTO office_notification_outbox
          (recipient_office_user_id, sender_office_user_id, type, title, body, link_url, event_key,
           status, attempts, next_attempt_at, created_at, updated_at)
        VALUES
          (#{recipientOfficeUserId}, #{senderOfficeUserId}, #{type}, #{title}, #{body}, #{linkUrl}, #{eventKey},
           'PENDING', 0, NOW(6), NOW(6), NOW(6))
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertIfAbsent(OfficeNotificationOutbox notification);

    @Select("""
        SELECT id, recipient_office_user_id, sender_office_user_id, type, title, body, link_url, event_key,
               status, attempts, next_attempt_at, delivered_at, last_error, delivery_started_at,
               dead_lettered_at, created_at, updated_at
        FROM office_notification_outbox
        WHERE status = 'PENDING' AND next_attempt_at <= #{now}
        ORDER BY id ASC
        LIMIT #{limit}
        """)
    List<OfficeNotificationOutbox> findPending(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Select("""
        SELECT id, recipient_office_user_id, sender_office_user_id, type, title, body, link_url, event_key,
               status, attempts, next_attempt_at, delivered_at, last_error, delivery_started_at,
               dead_lettered_at, created_at, updated_at
        FROM office_notification_outbox
        WHERE id = #{id}
        """)
    Optional<OfficeNotificationOutbox> findById(@Param("id") Long id);

    @Select("""
        SELECT id, recipient_office_user_id, sender_office_user_id, type, title, body, link_url, event_key,
               status, attempts, next_attempt_at, delivered_at, last_error, delivery_started_at,
               dead_lettered_at, created_at, updated_at
        FROM office_notification_outbox
        WHERE status = #{status}
        ORDER BY updated_at DESC, id DESC
        LIMIT #{limit} OFFSET #{offset}
        """)
    List<OfficeNotificationOutbox> findByStatus(@Param("status") String status,
                                                 @Param("limit") int limit,
                                                 @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM office_notification_outbox WHERE status = #{status}")
    long countByStatus(@Param("status") String status);

    @Update("""
        UPDATE office_notification_outbox
        SET status = 'SENDING', delivery_started_at = NOW(6), updated_at = NOW(6)
        WHERE id = #{id} AND status = 'PENDING'
        """)
    int claimForDelivery(@Param("id") Long id);

    @Update("""
        UPDATE office_notification_outbox
        SET status = 'DELIVERED', delivered_at = NOW(6), delivery_started_at = NULL,
            last_error = NULL, updated_at = NOW(6)
        WHERE id = #{id} AND status = 'SENDING'
        """)
    int markDelivered(@Param("id") Long id);

    @Update("""
        UPDATE office_notification_outbox
        SET status = CASE WHEN attempts + 1 >= #{maxAttempts} THEN 'DEAD_LETTER' ELSE 'PENDING' END,
            next_attempt_at = DATE_ADD(NOW(6), INTERVAL LEAST(300, POW(2, attempts) * 5) SECOND),
            last_error = #{lastError},
            delivery_started_at = NULL,
            dead_lettered_at = CASE WHEN attempts + 1 >= #{maxAttempts} THEN NOW(6) ELSE NULL END,
            updated_at = NOW(6),
            attempts = attempts + 1
        WHERE id = #{id} AND status = 'SENDING'
        """)
    int markAttemptFailed(@Param("id") Long id, @Param("maxAttempts") int maxAttempts,
                          @Param("lastError") String lastError);

    @Update("""
        UPDATE office_notification_outbox
        SET status = 'PENDING', attempts = 0, next_attempt_at = NOW(6), last_error = NULL,
            delivery_started_at = NULL, dead_lettered_at = NULL, updated_at = NOW(6)
        WHERE id = #{id} AND status = 'DEAD_LETTER'
        """)
    int requeueDeadLetter(@Param("id") Long id);

    @Update("""
        UPDATE office_notification_outbox
        SET status = 'PENDING', delivery_started_at = NULL, next_attempt_at = NOW(6),
            last_error = 'Delivery lease expired; returned to pending queue.', updated_at = NOW(6)
        WHERE status = 'SENDING' AND delivery_started_at < #{before}
        """)
    int recoverExpiredClaims(@Param("before") LocalDateTime before);
}
