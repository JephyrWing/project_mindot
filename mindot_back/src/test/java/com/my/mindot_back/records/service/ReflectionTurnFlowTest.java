// CBT 답변의 멱등성·revision 충돌·동시 요청과 메시지 보존을 실제 DB로 검증

package com.my.mindot_back.records.service;

import com.my.mindot_back.ai.repository.AiJobsRepository;
import com.my.mindot_back.records.client.InsightAiClient;
import com.my.mindot_back.records.dto.EmotionRecordsConfirmRequestDto;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
class ReflectionTurnFlowTest
        extends PostgresContainerTestBase {

    @Autowired
    private InsightService insightService;

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
                        "reflection-turn@example.com",
                        "unused-password-hash",
                        "CBT 답변 사용자"
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
    void answerIsAppliedOnceAndDuplicateReturnsSavedResult() {
        when(
                insightAiClient.call(
                        eq("/internal/ai/reflections/turn"),
                        anyMap()
                )
        ).thenAnswer(invocation ->
                validTurnResponse(
                        invocation.getArgument(1)
                )
        );

        Turn answer =
                new Turn("발표 전에 충분히 준비하지 못했습니다");

        SessionView first = insightService.turn(
                user.getId(),
                session.getId(),
                "turn-key-1",
                1L,
                answer
        );

        assertThat(first.revision())
                .isEqualTo(3L);
        assertThat(first.messages())
                .hasSize(3);
        assertThat(first.messages().get(1).get("role"))
                .isEqualTo("USER");
        assertThat(first.messages().get(1).get("content"))
                .isEqualTo(answer.answer());
        assertThat(first.messages().get(2).get("role"))
                .isEqualTo("ASSISTANT");

        SessionView duplicate = insightService.turn(
                user.getId(),
                session.getId(),
                "turn-key-1",
                1L,
                answer
        );

        assertThat(duplicate.revision())
                .isEqualTo(first.revision());
        assertThat(duplicate.messages())
                .isEqualTo(first.messages());

        assertConflict(
                () -> insightService.turn(
                        user.getId(),
                        session.getId(),
                        "turn-key-1",
                        1L,
                        new Turn("같은 키에 다른 답변")
                )
        );

        assertConflict(
                () -> insightService.turn(
                        user.getId(),
                        session.getId(),
                        "turn-stale-key",
                        1L,
                        new Turn("오래된 화면의 답변")
                )
        );

        ReflectionSessions saved =
                reflectionSessionsRepository
                        .findById(session.getId())
                        .orElseThrow();

        assertThat(saved.getQuestionAnswers())
                .hasSize(3);
        assertThat(saved.getQuestionAnswers())
                .extracting(
                        message -> message.get("role")
                )
                .containsExactly(
                        "ASSISTANT",
                        "USER",
                        "ASSISTANT"
                );

        verify(
                insightAiClient,
                times(1)
        ).call(
                eq("/internal/ai/reflections/turn"),
                anyMap()
        );
    }

    @Test
    void concurrentAnswersAcceptOneAndRejectStaleRequest()
            throws Exception {
        CountDownLatch generationStarted =
                new CountDownLatch(1);
        CountDownLatch releaseGeneration =
                new CountDownLatch(1);

        when(
                insightAiClient.call(
                        eq("/internal/ai/reflections/turn"),
                        anyMap()
                )
        ).thenAnswer(invocation -> {
            generationStarted.countDown();

            if (!releaseGeneration.await(
                    10,
                    TimeUnit.SECONDS
            )) {
                throw new IllegalStateException(
                        "CBT 답변 대기 시간이 초과됐습니다"
                );
            }

            return validTurnResponse(
                    invocation.getArgument(1)
            );
        });

        ExecutorService executor =
                Executors.newSingleThreadExecutor();

        try {
            Future<SessionView> firstFuture =
                    executor.submit(
                            () -> insightService.turn(
                                    user.getId(),
                                    session.getId(),
                                    "concurrent-turn-1",
                                    1L,
                                    new Turn("첫 번째 동시 답변")
                            )
                    );

            assertThat(
                    generationStarted.await(
                            10,
                            TimeUnit.SECONDS
                    )
            ).isTrue();

            assertConflict(
                    () -> insightService.turn(
                            user.getId(),
                            session.getId(),
                            "concurrent-turn-2",
                            1L,
                            new Turn("두 번째 동시 답변")
                    )
            );

            releaseGeneration.countDown();

            SessionView completed =
                    firstFuture.get(
                            10,
                            TimeUnit.SECONDS
                    );

            assertThat(completed.revision())
                    .isEqualTo(3L);
        } finally {
            releaseGeneration.countDown();
            executor.shutdownNow();
        }

        ReflectionSessions saved =
                reflectionSessionsRepository
                        .findById(session.getId())
                        .orElseThrow();

        assertThat(saved.getQuestionAnswers())
                .hasSize(3);

        assertThat(saved.getQuestionAnswers())
                .extracting(
                        message -> message.get("content")
                )
                .containsExactly(
                        "발표를 망쳤다는 생각을 뒷받침하는 사실은 무엇인가요?",
                        "첫 번째 동시 답변",
                        "그 사실을 다르게 해석할 가능성도 있을까요?"
                );

        verify(
                insightAiClient,
                times(1)
        ).call(
                eq("/internal/ai/reflections/turn"),
                anyMap()
        );
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
                        "발표를 망쳤다는 생각을 뒷받침하는 사실은 무엇인가요?",
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
                        "그 사실을 다르게 해석할 가능성도 있을까요?"
                )
        );
        response.put(
                "currentProposal",
                Map.of()
        );

        return response;
    }

    private void assertConflict(
            org.assertj.core.api.ThrowableAssert
                    .ThrowingCallable action
    ) {
        assertThatThrownBy(action)
                .isInstanceOf(
                        ResponseStatusException.class
                )
                .satisfies(error -> assertThat(
                        ((ResponseStatusException) error)
                                .getStatusCode()
                ).isEqualTo(HttpStatus.CONFLICT));
    }

    private void cleanDatabase() {
        aiJobsRepository.deleteAll();
        reflectionSessionsRepository.deleteAll();
        emotionRecordsRepository.deleteAll();
        consentEventsRepository.deleteAll();
        usersRepository.deleteAll();
    }
}