package com.portfolio.deployops.dto;

public record OfficeNotificationRequest(
    Long recipientOfficeUserId,
    Long senderOfficeUserId,
    String type,
    String title,
    String body,
    String linkUrl
) {
}
