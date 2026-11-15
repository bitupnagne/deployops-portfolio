package com.portfolio.deployops.domain;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class OfficeNotificationOutbox {
    private Long id;
    private Long recipientOfficeUserId;
    private Long senderOfficeUserId;
    private String type;
    private String title;
    private String body;
    private String linkUrl;
    private String eventKey;
    private String status;
    private int attempts;
    private LocalDateTime nextAttemptAt;
    private LocalDateTime deliveredAt;
    private String lastError;
    private LocalDateTime deliveryStartedAt;
    private LocalDateTime deadLetteredAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Builder
    public OfficeNotificationOutbox(Long id, Long recipientOfficeUserId, Long senderOfficeUserId,
                                    String type, String title, String body, String linkUrl, String eventKey,
                                    String status, int attempts, LocalDateTime nextAttemptAt,
                                    LocalDateTime deliveredAt, String lastError, LocalDateTime deliveryStartedAt,
                                    LocalDateTime deadLetteredAt, LocalDateTime createdAt,
                                    LocalDateTime updatedAt) {
        this.id = id;
        this.recipientOfficeUserId = recipientOfficeUserId;
        this.senderOfficeUserId = senderOfficeUserId;
        this.type = type;
        this.title = title;
        this.body = body;
        this.linkUrl = linkUrl;
        this.eventKey = eventKey;
        this.status = status;
        this.attempts = attempts;
        this.nextAttemptAt = nextAttemptAt;
        this.deliveredAt = deliveredAt;
        this.lastError = lastError;
        this.deliveryStartedAt = deliveryStartedAt;
        this.deadLetteredAt = deadLetteredAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
}
