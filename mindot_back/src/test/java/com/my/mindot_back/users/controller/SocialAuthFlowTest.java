// 카카오 소셜 인증의 정상 가입과 state·가입 티켓 일회성을 검증

package com.my.mindot_back.users.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.my.mindot_back.redis.repository.SocialSignupTicketRepository;
import com.my.mindot_back.redis.service.IssuedRefreshToken;
import com.my.mindot_back.redis.service.RefreshTokenService;
import com.my.mindot_back.support.PostgresRedisContainerTestBase;
import com.my.mindot_back.users.client.KakaoOAuthClient;
import com.my.mindot_back.users.dto.OAuthUserInfoDto;
import com.my.mindot_back.users.repository.ConsentEventsRepository;
import com.my.mindot_back.users.repository.UsersRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "oauth.kakao.rest-api-key=test-kakao-key",
        "oauth.kakao.redirect-uri=http://localhost:3000/oauth/kakao/callback"
})
class SocialAuthFlowTest
        extends PostgresRedisContainerTestBase {

    private static final String REDIRECT_URI =
            "http://localhost:3000/oauth/kakao/callback";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UsersRepository usersRepository;

    @Autowired
    private ConsentEventsRepository consentEventsRepository;

    @Autowired
    private SocialSignupTicketRepository socialSignupTicketRepository;

    @MockitoBean
    private KakaoOAuthClient kakaoOAuthClient;

    @MockitoBean
    private RefreshTokenService refreshTokenService;

    @AfterEach
    void cleanUp() {
        consentEventsRepository.deleteAll();
        usersRepository.deleteAll();
        socialSignupTicketRepository.deleteAll();
    }

    @Test
    void validCallbackCreatesAccountAndSignupTicketCanBeUsedOnlyOnce()
            throws Exception {
        OAuthStart start = startKakaoLogin();

        when(kakaoOAuthClient.getUserInfo(
                "valid-code",
                REDIRECT_URI
        )).thenReturn(
                new OAuthUserInfoDto(
                        "kakao-user-001",
                        "social-flow@example.com",
                        "소셜 테스트"
                )
        );

        MvcResult callbackResult = performKakaoCallback(
                start,
                "valid-code",
                start.state()
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signupRequired").value(true))
                .andExpect(jsonPath("$.signupTicket").isNotEmpty())
                .andExpect(
                        jsonPath("$.email")
                                .value("social-flow@example.com")
                )
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andReturn();

        String signupTicket = responseJson(callbackResult)
                .get("signupTicket")
                .asText();

        when(refreshTokenService.issue(anyLong()))
                .thenAnswer(invocation -> {
                    Long userId = invocation.getArgument(0);

                    return new IssuedRefreshToken(
                            userId,
                            "test-refresh-token",
                            Instant.now().plusSeconds(600),
                            600
                    );
                });

        mockMvc.perform(
                        post("/api/auth/oauth/signup")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(signupJson(signupTicket))
                )
                .andExpect(status().isCreated())
                .andExpect(
                        jsonPath("$.email")
                                .value("social-flow@example.com")
                )
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(
                        jsonPath("$.userRole")
                                .value("ROLE_USER")
                );

        assertThat(
                usersRepository.findByEmail(
                        "social-flow@example.com"
                )
        ).isPresent();

        assertThat(consentEventsRepository.count()).isEqualTo(3);

        mockMvc.perform(
                        post("/api/auth/oauth/signup")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(signupJson(signupTicket))
                )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        assertThat(usersRepository.count()).isEqualTo(1);
        assertThat(consentEventsRepository.count()).isEqualTo(3);
    }

    @Test
    void mismatchedOrReusedStateIsRejected()
            throws Exception {
        OAuthStart mismatchedStart = startKakaoLogin();

        mockMvc.perform(
                        post("/api/auth/oauth/kakao")
                                .cookie(mismatchedStart.cookie())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(callbackJson(
                                        "unused-code",
                                        "different-state"
                                ))
                )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        verifyNoInteractions(kakaoOAuthClient);

        mockMvc.perform(
                        post("/api/auth/oauth/kakao")
                                .cookie(mismatchedStart.cookie())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(callbackJson(
                                        "unused-code",
                                        mismatchedStart.state()
                                ))
                )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        OAuthStart validStart = startKakaoLogin();

        when(kakaoOAuthClient.getUserInfo(
                "replay-code",
                REDIRECT_URI
        )).thenReturn(
                new OAuthUserInfoDto(
                        "kakao-replay-user",
                        "state-replay@example.com",
                        "재사용 테스트"
                )
        );

        performKakaoCallback(
                validStart,
                "replay-code",
                validStart.state()
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signupRequired").value(true));

        performKakaoCallback(
                validStart,
                "replay-code",
                validStart.state()
        )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void expiredSignupTicketIsRejected()
            throws Exception {
        OAuthStart start = startKakaoLogin();

        when(kakaoOAuthClient.getUserInfo(
                "expired-ticket-code",
                REDIRECT_URI
        )).thenReturn(
                new OAuthUserInfoDto(
                        "kakao-expired-user",
                        "expired-ticket@example.com",
                        "만료 테스트"
                )
        );

        MvcResult callbackResult = performKakaoCallback(
                start,
                "expired-ticket-code",
                start.state()
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signupTicket").isNotEmpty())
                .andReturn();

        String signupTicket = responseJson(callbackResult)
                .get("signupTicket")
                .asText();

        // Redis TTL 만료로 티켓이 제거된 상태를 재현
        socialSignupTicketRepository.deleteAll();

        mockMvc.perform(
                        post("/api/auth/oauth/signup")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(signupJson(signupTicket))
                )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        assertThat(usersRepository.count()).isZero();
        assertThat(consentEventsRepository.count()).isZero();
    }

    private OAuthStart startKakaoLogin() throws Exception {
        MvcResult result = mockMvc.perform(
                        get("/api/auth/oauth/kakao/authorize")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizationUrl").isNotEmpty())
                .andReturn();

        JsonNode response = responseJson(result);
        String authorizationUrl =
                response.get("authorizationUrl").asText();

        String state = UriComponentsBuilder
                .fromUriString(authorizationUrl)
                .build()
                .getQueryParams()
                .getFirst("state");

        String setCookie = result.getResponse()
                .getHeader(HttpHeaders.SET_COOKIE);

        assertThat(state).isNotBlank();
        assertThat(setCookie).isNotBlank();

        String cookiePair = setCookie.substring(
                0,
                setCookie.indexOf(';')
        );

        String[] cookieParts = cookiePair.split("=", 2);
        Cookie cookie = new Cookie(
                cookieParts[0],
                cookieParts[1]
        );

        cookie.setPath("/api/auth/oauth");

        return new OAuthStart(state, cookie);
    }

    private org.springframework.test.web.servlet.ResultActions
    performKakaoCallback(
            OAuthStart start,
            String code,
            String state
    ) throws Exception {
        return mockMvc.perform(
                post("/api/auth/oauth/kakao")
                        .cookie(start.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(callbackJson(code, state))
        );
    }

    private JsonNode responseJson(MvcResult result)
            throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString()
        );
    }

    private String callbackJson(
            String code,
            String state
    ) {
        return """
                {
                  "code": "%s",
                  "redirectUri": "%s",
                  "state": "%s"
                }
                """.formatted(
                code,
                REDIRECT_URI,
                state
        );
    }

    private String signupJson(String signupTicket) {
        return """
                {
                  "signupTicket": "%s",
                  "termsAgreed": true,
                  "privacyAgreed": true,
                  "aiAnalysisAgreed": true
                }
                """.formatted(signupTicket);
    }

    private record OAuthStart(
            String state,
            Cookie cookie
    ) {
    }
}