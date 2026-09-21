// CBT 취소·완료·안전 중단 이후 변경 차단과 지연 응답 무시를 실제 DB로 검증

package com.my.mindot_back.records.controller;

import com.my.mindot_back.ai.entity.AiJobStatus;
import com.my.mindot_back.ai.repository.AiJobsRepository;
import com.my.mindot_back.common.jwt.JwtTokenProvider;
import com.my.mindot_back.records.dto.EmotionRecordsConfirmRequestDto;
import com.my.mindot_back.records.dto.InsightDtos.Prepared;
import com.my.mindot_back.records.dto.InsightDtos.SessionView;
import com.my.mindot_back.records.dto.InsightDtos.Turn;
import com.my.mindot_back.records.dto.ReflectionSessionConfirmRequestDto;
import com.my.mindot_back.records.entity.EmotionRecords;
import com.my.mindot_back.records.entity.InputType;
import com.my.mindot_back.records.entity.ReflectionSessionStatus;
import com.my.mindot_back.records.entity.ReflectionSessions;
import com.my.mindot_back.records.repository.EmotionRecordsRepository;
import com.my.mindot_back.records.repository.ReflectionSessionsRepository;
import com.my.mindot_back.records.service.InsightEmbeddingService;
import com.my.mindot_back.records.service.InsightTransactions;
import com.my.mindot_back.support.PostgresContainerTestBase;
import com.my.mindot_back.users.entity.ConsentEvents;
import com.my.mindot_back.users.entity.ConsentType;
import com.my.mindot_back.users.entity.Users;
import com.my.mindot_back.users.repository.ConsentEventsRepository;
import com.my.mindot_back.users.repository.UsersRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ReflectionTerminalFlowTest
        extends PostgresContainerTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InsightTransactions insightTransactions;

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
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private InsightEmbeddingService insightEmbeddingService;

    private Users user;
    private String accessToken;

    @BeforeEach
    void setUp() {
        cleanDatabase();

        user = usersRepository.saveAndFlush(
                Users.create(
                        "reflection-terminal@example.com",
                        "unused-password-hash",
                        "CBT 종료 사용자"
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
    }

    @AfterEach
    void cleanUp() {
        cleanDatabase();
    }

    @Test
    void idleSessionCanBeCancelledAndRejectsLaterChanges()
            throws Exception {
        ReflectionSessions session =
                createReadySession(
                        user,
                        "일반 취소 기록"
                );

        mockMvc.perform(
                        post(
                                "/api/reflections/{sessionId}/cancel",
                                session.getId()
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                                .header(
                                        "Idempotency-Key",
                                        "idle-cancel-key"
                                )
                                .header(
                                        HttpHeaders.IF_MATCH,
                                        "\"1\""
                                )
                )
                .andExpect(status().isOk())
                .andExpect(
                        header().string(
                                HttpHeaders.ETAG,
                                "\"2\""
                        )
                )
                .andExpect(
                        jsonPath("$.status")
                                .value("CANCELLED")
                )
                .andExpect(
                        jsonPath("$.revision")
                                .value(2)
                );

        ReflectionSessions cancelled =
                reflectionSessionsRepository
                        .findById(session.getId())
                        .orElseThrow();

        assertThat(cancelled.getStatus())
                .isEqualTo(
                        ReflectionSessionStatus.CANCELLED
                );

        assertTerminalSessionRejectsChanges(
                session.getId(),
                2L,
                "cancelled"
        );

        verify(
                insightEmbeddingService
        ).closeRuntime(session.getId());
    }

    @Test
    void processingSessionCancellationFailsJobAndIgnoresLateResult()
            throws Exception {
        ReflectionSessions session =
                createReadySession(
                        user,
                        "처리 중 취소 기록"
                );

        Prepared pending = insightTransactions.turn(
                user.getId(),
                session.getId(),
                "processing-turn-key",
                1L,
                new Turn("취소 전에 저장된 사용자 답변")
        );

        mockMvc.perform(
                        post(
                                "/api/reflections/{sessionId}/cancel",
                                session.getId()
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                                .header(
                                        "Idempotency-Key",
                                        "processing-cancel-key"
                                )
                                .header(
                                        HttpHeaders.IF_MATCH,
                                        "\"2\""
                                )
                )
                .andExpect(status().isOk())
                .andExpect(
                        header().string(
                                HttpHeaders.ETAG,
                                "\"3\""
                        )
                )
                .andExpect(
                        jsonPath("$.status")
                                .value("CANCELLED")
                )
                .andExpect(
                        jsonPath("$.revision")
                                .value(3)
                )
                .andExpect(
                        jsonPath("$.messages.length()")
                                .value(2)
                );

        assertThat(
                aiJobsRepository
                        .findById(pending.jobId())
                        .orElseThrow()
                        .getStatus()
        ).isEqualTo(AiJobStatus.FAILED);

        SessionView afterLateResult =
                insightTransactions.complete(
                        user.getId(),
                        pending,
                        validTurnResponse(
                                pending.request()
                        )
                );

        assertThat(afterLateResult.status())
                .isEqualTo("CANCELLED");
        assertThat(afterLateResult.revision())
                .isEqualTo(3L);
        assertThat(afterLateResult.messages())
                .hasSize(2);

        assertThat(afterLateResult.messages())
                .extracting(message -> message.get("role"))
                .containsExactly(
                        "ASSISTANT",
                        "USER"
                );

        ReflectionSessions saved =
                reflectionSessionsRepository
                        .findById(session.getId())
                        .orElseThrow();

        assertThat(saved.getStatus())
                .isEqualTo(
                        ReflectionSessionStatus.CANCELLED
                );
        assertThat(saved.getQuestionAnswers())
                .hasSize(2);
    }

    @Test
    void completedAndSafetyStoppedSessionsRejectAllChanges()
            throws Exception {
        ReflectionSessions completed =
                createCompletedSession(
                        user,
                        "완료된 CBT 기록"
                );

        ReflectionSessions safetyStopped =
                createSafetyStoppedSession(
                        user,
                        "안전 중단 CBT 기록"
                );

        assertTerminalSessionRejectsChanges(
                completed.getId(),
                1L,
                "completed"
        );

        assertTerminalSessionRejectsChanges(
                safetyStopped.getId(),
                1L,
                "safety-stopped"
        );

        assertThat(
                reflectionSessionsRepository
                        .findById(completed.getId())
                        .orElseThrow()
                        .getStatus()
        ).isEqualTo(
                ReflectionSessionStatus.COMPLETED
        );

        assertThat(
                reflectionSessionsRepository
                        .findById(safetyStopped.getId())
                        .orElseThrow()
                        .getStatus()
        ).isEqualTo(
                ReflectionSessionStatus.SAFETY_STOPPED
        );
    }

    private void assertTerminalSessionRejectsChanges(
            Long sessionId,
            long revision,
            String keyPrefix
    ) throws Exception {
        mockMvc.perform(
                        post(
                                "/api/reflections/{sessionId}/turn",
                                sessionId
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                                .header(
                                        "Idempotency-Key",
                                        keyPrefix + "-turn"
                                )
                                .header(
                                        HttpHeaders.IF_MATCH,
                                        "\"" + revision + "\""
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content("""
                                        {
                                          "answer": "종료 뒤 전송한 답변"
                                        }
                                        """)
                )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        mockMvc.perform(
                        post(
                                "/api/reflections/{sessionId}/retry",
                                sessionId
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                                .header(
                                        "Idempotency-Key",
                                        keyPrefix + "-retry"
                                )
                                .header(
                                        HttpHeaders.IF_MATCH,
                                        "\"" + revision + "\""
                                )
                )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        mockMvc.perform(
                        post(
                                "/api/reflections/{sessionId}/confirm",
                                sessionId
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                                .header(
                                        "Idempotency-Key",
                                        keyPrefix + "-confirm"
                                )
                                .header(
                                        HttpHeaders.IF_MATCH,
                                        "\"" + revision + "\""
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content("""
                                        {
                                          "proposalId": "terminal-proposal",
                                          "reviews": [],
                                          "beforeBeliefStrength": 80,
                                          "afterBeliefStrength": 40,
                                          "finalEmotionIntensity": 4,
                                          "helpfulnessScore": 3
                                        }
                                        """)
                )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    private ReflectionSessions createReadySession(
            Users owner,
            String rawText
    ) {
        ReflectionSessions session =
                ReflectionSessions.create(
                        owner,
                        createCompleteRecord(
                                owner,
                                rawText
                        )
                );

        session.appendInsightMessage(
                Map.of(
                        "messageNumber", 1,
                        "role", "ASSISTANT",
                        "content",
                        "이 생각을 뒷받침하는 사실은 무엇인가요?",
                        "createdAt",
                        Instant.now().toString()
                )
        );

        applyRevision(session, 1L);

        return reflectionSessionsRepository.saveAndFlush(
                session
        );
    }

    private ReflectionSessions createCompletedSession(
            Users owner,
            String rawText
    ) {
        ReflectionSessions session =
                createReadySession(owner, rawText);

        session.confirm(
                new ReflectionSessionConfirmRequestDto(
                        "처음 생각을 지지하는 근거",
                        "처음 생각과 다른 근거",
                        "다르게 바라본 생각",
                        (short) 80,
                        (short) 40,
                        (short) 4,
                        (short) 3,
                        List.of(),
                        List.of()
                )
        );

        return reflectionSessionsRepository.saveAndFlush(
                session
        );
    }

    private ReflectionSessions createSafetyStoppedSession(
            Users owner,
            String rawText
    ) {
        ReflectionSessions session =
                createReadySession(owner, rawText);

        session.stopForSafety();

        return reflectionSessionsRepository.saveAndFlush(
                session
        );
    }

    private EmotionRecords createCompleteRecord(
            Users owner,
            String rawText
    ) {
        EmotionRecords record =
                EmotionRecords.createQuick(
                        owner,
                        rawText,
                        InputType.TEXT,
                        Instant.now()
                );

        record.confirm(
                new EmotionRecordsConfirmRequestDto(
                        rawText + " 상황",
                        rawText + " 생각",
                        "ANXIETY",
                        (short) 7,
                        List.of(),
                        "WORK",
                        "COLLEAGUE",
                        Map.of()
                )
        );

        return emotionRecordsRepository.saveAndFlush(record);
    }

    private void applyRevision(
            ReflectionSessions session,
            long revision
    ) {
        LinkedHashMap<String, Object> state =
                new LinkedHashMap<>(
                        session.insight()
                );

        state.put("revision", revision);
        state.put("phase", "DIALOGUE");
        state.put(
                "resultFormatVersion",
                "cbt-insight-1"
        );

        session.replaceInsight(state);
    }

    private Map<String, Object> validTurnResponse(
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
        response.put("outcome", "QUESTION");
        response.put(
                "assistantMessage",
                Map.of(
                        "messageNumber",
                        assistantMessageNumber,
                        "role",
                        "ASSISTANT",
                        "content",
                        "취소 뒤에는 저장되면 안 되는 응답"
                )
        );
        response.put(
                "currentProposal",
                Map.of()
        );

        return response;
    }

    private void cleanDatabase() {
        aiJobsRepository.deleteAll();
        reflectionSessionsRepository.deleteAll();
        emotionRecordsRepository.deleteAll();
        consentEventsRepository.deleteAll();
        usersRepository.deleteAll();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}