package com.my.mindot_back.common.exception;

import com.my.mindot_back.common.auth.RefreshTokenCookieManager;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// API 처리 중 exception을 낚아서 처리하는 컨트롤러
@RestControllerAdvice
@RequiredArgsConstructor
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
}
