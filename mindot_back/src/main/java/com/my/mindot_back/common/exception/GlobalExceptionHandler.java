package com.my.mindot_back.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// API 처리 중 exception을 낚아서 처리하는 컨트롤러
@RestControllerAdvice
public class GlobalExceptionHandler {
    // 어떤 오류를 받을지
    @ExceptionHandler({InvalidRefreshTokenException.class,
            RefreshTokenReuseException.class})
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ErrorResponse handleRefreshTokenFailure(
            InvalidRefreshTokenException e
    ) {
        return ErrorResponse.builder()
                .status(HttpStatus.UNAUTHORIZED.value())
                .message("인증이 만료되었습니다. 다시 로그인해주세요.")
                .build();
    }
}
