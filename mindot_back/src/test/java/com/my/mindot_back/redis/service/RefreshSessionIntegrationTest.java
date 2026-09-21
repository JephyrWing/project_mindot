// Refresh Token의 회전·만료·변조·재사용·동시 요청 처리를 실제 Redis로 검증

package com.my.mindot_back.redis.service;

import com.my.mindot_back.support.PostgresRedisContainerTestBase;
import com.my.mindot_back.users.entity.Users;
import com.my.mindot_back.users.repository.UsersRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockCookie;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RefreshSessionIntegrationTest
        extends PostgresRedisContainerTestBase {

    private static final String ALLOWED_ORIGIN =
            "http://localhost:3000";
    private static final String COOKIE_NAME =
            "mindot_refresh";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UsersRepository usersRepository;

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private Users activeUser;

    @BeforeEach
    void setUp() {
        activeUser = usersRepository.saveAndFlush(
                Users.create(
                        "refresh-test@example.com",
                        "unused-password-hash",
                        "토큰 테스트"
                )
        );
    }

    @AfterEach
    void cleanUp() {
        Set<String> keys = redisTemplate.keys("*");

        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }

        usersRepository.deleteAll();
    }

    @Test
    void normalRotationReturnsNewAccessTokenAndCookie()
            throws Exception {
        IssuedRefreshToken original =
                refreshTokenService.issue(activeUser.getId());

        var response = mockMvc.perform(
                        refreshRequest(original.value())
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(
                        jsonPath("$.tokenType")
                                .value("Bearer")
                )
                .andExpect(
                        jsonPath("$.expiresInSeconds")
                                .value(900)
                )
                .andExpect(
                        header().string(
                                HttpHeaders.CACHE_CONTROL,
                                containsString("no-store")
                        )
                )
                .andReturn()
                .getResponse();

        String setCookie = response.getHeader(
                HttpHeaders.SET_COOKIE
        );
        MockCookie rotatedCookie = MockCookie.parse(setCookie);

        assertThat(rotatedCookie.getName())
                .isEqualTo(COOKIE_NAME);
        assertThat(rotatedCookie.getValue())
                .isNotEqualTo(original.value());
        assertThat(rotatedCookie.isHttpOnly()).isTrue();
        assertThat(rotatedCookie.getPath())
                .isEqualTo("/api/auth");
    }

    @Test
    void expiredSessionReturnsUnauthorizedAndDeletesCookie()
            throws Exception {
        IssuedRefreshToken issued =
                refreshTokenService.issue(activeUser.getId());

        String sessionId = sessionIdOf(issued.value());

        redisTemplate.opsForHash().put(
                redisKey(sessionId),
                "absoluteExpiresAt",
                Instant.now().minusSeconds(1).toString()
        );

        assertUnauthorizedAndDeletesCookie(issued.value());
    }

    @Test
    void tamperedAndMissingSessionsReturnUnauthorized()
            throws Exception {
        IssuedRefreshToken issued =
                refreshTokenService.issue(activeUser.getId());

        assertUnauthorizedAndDeletesCookie(
                tamperSecret(issued.value())
        );

        String missingSessionToken =
                UUID.randomUUID() + ".missing-session";

        assertUnauthorizedAndDeletesCookie(
                missingSessionToken
        );
    }

    @Test
    void reusedOldTokenRevokesRotatedSession()
            throws Exception {
        IssuedRefreshToken original =
                refreshTokenService.issue(activeUser.getId());

        var firstResponse = mockMvc.perform(
                        refreshRequest(original.value())
                )
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();

        String rotatedToken = MockCookie.parse(
                firstResponse.getHeader(
                        HttpHeaders.SET_COOKIE
                )
        ).getValue();

        assertUnauthorizedAndDeletesCookie(
                original.value()
        );

        assertUnauthorizedAndDeletesCookie(
                rotatedToken
        );
    }

    @Test
    void simultaneousRotationAllowsOnlyOneSuccess()
            throws Exception {
        IssuedRefreshToken issued =
                refreshTokenService.issue(activeUser.getId());

        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);

        try {
            var first = executor.submit(() -> {
                start.await();

                return mockMvc.perform(
                                refreshRequest(issued.value())
                        )
                        .andReturn()
                        .getResponse();
            });

            var second = executor.submit(() -> {
                start.await();

                return mockMvc.perform(
                                refreshRequest(issued.value())
                        )
                        .andReturn()
                        .getResponse();
            });

            start.countDown();

            var firstResponse = first.get();
            var secondResponse = second.get();

            assertThat(
                    List.of(
                            firstResponse.getStatus(),
                            secondResponse.getStatus()
                    )
            ).containsExactlyInAnyOrder(200, 401);

            var unauthorizedResponse =
                    firstResponse.getStatus() == 401
                            ? firstResponse
                            : secondResponse;

            assertThat(
                    unauthorizedResponse.getHeader(
                            HttpHeaders.SET_COOKIE
                    )
            ).contains("Max-Age=0");
        } finally {
            executor.shutdownNow();
        }
    }

    private void assertUnauthorizedAndDeletesCookie(
            String refreshToken
    ) throws Exception {
        mockMvc.perform(refreshRequest(refreshToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
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

    private MockHttpServletRequestBuilder refreshRequest(
            String refreshToken
    ) {
        return MockMvcRequestBuilders
                .post("/api/auth/refresh")
                .header(
                        HttpHeaders.ORIGIN,
                        ALLOWED_ORIGIN
                )
                .cookie(
                        new Cookie(
                                COOKIE_NAME,
                                refreshToken
                        )
                );
    }

    private String sessionIdOf(String refreshToken) {
        return refreshToken.substring(
                0,
                refreshToken.indexOf('.')
        );
    }

    private String redisKey(String sessionId) {
        return "auth:refresh:" + sessionId;
    }

    private String tamperSecret(String refreshToken) {
        int secretStart =
                refreshToken.indexOf('.') + 1;
        char current =
                refreshToken.charAt(secretStart);
        char replacement =
                current == 'a' ? 'b' : 'a';

        return refreshToken.substring(0, secretStart)
                + replacement
                + refreshToken.substring(secretStart + 1);
    }
}