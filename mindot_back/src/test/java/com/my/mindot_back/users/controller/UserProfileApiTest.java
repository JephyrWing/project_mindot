// 프로필 조회·닉네임 수정·회원 탈퇴 계약을 실제 DB와 Redis로 검증

package com.my.mindot_back.users.controller;

import com.my.mindot_back.common.jwt.JwtTokenProvider;
import com.my.mindot_back.redis.service.RefreshTokenService;
import com.my.mindot_back.support.PostgresRedisContainerTestBase;
import com.my.mindot_back.users.entity.Users;
import com.my.mindot_back.users.repository.UsersRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class UserProfileApiTest
        extends PostgresRedisContainerTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UsersRepository usersRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private Users user;
    private String accessToken;

    @BeforeEach
    void setUp() {
        user = usersRepository.saveAndFlush(
                Users.create(
                        "profile-test@example.com",
                        "sensitive-password-hash",
                        "기존 닉네임"
                )
        );

        accessToken =
                jwtTokenProvider.createAccessToken(user.getId());
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
    void profileReturnsOnlyAllowedUserInformation()
            throws Exception {
        mockMvc.perform(
                        get("/api/users/me")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.id")
                                .value(user.getId())
                )
                .andExpect(
                        jsonPath("$.email")
                                .value(user.getEmail())
                )
                .andExpect(
                        jsonPath("$.displayName")
                                .value("기존 닉네임")
                )
                .andExpect(
                        jsonPath("$.timezone")
                                .value("Asia/Seoul")
                )
                .andExpect(
                        jsonPath("$.locale")
                                .value("ko-KR")
                )
                .andExpect(
                        jsonPath("$.passwordHash")
                                .doesNotExist()
                )
                .andExpect(
                        jsonPath("$.kakaoAccountLink")
                                .doesNotExist()
                )
                .andExpect(
                        jsonPath("$.googleAccountLink")
                                .doesNotExist()
                )
                .andExpect(
                        jsonPath("$.status")
                                .doesNotExist()
                );
    }

    @Test
    void validNicknameIsTrimmedAndSaved()
            throws Exception {
        mockMvc.perform(
                        patch("/api/users/me")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content("""
                                        {
                                          "displayName": "  변경 닉네임  "
                                        }
                                        """)
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.displayName")
                                .value("변경 닉네임")
                );

        Users updated = usersRepository
                .findById(user.getId())
                .orElseThrow();

        assertThat(updated.getDisplayName())
                .isEqualTo("변경 닉네임");
    }

    @Test
    void nicknameBoundaryAllowsEightyAndRejectsInvalidValues()
            throws Exception {
        String eightyCharacters = "가".repeat(80);

        mockMvc.perform(
                        patch("/api/users/me")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        nicknameJson(
                                                eightyCharacters
                                        )
                                )
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.displayName")
                                .value(eightyCharacters)
                );

        mockMvc.perform(
                        patch("/api/users/me")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        nicknameJson("   ")
                                )
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        mockMvc.perform(
                        patch("/api/users/me")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        nicknameJson(
                                                "가".repeat(81)
                                        )
                                )
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        Users unchanged = usersRepository
                .findById(user.getId())
                .orElseThrow();

        assertThat(unchanged.getDisplayName())
                .isEqualTo(eightyCharacters);
    }

    @Test
    void withdrawalDeletesUserSessionsAndRejectsOldAccessToken()
            throws Exception {
        refreshTokenService.issue(user.getId());
        refreshTokenService.issue(user.getId());

        Set<String> sessionsBefore =
                redisTemplate.keys("auth:refresh:*");

        assertThat(sessionsBefore).hasSize(2);

        mockMvc.perform(
                        delete("/api/users/me")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
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

        assertThat(
                usersRepository.existsById(user.getId())
        ).isFalse();

        Set<String> sessionsAfter =
                redisTemplate.keys("auth:refresh:*");

        assertThat(sessionsAfter).isEmpty();

        mockMvc.perform(
                        get("/api/users/me")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    private String nicknameJson(String displayName) {
        return """
                {
                  "displayName": "%s"
                }
                """.formatted(displayName);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}