/*
 * Refresh Token 쿠키의 생성·조회·삭제를 한곳에서 관리
 *
 * 쿠키 이름, 경로, Secure 여부는 application.yml의
 * auth.refresh-cookie 설정값을 사용
 * 원본 Refresh Token은 JavaScript가 읽을 수 없도록 HttpOnly로 설정
 */
package com.my.mindot_back.common.auth;

import com.my.mindot_back.common.config.RefreshCookieProperties;
import com.my.mindot_back.redis.service.IssuedRefreshToken;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class RefreshTokenCookieManager {

    private final RefreshCookieProperties properties;

    public Optional<String> read(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }

        return Arrays.stream(request.getCookies())
                .filter(cookie -> properties.name().equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst();
    }

    public ResponseCookie create(IssuedRefreshToken token) {
        return baseCookie(token.value())
                .maxAge(Duration.ofSeconds(token.expiresInSeconds()))
                .build();
    }

    public ResponseCookie delete() {
        return baseCookie("")
                .maxAge(Duration.ZERO)
                .build();
    }

    private ResponseCookie.ResponseCookieBuilder baseCookie(String value) {
        return ResponseCookie.from(properties.name(), value)
                .httpOnly(true)
                .secure(properties.secure())
                .sameSite("Lax")
                .path(properties.path());
    }
}
