// OAuth state를 HttpOnly 쿠키와 Redis에 저장하고 콜백에서 한 번만 검증하는 관리자

package com.my.mindot_back.common.auth;

import com.my.mindot_back.common.config.OAuthStateCookieProperties;
import com.my.mindot_back.users.service.OAuthProvider;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;

@Component
@RequiredArgsConstructor
public class OAuthStateCookieManager {

    private static final int STATE_BYTE_LENGTH = 32;
    private static final String STATE_KEY_PREFIX =
            "auth:oauth-state:";

    // application.yml의 auth.oauth-state-cookie 설정
    private final OAuthStateCookieProperties properties;

    // 발급한 state의 사용 여부와 만료 시간을 관리하는 Redis 작업 객체
    private final StringRedisTemplate redisTemplate;

    // 예측할 수 없는 state 생성용 난수 생성기
    private final SecureRandom secureRandom = new SecureRandom();

    /*
     * 카카오·구글 로그인 시작 시 사용할 랜덤 state를 생성하고
     * Redis에 해시값과 만료 시간을 저장
     */
    public String createState() {
        byte[] bytes = new byte[STATE_BYTE_LENGTH];
        secureRandom.nextBytes(bytes);

        String state = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(bytes);

        redisTemplate.opsForValue().set(
                stateKey(state),
                "1",
                properties.maxAge()
        );

        return state;
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
     * 콜백 요청이 오면 쿠키와 Redis의 state를 먼저 소모하고
     * 콜백 state가 모두 일치하는 경우에만 인증을 허용
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

        if (expectedState == null || callbackState == null) {
            throw invalidState();
        }

        /*
         * Redis key를 먼저 삭제하여 성공·실패 여부와 관계없이
         * 해당 로그인 요청을 다시 사용할 수 없게 처리
         */
        Boolean consumed = redisTemplate.delete(
                stateKey(expectedState)
        );

        boolean stateMatches = MessageDigest.isEqual(
                expectedState.getBytes(StandardCharsets.UTF_8),
                callbackState.getBytes(StandardCharsets.UTF_8)
        );

        if (!Boolean.TRUE.equals(consumed) || !stateMatches) {
            throw invalidState();
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

    // Redis에 state 원문이 남지 않도록 SHA-256 해시를 key로 사용
    private String stateKey(String state) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            byte[] hashed = digest.digest(
                    state.getBytes(StandardCharsets.UTF_8)
            );

            return STATE_KEY_PREFIX
                    + HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    "SHA-256을 사용할 수 없습니다",
                    e
            );
        }
    }

    private ResponseStatusException invalidState() {
        return new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "유효하지 않거나 만료된 소셜 로그인 요청입니다."
        );
    }
}