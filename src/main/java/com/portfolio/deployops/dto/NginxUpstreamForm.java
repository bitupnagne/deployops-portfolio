package com.portfolio.deployops.dto;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
@Getter @Setter
public class NginxUpstreamForm {
    @NotNull private Long serverId;
    @NotNull @Min(1) @Max(65535) private Integer port;
}
