// OAuth state를 HttpOnly 쿠키에 저장하고 콜백에서 한 번만 검증하는 관리자
package com.my.mindot_back.common.auth;

import com.my.mindot_back.common.config.OAuthStateCookieProperties;
import com.my.mindot_back.users.service.OAuthProvider;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

@Component
@RequiredArgsConstructor
public class OAuthStateCookieManager {

    private static final int STATE_BYTE_LENGTH = 32;

    // application.yml의 auth.oauth-state-cookie 설정
    private final OAuthStateCookieProperties properties;

    // 예측할 수 없는 state 생성용 난수 생성기
    private final SecureRandom secureRandom = new SecureRandom();

    // 카카오·구글 로그인 시작 시 URL에 포함할 랜덤 state 생성
    public String createState() {
        byte[] bytes = new byte[STATE_BYTE_LENGTH];
        secureRandom.nextBytes(bytes);

        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(bytes);
    }

    // 제공자별 독립 HttpOnly state 쿠키 생성
    public ResponseCookie create(
            OAuthProvider provider,
            String state
    ) {
        return ResponseCookie.from(cookieName(provider), state)
                .httpOnly(true)
                .secure(properties.secure())
                .sameSite(properties.sameSite())
                .path(properties.path())
                .maxAge(properties.maxAge())
                .build();
    }

    /*
     * 콜백 state와 HttpOnly 쿠키 state를 비교하고,
     * 성공·실패와 무관하게 쿠키를 즉시 삭제해 재사용을 막음
     */
    public void validateAndConsume(
            OAuthProvider provider,
            String callbackState,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        String expectedState = read(provider, request);

        response.addHeader(
                HttpHeaders.SET_COOKIE,
                delete(provider).toString()
        );

        if (expectedState == null
                || callbackState == null
                || !MessageDigest.isEqual(
                expectedState.getBytes(StandardCharsets.UTF_8),
                callbackState.getBytes(StandardCharsets.UTF_8)
        )) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "유효하지 않거나 만료된 소셜 로그인 요청입니다."
            );
        }
    }

    // 요청 쿠키에서 해당 제공자의 state 값 조회
    private String read(
            OAuthProvider provider,
            HttpServletRequest request
    ) {
        Cookie[] cookies = request.getCookies();

        if (cookies == null) {
            return null;
        }

        for (Cookie cookie : cookies) {
            if (cookieName(provider).equals(cookie.getName())) {
                return cookie.getValue();
            }
        }

        return null;
    }

    // 사용한 state 쿠키를 즉시 만료시키는 Set-Cookie 생성
    private ResponseCookie delete(OAuthProvider provider) {
        return ResponseCookie.from(cookieName(provider), "")
                .httpOnly(true)
                .secure(properties.secure())
                .sameSite(properties.sameSite())
                .path(properties.path())
                .maxAge(Duration.ZERO)
                .build();
    }

    // 카카오·구글 동시 로그인 시작 시 state가 덮어써지지 않도록 쿠키명 분리
    private String cookieName(OAuthProvider provider) {
        return provider == OAuthProvider.KAKAO
                ? "mindot_oauth_kakao_state"
                : "mindot_oauth_google_state";
    }
}