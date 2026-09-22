// 공개 API와 JWT 보호 API의 인증 경계를 실제 보안 필터로 검증

package com.my.mindot_back.common.security;

import com.my.mindot_back.common.jwt.JwtTokenProvider;
import com.my.mindot_back.support.PostgresContainerTestBase;
import com.my.mindot_back.users.entity.Users;
import com.my.mindot_back.users.repository.UsersRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Date;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SecurityApiIntegrationTest
        extends PostgresContainerTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UsersRepository usersRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private Users activeUser;
    private String validToken;

    @BeforeEach
    void setUp() {
        activeUser = usersRepository.saveAndFlush(
                Users.create(
                        "security-test@example.com",
                        "unused-password-hash",
                        "보안 테스트"
                )
        );

        validToken = jwtTokenProvider.createAccessToken(
                activeUser.getId()
        );
    }

    @Test
    void publicLoginApiDoesNotRequireAccessToken()
            throws Exception {
        mockMvc.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}")
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void protectedApisRejectRequestsWithoutToken()
            throws Exception {
        mockMvc.perform(get("/api/records"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        mockMvc.perform(get("/api/consents"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void protectedApisAcceptValidActiveUserToken()
            throws Exception {
        mockMvc.perform(
                        get("/api/records")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(validToken)
                                )
                )
                .andExpect(status().isOk());

        mockMvc.perform(
                        get("/api/consents")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(validToken)
                                )
                )
                .andExpect(status().isOk());
    }

    @Test
    void protectedApisRejectExpiredToken()
            throws Exception {
        assertProtectedApisReject(
                createExpiredToken(activeUser.getId())
        );
    }

    @Test
    void protectedApisRejectTamperedToken()
            throws Exception {
        assertProtectedApisReject(
                tamperSignature(validToken)
        );
    }

    private void assertProtectedApisReject(String token)
            throws Exception {
        mockMvc.perform(
                        get("/api/records")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(token)
                                )
                )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        mockMvc.perform(
                        get("/api/consents")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(token)
                                )
                )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    private String createExpiredToken(Long userId) {
        Instant now = Instant.now();

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuedAt(Date.from(now.minusSeconds(120)))
                .expiration(Date.from(now.minusSeconds(60)))
                .signWith(
                        Keys.hmacShaKeyFor(
                                Decoders.BASE64.decode(
                                        TEST_JWT_SECRET
                                )
                        )
                )
                .compact();
    }

    private String tamperSignature(String token) {
        int signatureStart = token.lastIndexOf('.') + 1;
        char current = token.charAt(signatureStart);
        char replacement = current == 'a' ? 'b' : 'a';

        return token.substring(0, signatureStart)
                + replacement
                + token.substring(signatureStart + 1);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}