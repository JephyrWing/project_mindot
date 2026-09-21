// CBT AI 실패 후 답변 보존·재시도·재동기화·늦은 응답 무시를 검증

package com.my.mindot_back.records.service;

import com.my.mindot_back.ai.repository.AiJobsRepository;
import com.my.mindot_back.records.client.InsightAiClient;
import com.my.mindot_back.records.dto.EmotionRecordsConfirmRequestDto;
import com.my.mindot_back.records.dto.InsightDtos.Prepared;
import com.my.mindot_back.records.dto.InsightDtos.SessionView;
import com.my.mindot_back.records.dto.InsightDtos.Turn;
import com.my.mindot_back.records.entity.EmotionRecords;
import com.my.mindot_back.records.entity.InputType;
import com.my.mindot_back.records.entity.ReflectionSessions;
import com.my.mindot_back.records.repository.EmotionRecordsRepository;
import com.my.mindot_back.records.repository.ReflectionSessionsRepository;
import com.my.mindot_back.support.PostgresContainerTestBase;
import com.my.mindot_back.users.entity.ConsentEvents;
import com.my.mindot_back.users.entity.ConsentType;
import com.my.mindot_back.users.entity.Users;
import com.my.mindot_back.users.repository.ConsentEventsRepository;
import com.my.mindot_back.users.repository.UsersRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
class ReflectionRetryFlowTest
        extends PostgresContainerTestBase {

    @Autowired
    private InsightService insightService;

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

    @MockitoBean
    private InsightAiClient insightAiClient;

    private Users user;
    private ReflectionSessions session;

    @BeforeEach
    void setUp() {
        cleanDatabase();

        user = usersRepository.saveAndFlush(
                Users.create(
                        "reflection-retry@example.com",
                        "unused-password-hash",
                        "CBT 재시도 사용자"
                )
        );

        consentEventsRepository.saveAndFlush(
                ConsentEvents.grant(
                        user,
                        ConsentType.AI_ANALYSIS,
                        "ai-analysis-v1"
                )
        );

        session = createReadySession(user);
    }

    @AfterEach
    void cleanUp() {
        cleanDatabase();
    }

    @Test
    void failedGenerationPreservesUserAnswerAndRetryAddsOnlyAssistant()
            throws Exception {
        when(
                insightAiClient.call(
                        eq("/internal/ai/reflections/turn"),
                        anyMap()
                )
        )
                .thenThrow(
                        new InsightAiClient.Failure(
                                "GENERATION_FAILED"
                        )
                )
                .thenAnswer(invocation ->
                        validTurnResponse(
                                invocation.getArgument(1)
                        )
                );

        Turn answer =
                new Turn("발표 준비가 부족했던 것은 사실입니다");

        SessionView failed = insightService.turn(
                user.getId(),
                session.getId(),
                "failed-turn-key",
                1L,
                answer
        );

        assertThat(failed.revision())
                .isEqualTo(2L);
        assertThat(failed.messages())
                .hasSize(2);
        assertThat(failed.messages().get(1).get("role"))
                .isEqualTo("USER");
        assertThat(failed.messages().get(1).get("content"))
                .isEqualTo(answer.answer());
        assertThat(failed.job().get("status"))
                .isEqualTo("FAILED");
        assertThat(failed.job().get("retryable"))
                .isEqualTo(true);
        assertThat(failed.job().get("errorCode"))
                .isEqualTo("GENERATION_FAILED");

        SessionView retried = insightService.retry(
                user.getId(),
                session.getId(),
                "retry-turn-key",
                2L
        );

        assertThat(retried.revision())
                .isEqualTo(3L);
        assertThat(retried.messages())
                .hasSize(3);

        assertThat(retried.messages())
                .extracting(message -> message.get("role"))
                .containsExactly(
                        "ASSISTANT",
                        "USER",
                        "ASSISTANT"
                );

        assertThat(retried.messages())
                .extracting(message -> message.get("content"))
                .containsExactly(
                        "발표를 망쳤다는 생각의 근거는 무엇인가요?",
                        answer.answer(),
                        "그 사실만으로 발표 전체를 망쳤다고 볼 수 있을까요?"
                );

        assertThat(retried.job().get("status"))
                .isEqualTo("COMPLETED");

        verify(
                insightAiClient,
                times(2)
        ).call(
                eq("/internal/ai/reflections/turn"),
                anyMap()
        );
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                    "GENERATION_FAILED",
                    "TRANSPORT_FAILED"
            }
    )
    void normalizedAiFailuresPreserveSavedUserAnswer(
            String failureCode
    ) {
        when(
                insightAiClient.call(
                        eq("/internal/ai/reflections/turn"),
                        anyMap()
                )
        ).thenThrow(
                new InsightAiClient.Failure(failureCode)
        );

        SessionView failed = insightService.turn(
                user.getId(),
                session.getId(),
                "normalized-failure-" + failureCode,
                1L,
                new Turn("실패해도 보존할 사용자 답변")
        );

        assertThat(failed.revision())
                .isEqualTo(2L);
        assertThat(failed.messages())
                .hasSize(2);
        assertThat(failed.messages().get(1).get("content"))
                .isEqualTo(
                        "실패해도 보존할 사용자 답변"
                );
        assertThat(failed.job().get("status"))
                .isEqualTo("FAILED");
        assertThat(failed.job().get("errorCode"))
                .isEqualTo(failureCode);
    }

    @Test
    void invalidAiResultMarksJobFailedWithoutLosingUserAnswer() {
        when(
                insightAiClient.call(
                        eq("/internal/ai/reflections/turn"),
                        anyMap()
                )
        ).thenReturn(
                Map.of(
                        "outcome",
                        "INVALID_OUTCOME"
                )
        );

        SessionView failed = insightService.turn(
                user.getId(),
                session.getId(),
                "invalid-result-key",
                1L,
                new Turn("잘못된 AI 응답 전의 사용자 답변")
        );

        assertThat(failed.revision())
                .isEqualTo(2L);
        assertThat(failed.messages())
                .hasSize(2);
        assertThat(failed.messages().get(1).get("role"))
                .isEqualTo("USER");
        assertThat(failed.messages().get(1).get("content"))
                .isEqualTo(
                        "잘못된 AI 응답 전의 사용자 답변"
                );
        assertThat(failed.job().get("status"))
                .isEqualTo("FAILED");
        assertThat(failed.job().get("errorCode"))
                .isEqualTo("RESULT_COMMIT_FAILED");
    }

    @Test
    void resyncRestoresSessionThenReplaysSameSavedTurn() {
        when(
                insightAiClient.call(
                        eq("/internal/ai/reflections/turn"),
                        anyMap()
                )
        )
                .thenThrow(
                        new InsightAiClient.Failure(
                                "RESYNC_REQUIRED"
                        )
                )
                .thenAnswer(invocation ->
                        validTurnResponse(
                                invocation.getArgument(1)
                        )
                );

        when(
                insightAiClient.call(
                        eq("/internal/ai/reflections/start"),
                        anyMap()
                )
        ).thenReturn(
                Map.of("restored", true)
        );

        SessionView result = insightService.turn(
                user.getId(),
                session.getId(),
                "resync-turn-key",
                1L,
                new Turn("재동기화 후에도 유지할 답변")
        );

        assertThat(result.revision())
                .isEqualTo(3L);
        assertThat(result.messages())
                .hasSize(3);
        assertThat(result.messages().get(1).get("content"))
                .isEqualTo(
                        "재동기화 후에도 유지할 답변"
                );

        InOrder order = inOrder(insightAiClient);

        order.verify(insightAiClient)
                .call(
                        eq("/internal/ai/reflections/turn"),
                        anyMap()
                );

        order.verify(insightAiClient)
                .call(
                        eq("/internal/ai/reflections/start"),
                        anyMap()
                );

        order.verify(insightAiClient)
                .call(
                        eq("/internal/ai/reflections/turn"),
                        anyMap()
                );
    }

    @Test
    void lateResultFromFailedAttemptDoesNotOverwriteRetryResult() {
        Prepared failedAttempt =
                insightTransactions.turn(
                        user.getId(),
                        session.getId(),
                        "late-first-key",
                        1L,
                        new Turn("한 번만 저장할 사용자 답변")
                );

        insightTransactions.fail(
                user.getId(),
                failedAttempt,
                "GENERATION_FAILED"
        );

        Prepared retryAttempt =
                insightTransactions.retry(
                        user.getId(),
                        session.getId(),
                        "late-retry-key",
                        2L
                );

        SessionView completed =
                insightTransactions.complete(
                        user.getId(),
                        retryAttempt,
                        validTurnResponse(
                                retryAttempt.request()
                        )
                );

        assertThat(completed.revision())
                .isEqualTo(3L);
        assertThat(completed.messages())
                .hasSize(3);

        SessionView afterLateResult =
                insightTransactions.complete(
                        user.getId(),
                        failedAttempt,
                        validTurnResponse(
                                failedAttempt.request()
                        )
                );

        assertThat(afterLateResult.revision())
                .isEqualTo(3L);
        assertThat(afterLateResult.messages())
                .hasSize(3);
        assertThat(afterLateResult.messages())
                .isEqualTo(completed.messages());

        ReflectionSessions saved =
                reflectionSessionsRepository
                        .findById(session.getId())
                        .orElseThrow();

        assertThat(saved.getQuestionAnswers())
                .hasSize(3);
    }

    private ReflectionSessions createReadySession(
            Users owner
    ) {
        EmotionRecords emotionRecord =
                EmotionRecords.createQuick(
                        owner,
                        "회의 발표를 망쳤다고 느꼈다",
                        InputType.TEXT,
                        Instant.parse(
                                "2026-09-21T01:00:00Z"
                        )
                );

        emotionRecord.confirm(
                new EmotionRecordsConfirmRequestDto(
                        "회의에서 발표했다",
                        "나는 발표를 망쳤다",
                        "ANXIETY",
                        (short) 7,
                        List.of(),
                        "WORK",
                        "COLLEAGUE",
                        Map.of()
                )
        );

        emotionRecord =
                emotionRecordsRepository.saveAndFlush(
                        emotionRecord
                );

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
                        "발표를 망쳤다는 생각의 근거는 무엇인가요?",
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

        reflectionSession.replaceInsight(state);

        return reflectionSessionsRepository.saveAndFlush(
                reflectionSession
        );
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
                        "그 사실만으로 발표 전체를 망쳤다고 볼 수 있을까요?"
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
}