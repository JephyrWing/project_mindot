package com.my.mindot_back.common.exception;

import com.my.mindot_back.common.auth.RefreshTokenCookieManager;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

// API 처리 중 exception을 낚아서 처리하는 컨트롤러
@RestControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class GlobalExceptionHandler {
    private final RefreshTokenCookieManager cookieManager;

    // 어떤 오류를 받을지
    @ExceptionHandler({InvalidRefreshTokenException.class,
            RefreshTokenReuseException.class})
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ErrorResponse handleRefreshTokenFailure(
            RuntimeException e,
            HttpServletResponse response
    ) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.addHeader(
                HttpHeaders.SET_COOKIE,
                cookieManager.delete().toString()
        );

        return ErrorResponse.builder()
                .status(HttpStatus.UNAUTHORIZED.value())
                .message("인증이 만료되었습니다. 다시 로그인해주세요.")
                .build();
    }

    // @Valid 검증 실패 시 첫번째 필드 오류를 공통 JSON 형식으로 반환
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleValidationException(
            MethodArgumentNotValidException exception
    ) {
        String message = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("요청값이 올바르지 않습니다.");

        return ErrorResponse.builder()
                .status(HttpStatus.BAD_REQUEST.value())
                .message(message)
                .build();
    }

    // JSON 형식 자체가 잘못된 경우 공통 JSON 형식으로 반환
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleUnreadableRequest(
            HttpMessageNotReadableException exception
    ) {
        return ErrorResponse.builder()
                .status(HttpStatus.BAD_REQUEST.value())
                .message("요청 JSON 형식이 올바르지 않습니다.")
                .build();
    }

    // Service에서 발생한 HTTP 상태 예외를 프론트 공통 오류 형식으로 반환
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatusException(
            ResponseStatusException exception
    ) {
        return ResponseEntity
                .status(exception.getStatusCode())
                .body(
                        ErrorResponse.builder()
                                .status(exception.getStatusCode().value())
                                .message(exception.getReason())
                                .build()
                );
    }

    // 예상하지 못한 서버 오류는 내부 원인을 노출하지 않고 공통 JSON으로 반환
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleUnexpectedException(
            Exception exception
    ) {
        log.error("처리되지 않은 서버 오류", exception);

        return ErrorResponse.builder()
                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .message("서버 내부 오류가 발생했습니다.")
                .build();
    }

    // enum 또는 숫자 쿼리 파라미터 형식이 잘못된 경우 공통 JSON 형식으로 반환
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleQueryParameterTypeMismatch(
            MethodArgumentTypeMismatchException exception
    ) {
        return ErrorResponse.builder()
                .status(HttpStatus.BAD_REQUEST.value())
                .message("요청 쿼리 값이 올바르지 않습니다.")
                .build();
    }
}
