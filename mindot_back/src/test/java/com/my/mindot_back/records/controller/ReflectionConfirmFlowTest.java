// 현재 CBT 제안의 승인·거부·최종 확정과 리포트 갱신 경로를 실제 DB로 검증

package com.my.mindot_back.records.controller;

import com.my.mindot_back.ai.repository.AiJobsRepository;
import com.my.mindot_back.common.jwt.JwtTokenProvider;
import com.my.mindot_back.records.dto.EmotionRecordsConfirmRequestDto;
import com.my.mindot_back.records.entity.DistortionReviewStatus;
import com.my.mindot_back.records.entity.EmotionRecords;
import com.my.mindot_back.records.entity.InputType;
import com.my.mindot_back.records.entity.ReflectionSessionStatus;
import com.my.mindot_back.records.entity.ReflectionSessions;
import com.my.mindot_back.records.repository.EmotionRecordsRepository;
import com.my.mindot_back.records.repository.ReflectionSessionsRepository;
import com.my.mindot_back.records.repository.SessionDistortionsRepository;
import com.my.mindot_back.records.service.InsightEmbeddingService;
import com.my.mindot_back.reports.service.ReportCacheInvalidationService;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ReflectionConfirmFlowTest
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
    private ReflectionSessionsRepository reflectionSessionsRepository;

    @Autowired
    private SessionDistortionsRepository sessionDistortionsRepository;

    @Autowired
    private AiJobsRepository aiJobsRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private InsightEmbeddingService insightEmbeddingService;

    @MockitoBean
    private ReportCacheInvalidationService
            reportCacheInvalidationService;

    private Users user;
    private EmotionRecords record;
    private ReflectionSessions session;
    private String accessToken;

    @BeforeEach
    void setUp() {
        user = usersRepository.saveAndFlush(
                Users.create(
                        "reflection-confirm@example.com",
                        "unused-password-hash",
                        "CBT 확정 사용자"
                )
        );

        consentEventsRepository.saveAndFlush(
                ConsentEvents.grant(
                        user,
                        ConsentType.AI_ANALYSIS,
                        "ai-analysis-v1"
                )
        );

        record = createCompleteRecord(user);
        session = createProposalSession(user, record);

        accessToken =
                jwtTokenProvider.createAccessToken(user.getId());
    }

    @Test
    void validCurrentProposalIsConfirmedAndStored()
            throws Exception {
        mockMvc.perform(
                        post(
                                "/api/reflections/{sessionId}/confirm",
                                session.getId()
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                                .header(
                                        "Idempotency-Key",
                                        "confirm-proposal-key"
                                )
                                .header(
                                        HttpHeaders.IF_MATCH,
                                        "\"5\""
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(validConfirmJson())
                )
                .andExpect(status().isOk())
                .andExpect(
                        header().string(
                                HttpHeaders.ETAG,
                                "\"6\""
                        )
                )
                .andExpect(
                        jsonPath("$.status")
                                .value("COMPLETED")
                )
                .andExpect(
                        jsonPath("$.revision")
                                .value(6)
                )
                .andExpect(
                        jsonPath("$.currentProposal")
                                .doesNotExist()
                )
                .andExpect(
                        jsonPath(
                                "$.confirmedResult.proposalId"
                        ).value("proposal-1")
                )
                .andExpect(
                        jsonPath(
                                "$.confirmedResult.beforeText"
                        ).value("나는 발표를 완전히 망쳤다")
                )
                .andExpect(
                        jsonPath(
                                "$.confirmedResult.afterText"
                        ).value(
                                "일부 실수는 있었지만 전체 발표를 망친 것은 아니다"
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.confirmedResult.beforeBeliefStrength"
                        ).value(80)
                )
                .andExpect(
                        jsonPath(
                                "$.confirmedResult.afterBeliefStrength"
                        ).value(35)
                )
                .andExpect(
                        jsonPath(
                                "$.confirmedResult.finalEmotionIntensity"
                        ).value(3)
                )
                .andExpect(
                        jsonPath(
                                "$.confirmedResult.helpfulnessScore"
                        ).value(5)
                );

        ReflectionSessions saved =
                reflectionSessionsRepository
                        .findById(session.getId())
                        .orElseThrow();

        assertThat(saved.getStatus())
                .isEqualTo(
                        ReflectionSessionStatus.COMPLETED
                );
        assertThat(saved.getUserConfirmed())
                .isTrue();
        assertThat(saved.getAlternativeThoughtText())
                .isEqualTo(
                        "일부 실수는 있었지만 전체 발표를 망친 것은 아니다"
                );
        assertThat(saved.getBeforeBeliefStrength())
                .isEqualTo((short) 80);
        assertThat(saved.getAfterBeliefStrength())
                .isEqualTo((short) 35);
        assertThat(saved.getFinalEmotionIntensity())
                .isEqualTo((short) 3);
        assertThat(saved.getHelpfulnessScore())
                .isEqualTo((short) 5);

        var reviews =
                sessionDistortionsRepository
                        .findAllBySession_IdAndPhase(
                                session.getId(),
                                com.my.mindot_back.records.entity
                                        .DistortionPhase.BEFORE
                        );

        assertThat(reviews)
                .extracting(
                        item -> item
                                .getDistortionType()
                                .getCode(),
                        item -> item.getReviewStatus()
                )
                .containsExactlyInAnyOrder(
                        tuple(
                                "ALL_OR_NOTHING_THINKING",
                                DistortionReviewStatus.CONFIRMED
                        ),
                        tuple(
                                "MIND_READING",
                                DistortionReviewStatus.REJECTED
                        )
                );

        verify(
                reportCacheInvalidationService
        ).invalidateByOccurredAt(
                user.getId(),
                record.getOccurredAt()
        );

        verify(
                insightEmbeddingService
        ).submit(
                user.getId(),
                session.getId()
        );
    }

    @Test
    void wrongProposalOrIncompleteReviewsAreRejected()
            throws Exception {
        mockMvc.perform(
                        post(
                                "/api/reflections/{sessionId}/confirm",
                                session.getId()
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                                .header(
                                        "Idempotency-Key",
                                        "wrong-proposal-key"
                                )
                                .header(
                                        HttpHeaders.IF_MATCH,
                                        "\"5\""
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content("""
                                        {
                                          "proposalId": "wrong-proposal",
                                          "reviews": [
                                            {
                                              "code": "ALL_OR_NOTHING_THINKING",
                                              "reviewStatus": "CONFIRMED"
                                            },
                                            {
                                              "code": "MIND_READING",
                                              "reviewStatus": "REJECTED"
                                            }
                                          ],
                                          "beforeBeliefStrength": 80,
                                          "afterBeliefStrength": 35,
                                          "finalEmotionIntensity": 3,
                                          "helpfulnessScore": 5
                                        }
                                        """)
                )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        mockMvc.perform(
                        post(
                                "/api/reflections/{sessionId}/confirm",
                                session.getId()
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                                .header(
                                        "Idempotency-Key",
                                        "missing-review-key"
                                )
                                .header(
                                        HttpHeaders.IF_MATCH,
                                        "\"5\""
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content("""
                                        {
                                          "proposalId": "proposal-1",
                                          "reviews": [
                                            {
                                              "code": "ALL_OR_NOTHING_THINKING",
                                              "reviewStatus": "CONFIRMED"
                                            }
                                          ],
                                          "beforeBeliefStrength": 80,
                                          "afterBeliefStrength": 35,
                                          "finalEmotionIntensity": 3,
                                          "helpfulnessScore": 5
                                        }
                                        """)
                )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        ReflectionSessions unchanged =
                reflectionSessionsRepository
                        .findById(session.getId())
                        .orElseThrow();

        assertThat(unchanged.getStatus())
                .isEqualTo(ReflectionSessionStatus.OPEN);
        assertThat(unchanged.getUserConfirmed())
                .isFalse();
        assertThat(sessionDistortionsRepository.count())
                .isZero();
        assertThat(aiJobsRepository.count())
                .isZero();
    }

    private EmotionRecords createCompleteRecord(
            Users owner
    ) {
        EmotionRecords emotionRecord =
                EmotionRecords.createQuick(
                        owner,
                        "회의 발표에서 실수해 불안했다",
                        InputType.TEXT,
                        Instant.parse(
                                "2026-09-21T01:00:00Z"
                        )
                );

        emotionRecord.confirm(
                new EmotionRecordsConfirmRequestDto(
                        "회의에서 발표했다",
                        "나는 발표를 완전히 망쳤다",
                        "ANXIETY",
                        (short) 8,
                        List.of(),
                        "WORK",
                        "COLLEAGUE",
                        Map.of()
                )
        );

        return emotionRecordsRepository.saveAndFlush(
                emotionRecord
        );
    }

    private ReflectionSessions createProposalSession(
            Users owner,
            EmotionRecords emotionRecord
    ) {
        ReflectionSessions reflectionSession =
                ReflectionSessions.create(
                        owner,
                        emotionRecord
                );

        reflectionSession.appendInsightMessage(
                Map.of(
                        "messageNumber", 1,
                        "role", "ASSISTANT",
                        "content",
                        "이 생각을 새롭게 바라보면 어떻게 표현할 수 있을까요?",
                        "createdAt",
                        Instant.now().toString()
                )
        );

        LinkedHashMap<String, Object> proposal =
                new LinkedHashMap<>();
        proposal.put("proposalId", "proposal-1");
        proposal.put(
                "beforeText",
                "나는 발표를 완전히 망쳤다"
        );
        proposal.put(
                "afterText",
                "일부 실수는 있었지만 전체 발표를 망친 것은 아니다"
        );
        proposal.put(
                "evidenceForText",
                "발표 중 한 문장을 잊었다"
        );
        proposal.put(
                "evidenceAgainstText",
                "질문에 답했고 발표를 끝까지 마쳤다"
        );
        proposal.put(
                "suggestions",
                List.of(
                        Map.of(
                                "code",
                                "ALL_OR_NOTHING_THINKING"
                        ),
                        Map.of(
                                "code",
                                "MIND_READING"
                        )
                )
        );
        proposal.put(
                "resultFormatVersion",
                "cbt-insight-1"
        );

        LinkedHashMap<String, Object> state =
                new LinkedHashMap<>();
        state.put("revision", 5L);
        state.put(
                "phase",
                "PROPOSAL_REVIEW"
        );
        state.put(
                "currentProposal",
                proposal
        );
        state.put(
                "resultFormatVersion",
                "cbt-insight-1"
        );

        reflectionSession.replaceInsight(state);

        return reflectionSessionsRepository.saveAndFlush(
                reflectionSession
        );
    }

    private String validConfirmJson() {
        return """
                {
                  "proposalId": "proposal-1",
                  "reviews": [
                    {
                      "code": "ALL_OR_NOTHING_THINKING",
                      "reviewStatus": "CONFIRMED"
                    },
                    {
                      "code": "MIND_READING",
                      "reviewStatus": "REJECTED"
                    }
                  ],
                  "beforeBeliefStrength": 80,
                  "afterBeliefStrength": 35,
                  "finalEmotionIntensity": 3,
                  "helpfulnessScore": 5
                }
                """;
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}