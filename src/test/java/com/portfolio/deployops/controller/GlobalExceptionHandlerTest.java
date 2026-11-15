package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;

/**
 * 클라이언트 잘못을 500 으로 보고하지 않는지 지킨다.
 *
 * <p>배포시스템은 사내 유일 배포 경로라 "500 발생률"이 곧 장애 신호다. 메서드 불일치나
 * 잘못된 Content-Type 까지 500 으로 세면 그 신호가 오탐으로 가득 차 진짜 장애를 덮는다.
 * 실제로 catch-all 이 {@code HttpRequestMethodNotSupportedException} 을 삼켜
 * 405 가 500 으로 나가고 있었다.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("메서드 불일치는 405 로 나간다 (500 아님)")
    void methodNotSupportedIsReportedAs405() {
        var response = handler.handleGeneral(
            new HttpRequestMethodNotSupportedException(HttpMethod.GET.name()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getErrorCode()).isEqualTo("CLIENT_ERROR");
    }

    @Test
    @DisplayName("미지원 Content-Type 은 415 로 나간다")
    void unsupportedMediaTypeIsReportedAs415() {
        var response = handler.handleGeneral(
            new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN, java.util.List.of()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    @DisplayName("진짜 서버 오류는 그대로 500")
    void genuineFailuresStayAs500() {
        var response = handler.handleGeneral(new IllegalStateException("DB 커넥션 없음"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getErrorCode()).isEqualTo("INTERNAL_ERROR");
    }

    @Test
    @DisplayName("응답 본문에 내부 정보를 담지 않는다")
    void responseBodyDoesNotLeakInternals() {
        var response = handler.handleGeneral(
            new IllegalStateException("jdbc:mariadb://198.18.8.5:3306/deployops 접속 실패"));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).doesNotContain("jdbc", "198.18.8.5");
    }

    @Test
    @DisplayName("깨진 JSON·없는 enum 값은 400 으로 나간다 (500 아님)")
    void unreadableRequestBodyIsReportedAs400() {
        var response = handler.handleUnreadableRequest(
            new org.springframework.http.converter.HttpMessageNotReadableException(
                "Cannot deserialize value of type AuthType from String \"KEY\"",
                new org.springframework.http.server.ServletServerHttpRequest(
                    new org.springframework.mock.web.MockHttpServletRequest())));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        // 예외 메시지에는 내부 클래스명이 들어 있다 — 본문에 그대로 싣지 않는다.
        assertThat(response.getBody().getMessage()).doesNotContain("AuthType", "deserialize");
    }

    /**
     * 승인 정책 저장이 "요청 형식이 올바르지 않습니다" 만 뱉고 끝나 무엇을 고쳐야 하는지
     * 알 수 없던 실제 사고를 고정한다. 화면이 옛 값 SEQUENTIAL 을 보내고 있었는데,
     * 응답만 봐서는 그 사실을 알 방법이 없었다.
     *
     * <p>아래 문자열은 서버 저널에서 그대로 떠 온 Jackson 3 의 진짜 메시지다.
     * 눈여겨볼 점은 <b>{@code ["policyType"]} 같은 필드 경로가 없다</b>는 것 —
     * 필드 경로에만 기대던 예전 구현이 아무것도 못 뽑고 조용히 포기한 이유다.
     */
    @Test
    @DisplayName("없는 enum 값이면 무엇을 보냈고 무엇이 가능한지 알려 준다")
    void enumRejectionTellsWhichValuesAreAllowed() {
        var response = handler.handleUnreadableRequest(
            new org.springframework.http.converter.HttpMessageNotReadableException(
                "JSON parse error: Cannot deserialize value of type "
                    + "`com.portfolio.deployops.domain.PolicyType` from String \"SEQUENTIAL\": "
                    + "not one of the values accepted for Enum class: [ALL, ANY_ONE, STEP]",
                new org.springframework.http.server.ServletServerHttpRequest(
                    new org.springframework.mock.web.MockHttpServletRequest())));

        assertThat(response.getBody()).isNotNull();
        String message = response.getBody().getMessage();
        assertThat(message)
            .as("보낸 값과 허용 값이 모두 보여야 사용자가 스스로 고칠 수 있다")
            .contains("SEQUENTIAL")
            .contains("ALL", "ANY_ONE", "STEP");
        assertThat(message)
            .as("패키지 경로는 사용자에게 아무 의미가 없고 내부 구조만 드러낸다")
            .doesNotContain("com.portfolio");

        // 화면이 붉은 테두리를 칠할 수 있도록 어느 칸인지도 함께 돌려준다.
        assertThat(response.getBody().getFieldErrors())
            .isNotNull()
            .extracting("field")
            .containsExactly("policyType");
    }
}
