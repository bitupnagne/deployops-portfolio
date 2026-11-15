package com.portfolio.deployops.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {

    private boolean success;
    private String message;
    private T data;
    private String errorCode;
    private List<FieldError> fieldErrors;

    /**
     * AI 답변이 길이 제한에 걸려 끊겼는가.
     *
     * <p>끊긴 답을 정상 응답과 똑같이 실어 보내면 사용자에게는 "AI 가 분석을 하다 말았다"
     * 로 보인다. 실제로 배포 로그 분석이 늘 마지막 절에서 잘리고 있었다.
     * AI 응답에만 쓰므로 나머지 응답에서는 항상 false 다.
     */
    private boolean truncated;

    public static <T> ApiResponse<T> ok(T data) {
        return ApiResponse.<T>builder().success(true).data(data).build();
    }

    /** AI 답변용 — 잘렸는지 함께 알린다. */
    public static <T> ApiResponse<T> okAi(T data, boolean truncated) {
        return ApiResponse.<T>builder().success(true).data(data).truncated(truncated).build();
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        return ApiResponse.<T>builder().success(true).message(message).data(data).build();
    }

    public static <T> ApiResponse<T> error(String errorCode, String message) {
        return ApiResponse.<T>builder().success(false).errorCode(errorCode).message(message).build();
    }

    @Getter
    @AllArgsConstructor
    public static class FieldError {
        private String field;
        private String message;
    }
}
