package com.portfolio.deployops.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.List;

@Getter @Setter
public class TeamForm {
    private String name;
    private String description;
    private Long leaderId;
    private List<Long> memberIds;
}
