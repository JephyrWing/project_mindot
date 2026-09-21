// 동의 현재 상태·철회·재동의·이력 페이징 계약을 실제 DB로 검증

package com.my.mindot_back.users.controller;

import com.my.mindot_back.common.jwt.JwtTokenProvider;
import com.my.mindot_back.support.PostgresContainerTestBase;
import com.my.mindot_back.users.entity.ConsentEvents;
import com.my.mindot_back.users.entity.ConsentType;
import com.my.mindot_back.users.entity.Users;
import com.my.mindot_back.users.repository.ConsentEventsRepository;
import com.my.mindot_back.users.repository.UsersRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ConsentApiIntegrationTest
        extends PostgresContainerTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UsersRepository usersRepository;

    @Autowired
    private ConsentEventsRepository consentEventsRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private Users user;
    private String accessToken;

    @BeforeEach
    void setUp() {
        user = usersRepository.saveAndFlush(
                Users.create(
                        "consent-api-test@example.com",
                        "unused-password-hash",
                        "동의 API 테스트"
                )
        );

        consentEventsRepository.saveAllAndFlush(
                List.of(
                        ConsentEvents.grant(
                                user,
                                ConsentType.TERMS,
                                "terms-v1"
                        ),
                        ConsentEvents.grant(
                                user,
                                ConsentType.PRIVACY,
                                "privacy-v1"
                        ),
                        ConsentEvents.grant(
                                user,
                                ConsentType.AI_ANALYSIS,
                                "ai-analysis-v1"
                        )
                )
        );

        accessToken =
                jwtTokenProvider.createAccessToken(user.getId());
    }

    @Test
    void currentConsentStatusReturnsLatestStateForEveryType()
            throws Exception {
        mockMvc.perform(
                        get("/api/consents")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(
                        jsonPath("$[0].consentType")
                                .value("TERMS")
                )
                .andExpect(
                        jsonPath("$[0].granted")
                                .value(true)
                )
                .andExpect(
                        jsonPath("$[0].changeable")
                                .value(false)
                )
                .andExpect(
                        jsonPath("$[1].consentType")
                                .value("PRIVACY")
                )
                .andExpect(
                        jsonPath("$[1].granted")
                                .value(true)
                )
                .andExpect(
                        jsonPath("$[1].changeable")
                                .value(false)
                )
                .andExpect(
                        jsonPath("$[2].consentType")
                                .value("AI_ANALYSIS")
                )
                .andExpect(
                        jsonPath("$[2].latestAction")
                                .value("GRANTED")
                )
                .andExpect(
                        jsonPath("$[2].granted")
                                .value(true)
                )
                .andExpect(
                        jsonPath("$[2].changeable")
                                .value(true)
                )
                .andExpect(
                        jsonPath("$[3].consentType")
                                .value("COUNSELOR_SHARE")
                )
                .andExpect(
                        jsonPath("$[3].granted")
                                .value(false)
                )
                .andExpect(
                        jsonPath("$[3].changeable")
                                .value(true)
                );
    }

    @Test
    void revokeAndGrantAppendEventsWithoutOverwritingHistory()
            throws Exception {
        assertThat(consentEventsRepository.count())
                .isEqualTo(3);

        mockMvc.perform(
                        post(
                                "/api/consents/AI_ANALYSIS/revoke"
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.latestAction")
                                .value("REVOKED")
                )
                .andExpect(
                        jsonPath("$.granted")
                                .value(false)
                );

        assertThat(consentEventsRepository.count())
                .isEqualTo(4);

        mockMvc.perform(
                        post(
                                "/api/consents/AI_ANALYSIS/revoke"
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.latestAction")
                                .value("REVOKED")
                );

        assertThat(consentEventsRepository.count())
                .isEqualTo(4);

        mockMvc.perform(
                        post(
                                "/api/consents/AI_ANALYSIS/grant"
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.latestAction")
                                .value("GRANTED")
                )
                .andExpect(
                        jsonPath("$.granted")
                                .value(true)
                );

        assertThat(consentEventsRepository.count())
                .isEqualTo(5);

        mockMvc.perform(
                        get("/api/consents/history")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                                .param("page", "0")
                                .param("size", "2")
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.content.length()")
                                .value(2)
                )
                .andExpect(
                        jsonPath("$.content[0].consentType")
                                .value("AI_ANALYSIS")
                )
                .andExpect(
                        jsonPath("$.content[0].action")
                                .value("GRANTED")
                )
                .andExpect(
                        jsonPath("$.content[1].consentType")
                                .value("AI_ANALYSIS")
                )
                .andExpect(
                        jsonPath("$.content[1].action")
                                .value("REVOKED")
                )
                .andExpect(
                        jsonPath("$.page")
                                .value(0)
                )
                .andExpect(
                        jsonPath("$.size")
                                .value(2)
                )
                .andExpect(
                        jsonPath("$.totalElements")
                                .value(5)
                )
                .andExpect(
                        jsonPath("$.totalPages")
                                .value(3)
                );
    }

    @Test
    void requiredConsentsCannotBeRevokedIndividually()
            throws Exception {
        mockMvc.perform(
                        post("/api/consents/TERMS/revoke")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        mockMvc.perform(
                        post("/api/consents/PRIVACY/revoke")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        assertThat(consentEventsRepository.count())
                .isEqualTo(3);
    }

    @Test
    void consentHistoryReturnsOnlyAuthenticatedUsersEvents()
            throws Exception {
        Users otherUser = usersRepository.saveAndFlush(
                Users.create(
                        "other-consent-user@example.com",
                        "unused-password-hash",
                        "다른 사용자"
                )
        );

        consentEventsRepository.saveAndFlush(
                ConsentEvents.grant(
                        otherUser,
                        ConsentType.COUNSELOR_SHARE,
                        "counselor-share-v1"
                )
        );

        mockMvc.perform(
                        get("/api/consents/history")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                                .param("page", "0")
                                .param("size", "10")
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.content.length()")
                                .value(3)
                )
                .andExpect(
                        jsonPath("$.totalElements")
                                .value(3)
                )
                .andExpect(
                        jsonPath(
                                "$.content[*].consentType"
                        ).value(
                                not(
                                        hasItem(
                                                "COUNSELOR_SHARE"
                                        )
                                )
                        )
                );
    }

    @Test
    void consentHistoryRejectsInvalidPageBoundaries()
            throws Exception {
        mockMvc.perform(
                        get("/api/consents/history")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                                .param("page", "-1")
                                .param("size", "20")
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        mockMvc.perform(
                        get("/api/consents/history")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                                .param("page", "0")
                                .param("size", "0")
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        mockMvc.perform(
                        get("/api/consents/history")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                                .param("page", "0")
                                .param("size", "51")
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}