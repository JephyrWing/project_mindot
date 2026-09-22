// AI 분석 동의가 없을 때 외부 AI 기능이 실행 전에 차단되는지 검증

package com.my.mindot_back.users.controller;

import com.my.mindot_back.common.jwt.JwtTokenProvider;
import com.my.mindot_back.records.repository.EmotionRecordsRepository;
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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AiConsentGateIntegrationTest
        extends PostgresContainerTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UsersRepository usersRepository;

    @Autowired
    private ConsentEventsRepository consentEventsRepository;

    @Autowired
    private EmotionRecordsRepository emotionRecordsRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private String accessToken;

    @BeforeEach
    void setUp() {
        Users user = usersRepository.saveAndFlush(
                Users.create(
                        "ai-consent-gate@example.com",
                        "unused-password-hash",
                        "AI 동의 게이트"
                )
        );

        consentEventsRepository.saveAllAndFlush(
                List.of(
                        ConsentEvents.grant(
                                user,
                                ConsentType.AI_ANALYSIS,
                                "ai-analysis-v1"
                        ),
                        ConsentEvents.revoke(
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
    void revokedConsentBlocksQuickRecordBeforeSaving()
            throws Exception {
        mockMvc.perform(
                        post("/api/records/quick")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                                .header(
                                        "Idempotency-Key",
                                        "ai-consent-quick-1"
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content("""
                                        {
                                          "rawText": "오늘은 기분이 좋았습니다",
                                          "inputType": "TEXT",
                                          "occurredAt": "2026-09-21T01:00:00Z"
                                        }
                                        """)
                )
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        assertThat(emotionRecordsRepository.count())
                .isZero();
    }

    @Test
    void revokedConsentBlocksSemanticSearch()
            throws Exception {
        mockMvc.perform(
                        get("/api/records/semantic-search")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                                .param(
                                        "query",
                                        "회사에서 힘들었던 일"
                                )
                )
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void revokedConsentBlocksSttBeforeAudioProcessing()
            throws Exception {
        MockMultipartFile audio =
                new MockMultipartFile(
                        "audio",
                        "voice.webm",
                        "audio/webm",
                        new byte[]{1, 2, 3}
                );

        mockMvc.perform(
                        multipart("/api/stt/transcribe")
                                .file(audio)
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void revokedConsentBlocksReflectionBeforeRecordLookup()
            throws Exception {
        mockMvc.perform(
                        post("/api/reflections/open")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                                .header(
                                        "Idempotency-Key",
                                        "ai-consent-reflection-1"
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content("""
                                        {
                                          "emotionRecordId": 999999
                                        }
                                        """)
                )
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}