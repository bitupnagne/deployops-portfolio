package com.portfolio.deployops.domain;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Assistant 챗봇 대화 한 줄(사용자별 이력). role = user | bot. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssistantChatMessage {
    private Long id;
    private String loginId;
    private String role;
    private String content;
    private LocalDateTime createdAt;
}
