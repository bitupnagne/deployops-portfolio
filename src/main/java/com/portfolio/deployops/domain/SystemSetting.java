package com.portfolio.deployops.domain;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SystemSetting {

    private String settingKey;
    private String settingValue;
    private String category;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
