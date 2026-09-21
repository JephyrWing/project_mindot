// 로그아웃의 멱등성과 허용 Origin 기반 CORS 경계를 실제 필터로 검증

package com.my.mindot_back.common.security;

import com.my.mindot_back.redis.service.IssuedRefreshToken;
import com.my.mindot_back.redis.service.RefreshTokenService;
import com.my.mindot_back.support.PostgresRedisContainerTestBase;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SessionBoundaryApiTest
        extends PostgresRedisContainerTestBase {

    private static final String ALLOWED_ORIGIN =
            "http://localhost:3000";
    private static final String BLOCKED_ORIGIN =
            "https://blocked.example";
    private static final String COOKIE_NAME =
            "mindot_refresh";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @AfterEach
    void cleanUp() {
        Set<String> keys = redisTemplate.keys("*");

        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    void logoutWithoutCookieIsIdempotentAndDeletesCookie()
            throws Exception {
        performLogoutWithoutCookie();
        performLogoutWithoutCookie();
    }

    @Test
    void logoutRevokesExistingSessionAndRemainsIdempotent()
            throws Exception {
        IssuedRefreshToken issued =
                refreshTokenService.issue(1L);

        String redisKey =
                "auth:refresh:" + sessionIdOf(issued.value());

        assertThat(redisTemplate.hasKey(redisKey))
                .isTrue();

        performLogout(issued.value());

        assertThat(redisTemplate.hasKey(redisKey))
                .isFalse();

        performLogout(issued.value());

        assertThat(redisTemplate.hasKey(redisKey))
                .isFalse();
    }

    @Test
    void allowedOriginPreflightReturnsCredentialCorsHeaders()
            throws Exception {
        mockMvc.perform(
                        options("/api/auth/refresh")
                                .header(
                                        HttpHeaders.ORIGIN,
                                        ALLOWED_ORIGIN
                                )
                                .header(
                                        HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD,
                                        "POST"
                                )
                                .header(
                                        HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
                                        "content-type"
                                )
                )
                .andExpect(status().isOk())
                .andExpect(
                        header().string(
                                HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                                ALLOWED_ORIGIN
                        )
                )
                .andExpect(
                        header().string(
                                HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS,
                                "true"
                        )
                )
                .andExpect(
                        header().string(
                                HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS,
                                containsString("POST")
                        )
                );
    }

    @Test
    void blockedOriginPreflightIsRejected()
            throws Exception {
        mockMvc.perform(
                        options("/api/auth/refresh")
                                .header(
                                        HttpHeaders.ORIGIN,
                                        BLOCKED_ORIGIN
                                )
                                .header(
                                        HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD,
                                        "POST"
                                )
                )
                .andExpect(status().isForbidden())
                .andExpect(
                        header().doesNotExist(
                                HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN
                        )
                );
    }

    @Test
    void blockedOriginLogoutDoesNotRevokeSession()
            throws Exception {
        IssuedRefreshToken issued =
                refreshTokenService.issue(1L);

        String redisKey =
                "auth:refresh:" + sessionIdOf(issued.value());

        mockMvc.perform(
                        post("/api/auth/logout")
                                .header(
                                        HttpHeaders.ORIGIN,
                                        BLOCKED_ORIGIN
                                )
                                .cookie(
                                        new Cookie(
                                                COOKIE_NAME,
                                                issued.value()
                                        )
                                )
                )
                .andExpect(status().isForbidden())
                .andExpect(
                        header().doesNotExist(
                                HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN
                        )
                );

        assertThat(redisTemplate.hasKey(redisKey))
                .isTrue();
    }

    private void performLogoutWithoutCookie()
            throws Exception {
        mockMvc.perform(
                        post("/api/auth/logout")
                                .header(
                                        HttpHeaders.ORIGIN,
                                        ALLOWED_ORIGIN
                                )
                )
                .andExpect(status().isNoContent())
                .andExpect(
                        header().string(
                                HttpHeaders.SET_COOKIE,
                                containsString("Max-Age=0")
                        )
                )
                .andExpect(
                        header().string(
                                HttpHeaders.CACHE_CONTROL,
                                containsString("no-store")
                        )
                );
    }

    private void performLogout(String refreshToken)
            throws Exception {
        mockMvc.perform(
                        post("/api/auth/logout")
                                .header(
                                        HttpHeaders.ORIGIN,
                                        ALLOWED_ORIGIN
                                )
                                .cookie(
                                        new Cookie(
                                                COOKIE_NAME,
                                                refreshToken
                                        )
                                )
                )
                .andExpect(status().isNoContent())
                .andExpect(
                        header().string(
                                HttpHeaders.SET_COOKIE,
                                containsString("Max-Age=0")
                        )
                )
                .andExpect(
                        header().string(
                                HttpHeaders.CACHE_CONTROL,
                                containsString("no-store")
                        )
                );
    }

    private String sessionIdOf(String refreshToken) {
        return refreshToken.substring(
                0,
                refreshToken.indexOf('.')
        );
    }
}