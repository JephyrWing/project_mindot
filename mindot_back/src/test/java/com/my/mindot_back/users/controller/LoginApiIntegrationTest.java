// 이메일 로그인 성공 응답과 계정 정보가 노출되지 않는 실패 응답을 검증

package com.my.mindot_back.users.controller;

import com.my.mindot_back.redis.service.IssuedRefreshToken;
import com.my.mindot_back.redis.service.RefreshTokenService;
import com.my.mindot_back.support.PostgresContainerTestBase;
import com.my.mindot_back.users.entity.AccountStatus;
import com.my.mindot_back.users.entity.Users;
import com.my.mindot_back.users.repository.UsersRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class LoginApiIntegrationTest
        extends PostgresContainerTestBase {

    private static final String PASSWORD = "password123";
    private static final String GENERIC_LOGIN_ERROR =
            "이메일 또는 비밀번호가 올바르지 않습니다.";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UsersRepository usersRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @MockitoBean
    private RefreshTokenService refreshTokenService;

    @AfterEach
    void cleanUp() {
        usersRepository.deleteAll();
    }

    @Test
    void successfulLoginReturnsAccessTokenAndHttpOnlyRefreshCookie()
            throws Exception {
        Users user = savePasswordUser(
                "login-success@example.com",
                AccountStatus.ACTIVE
        );

        when(refreshTokenService.issue(user.getId()))
                .thenReturn(
                        new IssuedRefreshToken(
                                user.getId(),
                                "refresh-token-value",
                                Instant.now().plusSeconds(600),
                                600
                        )
                );

        mockMvc.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(loginJson(
                                        user.getEmail(),
                                        PASSWORD
                                ))
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
                        jsonPath("$.accessToken")
                                .isNotEmpty()
                )
                .andExpect(
                        jsonPath("$.userRole")
                                .value("ROLE_USER")
                )
                .andExpect(
                        jsonPath("$.refreshToken")
                                .doesNotExist()
                )
                .andExpect(
                        header().string(
                                HttpHeaders.CACHE_CONTROL,
                                containsString("no-store")
                        )
                )
                .andExpect(
                        header().string(
                                HttpHeaders.SET_COOKIE,
                                containsString(
                                        "mindot_refresh=refresh-token-value"
                                )
                        )
                )
                .andExpect(
                        header().string(
                                HttpHeaders.SET_COOKIE,
                                containsString("HttpOnly")
                        )
                )
                .andExpect(
                        header().string(
                                HttpHeaders.SET_COOKIE,
                                containsString("Path=/api/auth")
                        )
                )
                .andExpect(
                        header().string(
                                HttpHeaders.SET_COOKIE,
                                containsString("SameSite=Lax")
                        )
                );
    }

    @Test
    void loginFailuresReturnSameUnauthorizedResponse()
            throws Exception {
        Users passwordUser = savePasswordUser(
                "password-user@example.com",
                AccountStatus.ACTIVE
        );

        usersRepository.saveAndFlush(
                Users.createSocial(
                        "social-only@example.com",
                        "소셜 전용 사용자"
                )
        );

        savePasswordUser(
                "suspended@example.com",
                AccountStatus.SUSPENDED
        );

        savePasswordUser(
                "withdrawn@example.com",
                AccountStatus.WITHDRAWN
        );

        assertUnauthorized(
                passwordUser.getEmail(),
                "wrong-password"
        );
        assertUnauthorized(
                "unknown@example.com",
                PASSWORD
        );
        assertUnauthorized(
                "social-only@example.com",
                PASSWORD
        );
        assertUnauthorized(
                "suspended@example.com",
                PASSWORD
        );
        assertUnauthorized(
                "withdrawn@example.com",
                PASSWORD
        );

        verifyNoInteractions(refreshTokenService);
    }

    private void assertUnauthorized(
            String email,
            String password
    ) throws Exception {
        mockMvc.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(loginJson(email, password))
                )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(
                        jsonPath("$.message")
                                .value(GENERIC_LOGIN_ERROR)
                )
                .andExpect(
                        header().doesNotExist(
                                HttpHeaders.SET_COOKIE
                        )
                );
    }

    private Users savePasswordUser(
            String email,
            AccountStatus status
    ) {
        Users saved = usersRepository.saveAndFlush(
                Users.create(
                        email,
                        passwordEncoder.encode(PASSWORD),
                        "로그인 테스트"
                )
        );

        if (status != AccountStatus.ACTIVE) {
            jdbcTemplate.update(
                    """
                    UPDATE users
                    SET status = ?
                    WHERE id = ?
                    """,
                    status.name(),
                    saved.getId()
            );

            entityManager.clear();

            return usersRepository.findById(saved.getId())
                    .orElseThrow();
        }

        return saved;
    }

    private String loginJson(
            String email,
            String password
    ) {
        return """
                {
                  "email": "%s",
                  "password": "%s"
                }
                """.formatted(email, password);
    }
}