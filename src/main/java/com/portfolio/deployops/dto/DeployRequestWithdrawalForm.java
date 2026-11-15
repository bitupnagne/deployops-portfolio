package com.portfolio.deployops.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DeployRequestWithdrawalForm(
    @NotBlank(message = "철회 사유를 입력해 주세요.")
    @Size(max = 400, message = "철회 사유는 400자 이하로 입력해 주세요.") String reason
) {}
