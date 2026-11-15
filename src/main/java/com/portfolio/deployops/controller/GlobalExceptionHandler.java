package com.portfolio.deployops.controller;

import com.portfolio.deployops.dto.ApiResponse;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /*
      Jackson 이 남기는 메시지에서 사용자가 고칠 수 있는 조각만 뽑아낸다.
      실제 문구 예:
        JSON parse error: Cannot deserialize value of type `...domain.PolicyType`
        from String "SEQUENTIAL": not one of the values accepted for Enum class: [ALL, ANY_ONE, STEP]

      필드 경로(["policyType"])는 항상 들어 있지는 않다 — 위 경우엔 없다.
      그래서 <b>거절된 값</b>과 <b>허용 값</b>을 우선 쓰고, 필드명은 있으면 덧붙인다.
    */
    /** {@code ["필드명"]} — 있을 때만 쓴다. */
    private static final Pattern JSON_FIELD = Pattern.compile("\\[\"([A-Za-z0-9_]+)\"\\]");
    /** {@code accepted for Enum class: [A, B, C]} */
    private static final Pattern ENUM_VALUES = Pattern.compile("accepted for Enum class: \\[([^\\]]+)\\]");
    /** {@code from String "값"} — 무엇을 보냈다가 거절당했는지. */
    private static final Pattern REJECTED = Pattern.compile("from String \"([^\"]*)\"");
    /** {@code type `a.b.C`} — 마지막 조각이 타입 이름. 내부 패키지 경로는 버린다. */
    private static final Pattern TYPE_NAME = Pattern.compile("type `([^`]+)`");

    // MVC 메서드 인가 실패도 이 advice에 도달하므로 catch-all의 500으로 처리하지 않는다.
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<?>> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(ApiResponse.error("FORBIDDEN", "이 작업을 수행할 권한이 없습니다."));
    }

    // 필터의 로그인/CSRF 처리에는 관여하지 않고 MVC에서 발생한 인증 실패만 처리한다.
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<?>> handleAuthentication(AuthenticationException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(ApiResponse.error("UNAUTHORIZED", "인증이 필요합니다. 다시 로그인해 주세요."));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<?>> handleResponseStatus(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
            .body(ApiResponse.error(e.getStatusCode().toString(), e.getReason()));
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<?>> handleBind(BindException e) {
        List<ApiResponse.FieldError> fieldErrors = e.getFieldErrors().stream()
            .map(fe -> new ApiResponse.FieldError(fe.getField(), fe.getDefaultMessage()))
            .toList();
        return ResponseEntity.badRequest().body(
            ApiResponse.<Void>builder()
                .success(false)
                .errorCode("VALIDATION_ERROR")
                .message("입력값을 확인해주세요.")
                .fieldErrors(fieldErrors)
                .build()
        );
    }

    /**
     * 요청 본문을 읽지 못한 경우 — 깨진 JSON, 없는 enum 값, 타입 불일치.
     *
     * <p>{@code HttpMessageNotReadableException} 은 {@code ErrorResponse} 를 구현하지 않아
     * 아래 catch-all 로 떨어지면 500 이 된다. 명백한 클라이언트 잘못이므로 400 으로 돌린다.
     * 예외 메시지에는 클래스명·필드명이 들어 있어 본문에 그대로 싣지 않는다.
     */
    @ExceptionHandler({HttpMessageNotReadableException.class, TypeMismatchException.class})
    public ResponseEntity<ApiResponse<?>> handleUnreadableRequest(Exception e) {
        log.warn("Malformed request body — {}: {}", e.getClass().getSimpleName(), e.getMessage());

        // "요청 형식이 올바르지 않습니다" 만 던지면 어디를 고쳐야 할지 알 수 없다.
        //   실제로 화면이 없는 enum 값(SEQUENTIAL)을 보내는 버그가 이 문구 뒤에 몇 주 숨어 있었다.
        // Jackson 메시지에서 필드 이름과 허용 값을 뽑아 사용자에게 돌려준다.
        //   클래스 경로 같은 내부 정보는 빼고, 필드명과 고를 수 있는 값만 남긴다.
        String raw = e.getMessage() == null ? "" : e.getMessage();

        String field = null;
        Matcher fm = JSON_FIELD.matcher(raw);
        while (fm.find()) {
            field = fm.group(1);   // 가장 안쪽(마지막) 필드가 실제 문제 지점이다
        }
        String allowed = group(ENUM_VALUES, raw);
        String rejected = group(REJECTED, raw);
        String type = group(TYPE_NAME, raw);
        if (type != null) {
            type = type.substring(type.lastIndexOf('.') + 1);   // 패키지 경로는 내보내지 않는다
        }
        // 필드명이 없으면 타입 이름이 대신 힌트가 된다(PolicyType → policyType).
        if (field == null && type != null) {
            field = Character.toLowerCase(type.charAt(0)) + type.substring(1);
        }

        String msg;
        if (allowed != null) {
            msg = (rejected != null ? "'" + rejected + "' 은(는) 사용할 수 없는 값입니다. " : "값이 올바르지 않습니다. ")
                + "가능한 값: " + allowed;
        } else if (field != null) {
            msg = "'" + field + "' 값을 확인해 주세요.";
        } else {
            msg = "요청 형식이 올바르지 않습니다.";
        }

        return ResponseEntity.badRequest().body(
            ApiResponse.<Void>builder()
                .success(false)
                .errorCode("VALIDATION_ERROR")
                .message(msg)
                .fieldErrors(field == null ? null
                    : List.of(new ApiResponse.FieldError(field, msg)))
                .build()
        );
    }

    /** 정규식 첫 그룹을 꺼낸다. 없으면 null. */
    private String group(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /**
     * FK·UNIQUE 제약 위반 — <b>서버 잘못이 아니라 "지금은 그 일을 할 수 없다"는 상태다.</b>
     *
     * <p>서비스 계층이 삭제 전 참조 검사를 해서 대부분은 여기까지 오지 않는다
     * ({@code ServerService}·{@code ServerGroupService} 는 409 로 미리 안내한다).
     * 이건 그 검사를 빠뜨린 경로를 위한 <b>안전망</b>이다 — 새 엔티티가 늘 때
     * 검사를 잊어도 최소한 500 경보를 오염시키지는 않게 한다.
     *
     * <p>메시지에 제약 이름을 그대로 실어 보내지 않는다. 테이블·컬럼 구조가 드러나고,
     * 어차피 운영자가 읽고 할 수 있는 일이 없다. 원인은 로그에만 남긴다.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<?>> handleDataIntegrity(DataIntegrityViolationException e) {
        String cause = e.getMostSpecificCause().getMessage();
        log.warn("제약 위반으로 거절 — {}", cause);

        // 원인이 셋인데 한 문장으로 뭉뚱그리면 사용자가 할 일을 알 수 없다.
        //   중복  이름을 바꾸면 된다
        //   참조  연결된 것을 먼저 정리해야 한다
        //   없는 값  고른 대상이 사라진 것이라 다시 골라야 한다
        // 실제로 프로젝트 이름이 겹쳤을 뿐인데 "다른 데이터가 참조하고 있다" 고 안내해
        // 무엇을 지워야 하나 찾게 만들었다.
        String lower = cause == null ? "" : cause.toLowerCase();
        String code;
        String message;
        if (lower.contains("duplicate") || lower.contains("unique")) {
            code = "DUPLICATE";
            message = "이미 같은 값이 등록되어 있습니다. 이름을 다르게 지정해 주세요.";
        } else if (lower.contains("foreign key") || lower.contains("constraint fails")
                   || lower.contains("referenced")) {
            code = "CONSTRAINT_VIOLATION";
            message = "연결된 데이터가 있어 처리할 수 없습니다. "
                + "선택한 대상이 사라졌거나, 이 항목을 참조하는 다른 데이터가 남아 있습니다.";
        } else {
            code = "CONSTRAINT_VIOLATION";
            message = "입력값이 제약 조건에 맞지 않습니다. 값을 확인해 주세요.";
        }
        // 제약 이름은 싣지 않는다 — 테이블·컬럼 구조가 드러나고 운영자가 읽고 할 수 있는 일도 없다.
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(code, message));
    }

    /**
     * 나머지 예외. 단, <b>Spring MVC 의 표준 예외는 500 으로 뭉뚱그리지 않는다.</b>
     *
     * <p>메서드 불일치(405)·잘못된 JSON(400)·미지원 Content-Type(415) 같은 것은 전부
     * 클라이언트 잘못인데, 여기서 걸러 내지 않으면 500 + {@code log.error} 로 기록된다.
     * 배포시스템은 사내 유일 배포 경로라 "500 발생률"이 곧 장애 신호인데,
     * 오탐이 섞이면 그 신호가 무의미해진다. 이 예외들은 {@code ErrorResponse} 를 구현하므로
     * 자기 상태코드를 알고 있다 — 그대로 쓰고, 4xx 는 WARN 으로만 남긴다.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<?>> handleGeneral(Exception e) {
        if (e instanceof ErrorResponse errorResponse) {
            HttpStatusCode status = errorResponse.getStatusCode();
            if (status.is4xxClientError()) {
                // 스택트레이스는 남기지 않는다 — 봇·오타로도 발생해 로그를 채운다.
                log.warn("Client error {} — {}: {}", status.value(), e.getClass().getSimpleName(), e.getMessage());
                return ResponseEntity.status(status)
                    .body(ApiResponse.error("CLIENT_ERROR", "요청을 처리할 수 없습니다."));
            }
            log.error("Server error {}", status.value(), e);
            return ResponseEntity.status(status)
                .body(ApiResponse.error("INTERNAL_ERROR", "서버 오류가 발생했습니다."));
        }
        log.error("Unhandled application exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.error("INTERNAL_ERROR", "서버 오류가 발생했습니다."));
    }
}
