package com.portfolio.deployops.domain;
import lombok.*;
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class NginxUpstream {
    private String purpose;
    private int position;
    private Long sourceServerId;
    private String host;
    private int port;
}
