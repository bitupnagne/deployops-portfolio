package com.portfolio.deployops.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GitlabCiVariableForm(
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @NotBlank(message = "변수 값을 입력해 주세요.")
    @Size(max = 10000, message = "변수 값은 10,000자 이내로 입력해 주세요.") String value,
    @NotBlank(message = "변경 사유를 입력해 주세요.")
    @Size(max = 200, message = "변경 사유는 200자 이내로 입력해 주세요.") String reason
) {
    @Override public String toString() { return "GitlabCiVariableForm[REDACTED]"; }
}
