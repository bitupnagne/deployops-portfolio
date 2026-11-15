package com.portfolio.deployops.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GitlabCiVariableDeleteForm(
    @NotBlank(message = "삭제 사유를 입력해 주세요.")
    @Size(max = 200, message = "삭제 사유는 200자 이내로 입력해 주세요.") String reason
) {
    @Override public String toString() { return "GitlabCiVariableDeleteForm[REDACTED]"; }
}
