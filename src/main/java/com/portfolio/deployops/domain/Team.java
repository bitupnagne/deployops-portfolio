package com.portfolio.deployops.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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
public class Team {

    private Long id;
    private String name;
    private String description;
    private User leader;

    @Builder.Default
    private List<User> members = new ArrayList<>();

    @Builder.Default
    private boolean active = true;

    private LocalDateTime createdAt;
}
