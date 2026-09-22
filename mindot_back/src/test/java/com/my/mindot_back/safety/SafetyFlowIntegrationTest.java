// 기록과 CBT의 위험 신호 저장·안내·즉시 중단·소유권을 실제 DB로 검증

package com.my.mindot_back.safety;

import com.my.mindot_back.ai.repository.AiJobsRepository;
import com.my.mindot_back.common.jwt.JwtTokenProvider;
import com.my.mindot_back.records.dto.EmotionRecordsConfirmRequestDto;
import com.my.mindot_back.records.dto.EmotionRecordsQuickCreateRequestDto;
import com.my.mindot_back.records.dto.InsightDtos.Prepared;
import com.my.mindot_back.records.dto.InsightDtos.SessionView;
import com.my.mindot_back.records.dto.InsightDtos.Turn;
import com.my.mindot_back.records.dto.ai.FastApiRecordAnalysisResponseDto;
import com.my.mindot_back.records.entity.EmotionRecords;
import com.my.mindot_back.records.entity.InputType;
import com.my.mindot_back.records.entity.ReflectionSessionStatus;
import com.my.mindot_back.records.entity.ReflectionSessions;
import com.my.mindot_back.records.repository.EmotionRecordsRepository;
import com.my.mindot_back.records.repository.ReflectionSessionsRepository;
import com.my.mindot_back.records.service.EmotionRecordAiTransactionService;
import com.my.mindot_back.records.service.InsightTransactions;
import com.my.mindot_back.safety.dto.SafetyNoticeResponseDto;
import com.my.mindot_back.safety.entity.RiskLevel;
import com.my.mindot_back.safety.entity.SafetyActionCode;
import com.my.mindot_back.safety.repository.SafetyEventsRepository;
import com.my.mindot_back.safety.service.SafetyEventsService;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SafetyFlowIntegrationTest
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
    private AiJobsRepository aiJobsRepository;

    @Autowired
    private SafetyEventsRepository safetyEventsRepository;

    @Autowired
    private SafetyEventsService safetyEventsService;

    @Autowired
    private EmotionRecordAiTransactionService
            emotionRecordAiTransactionService;

    @Autowired
    private InsightTransactions insightTransactions;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private Users user;
    private Users otherUser;
    private String accessToken;
    private String otherAccessToken;

    @BeforeEach
    void setUp() {
        user = usersRepository.saveAndFlush(
                Users.create(
                        "safety-flow@example.com",
                        "unused-password-hash",
                        "안전 흐름 사용자"
                )
        );

        otherUser = usersRepository.saveAndFlush(
                Users.create(
                        "other-safety-flow@example.com",
                        "unused-password-hash",
                        "다른 안전 사용자"
                )
        );

        consentEventsRepository.saveAndFlush(
                ConsentEvents.grant(
                        user,
                        ConsentType.AI_ANALYSIS,
                        "ai-analysis-v1"
                )
        );

        accessToken =
                jwtTokenProvider.createAccessToken(user.getId());

        otherAccessToken =
                jwtTokenProvider.createAccessToken(
                        otherUser.getId()
                );
    }

    @Test
    void reviewAndCrisisAnalysisCreateMatchingSafetyNotices() {
        var reviewContext =
                emotionRecordAiTransactionService
                        .createQuickRecordAndStartAiJob(
                                user.getId(),
                                quickRequest(
                                        "검토가 필요한 감정 기록",
                                        "review-key"
                                ),
                                "review-key"
                        );

        emotionRecordAiTransactionService
                .completeAiAnalysis(
                        reviewContext.emotionRecordId(),
                        reviewContext.aiJobId(),
                        analysis(
                                "REVIEW",
                                "SELF_HARM_AMBIGUOUS"
                        )
                );

        var reviewResponse =
                emotionRecordAiTransactionService
                        .savedResponse(
                                user.getId(),
                                reviewContext.emotionRecordId()
                        );

        assertThat(reviewResponse.safetyNotice())
                .isNotNull();
        assertThat(
                reviewResponse
                        .safetyNotice()
                        .riskLevel()
        ).isEqualTo("REVIEW");
        assertThat(
                reviewResponse
                        .safetyNotice()
                        .actionCode()
        ).isEqualTo("SHOW_REVIEW_NOTICE");

        var crisisContext =
                emotionRecordAiTransactionService
                        .createQuickRecordAndStartAiJob(
                                user.getId(),
                                quickRequest(
                                        "즉시 확인이 필요한 감정 기록",
                                        "crisis-key"
                                ),
                                "crisis-key"
                        );

        emotionRecordAiTransactionService
                .completeAiAnalysis(
                        crisisContext.emotionRecordId(),
                        crisisContext.aiJobId(),
                        analysis(
                                "CRISIS",
                                "IMMEDIATE_DANGER"
                        )
                );

        var crisisResponse =
                emotionRecordAiTransactionService
                        .savedResponse(
                                user.getId(),
                                crisisContext.emotionRecordId()
                        );

        assertThat(crisisResponse.safetyNotice())
                .isNotNull();
        assertThat(
                crisisResponse
                        .safetyNotice()
                        .riskLevel()
        ).isEqualTo("CRISIS");
        assertThat(
                crisisResponse
                        .safetyNotice()
                        .reasonCode()
        ).isEqualTo("IMMEDIATE_DANGER");
        assertThat(
                crisisResponse
                        .safetyNotice()
                        .actionCode()
        ).isEqualTo("SHOW_CRISIS_NOTICE");

        assertThat(safetyEventsRepository.findAll())
                .extracting(
                        item -> item.getRiskLevel(),
                        item -> item.getActionCode()
                )
                .containsExactlyInAnyOrder(
                        tuple(
                                RiskLevel.REVIEW,
                                SafetyActionCode
                                        .SHOW_REVIEW_NOTICE
                        ),
                        tuple(
                                RiskLevel.CRISIS,
                                SafetyActionCode
                                        .SHOW_CRISIS_NOTICE
                        )
                );
    }

    @Test
    void safetyStopCreatesCrisisEventStopsSessionAndEnforcesOwnership()
            throws Exception {
        ReflectionSessions session =
                createReadySession(user);

        Prepared pending =
                insightTransactions.turn(
                        user.getId(),
                        session.getId(),
                        "safety-turn-key",
                        1L,
                        new Turn(
                                "지금 당장 위험할 수 있는 상황입니다"
                        )
                );

        SessionView stopped =
                insightTransactions.complete(
                        user.getId(),
                        pending,
                        safetyStopResponse(
                                pending.request()
                        )
                );

        assertThat(stopped.status())
                .isEqualTo("SAFETY_STOPPED");
        assertThat(stopped.revision())
                .isEqualTo(3L);
        assertThat(stopped.messages())
                .hasSize(3);

        ReflectionSessions savedSession =
                reflectionSessionsRepository
                        .findById(session.getId())
                        .orElseThrow();

        assertThat(savedSession.getStatus())
                .isEqualTo(
                        ReflectionSessionStatus.SAFETY_STOPPED
                );

        assertThat(safetyEventsRepository.count())
                .isEqualTo(1);

        var safetyEvent =
                safetyEventsRepository.findAll()
                        .get(0);

        assertThat(safetyEvent.getRiskLevel())
                .isEqualTo(RiskLevel.CRISIS);
        assertThat(safetyEvent.getReasonCode())
                .isEqualTo("IMMEDIATE_DANGER");
        assertThat(safetyEvent.getActionCode())
                .isEqualTo(
                        SafetyActionCode.SHOW_CRISIS_NOTICE
                );
        assertThat(safetyEvent.getAiJobs())
                .isNotNull();

        SafetyNoticeResponseDto notice =
                safetyEventsService
                        .getLatestSafetyNotice(
                                savedSession
                                        .getEmotionRecord()
                                        .getId()
                        );

        assertThat(notice)
                .isNotNull();
        assertThat(notice.safetyEventId())
                .isEqualTo(safetyEvent.getId());
        assertThat(notice.riskLevel())
                .isEqualTo("CRISIS");
        assertThat(notice.actionCode())
                .isEqualTo("SHOW_CRISIS_NOTICE");

        mockMvc.perform(
                        post(
                                "/api/safety-events/{eventId}/notice-shown",
                                safetyEvent.getId()
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(otherAccessToken)
                                )
                )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        assertThat(
                safetyEventsRepository
                        .findById(safetyEvent.getId())
                        .orElseThrow()
                        .getNoticeShownAt()
        ).isNull();

        mockMvc.perform(
                        post(
                                "/api/safety-events/{eventId}/notice-shown",
                                safetyEvent.getId()
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isNoContent());

        assertThat(
                safetyEventsRepository
                        .findById(safetyEvent.getId())
                        .orElseThrow()
                        .getNoticeShownAt()
        ).isNotNull();
    }

    private EmotionRecordsQuickCreateRequestDto quickRequest(
            String rawText,
            String ignoredKey
    ) {
        return new EmotionRecordsQuickCreateRequestDto(
                rawText,
                InputType.TEXT,
                Instant.parse("2026-09-21T01:00:00Z")
        );
    }

    private FastApiRecordAnalysisResponseDto analysis(
            String riskLevel,
            String reasonCode
    ) {
        return new FastApiRecordAnalysisResponseDto(
                new FastApiRecordAnalysisResponseDto
                        .StructuredRecord(
                        "안전 확인 상황",
                        "안전 확인 해석",
                        "안전 확인 생각",
                        List.of(
                                new FastApiRecordAnalysisResponseDto
                                        .EmotionItem(
                                        "ANXIETY",
                                        9
                                )
                        ),
                        "심장이 빠르게 뛴다",
                        "도움을 요청했다",
                        "OTHER",
                        "OTHER"
                ),
                new FastApiRecordAnalysisResponseDto
                        .RiskAssessment(
                        riskLevel,
                        reasonCode
                ),
                new FastApiRecordAnalysisResponseDto
                        .AnalysisMeta(
                        "test-model",
                        "analyze-record-v1"
                )
        );
    }

    private ReflectionSessions createReadySession(
            Users owner
    ) {
        EmotionRecords emotionRecord =
                EmotionRecords.createQuick(
                        owner,
                        "CBT 안전 중단을 확인할 기록",
                        InputType.TEXT,
                        Instant.parse(
                                "2026-09-21T02:00:00Z"
                        )
                );

        emotionRecord.confirm(
                new EmotionRecordsConfirmRequestDto(
                        "안전 확인이 필요한 상황",
                        "지금 위험할 수 있다",
                        "ANXIETY",
                        (short) 9,
                        List.of(),
                        "OTHER",
                        "OTHER",
                        Map.of()
                )
        );

        emotionRecord =
                emotionRecordsRepository.saveAndFlush(
                        emotionRecord
                );

        ReflectionSessions session =
                ReflectionSessions.create(
                        owner,
                        emotionRecord
                );

        session.appendInsightMessage(
                Map.of(
                        "messageNumber", 1,
                        "role", "ASSISTANT",
                        "content",
                        "지금 즉시 안전을 확인해야 할 상황인가요?",
                        "createdAt",
                        Instant.now().toString()
                )
        );

        LinkedHashMap<String, Object> state =
                new LinkedHashMap<>();
        state.put("revision", 1L);
        state.put("phase", "DIALOGUE");
        state.put(
                "resultFormatVersion",
                "cbt-insight-1"
        );

        session.replaceInsight(state);

        return reflectionSessionsRepository.saveAndFlush(
                session
        );
    }

    private Map<String, Object> safetyStopResponse(
            Map<String, Object> request
    ) {
        long inputRevision =
                ((Number) request.get(
                        "inputRevision"
                )).longValue();

        @SuppressWarnings("unchecked")
        Map<String, Object> userMessage =
                (Map<String, Object>) request.get(
                        "userMessage"
                );

        int assistantMessageNumber =
                ((Number) userMessage.get(
                        "messageNumber"
                )).intValue() + 1;

        LinkedHashMap<String, Object> response =
                new LinkedHashMap<>();

        response.put(
                "sessionId",
                request.get("sessionId")
        );
        response.put(
                "requestId",
                request.get("requestId")
        );
        response.put(
                "attemptNo",
                request.get("attemptNo")
        );
        response.put(
                "inputRevision",
                inputRevision
        );
        response.put(
                "revision",
                inputRevision + 1
        );
        response.put(
                "outcome",
                "SAFETY_STOP"
        );
        response.put(
                "assistantMessage",
                Map.of(
                        "messageNumber",
                        assistantMessageNumber,
                        "role",
                        "ASSISTANT",
                        "content",
                        "지금은 성찰보다 즉시 안전을 확보하는 것이 중요합니다"
                )
        );
        response.put(
                "currentProposal",
                Map.of()
        );
        response.put(
                "issue",
                "IMMEDIATE_DANGER"
        );

        return response;
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}