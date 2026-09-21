// CBT 신규 시작·기존 세션 복원·순차 및 동시 중복 생성을 실제 DB로 검증

package com.my.mindot_back.records.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.my.mindot_back.ai.repository.AiJobsRepository;
import com.my.mindot_back.common.jwt.JwtTokenProvider;
import com.my.mindot_back.records.client.InsightAiClient;
import com.my.mindot_back.records.dto.EmotionRecordsConfirmRequestDto;
import com.my.mindot_back.records.entity.EmotionRecords;
import com.my.mindot_back.records.entity.InputType;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ReflectionOpenFlowTest
        extends PostgresContainerTestBase {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper =
            new ObjectMapper();

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
    private InsightAiClient insightAiClient;

    private Users user;
    private EmotionRecords record;
    private String accessToken;

    @BeforeEach
    void setUp() {
        cleanDatabase();

        user = usersRepository.saveAndFlush(
                Users.create(
                        "reflection-open@example.com",
                        "unused-password-hash",
                        "CBT 시작 사용자"
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

        accessToken =
                jwtTokenProvider.createAccessToken(user.getId());
    }

    @AfterEach
    void cleanUp() {
        cleanDatabase();
    }

    @Test
    void newSessionStartsOnceAndCanBeRestoredWithoutHistoryChange()
            throws Exception {
        when(
                insightAiClient.call(
                        eq("/internal/ai/reflections/start"),
                        anyMap()
                )
        ).thenAnswer(invocation -> {
            Map<String, Object> request =
                    invocation.getArgument(1);

            // 기존 세션 복원 요청은 새 메시지를 생성하지 않고 결과도 사용하지 않음
            if ("RESTORE".equals(request.get("mode"))) {
                return Map.of("restored", true);
            }

            return validQuestionResponse(request);
        });;

        MvcResult firstResult = mockMvc.perform(
                        post("/api/reflections/open")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                                .header(
                                        "Idempotency-Key",
                                        "open-first-key"
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content("""
                                        {
                                          "emotionRecordId": %d
                                        }
                                        """.formatted(record.getId()))
                )
                .andExpect(status().isOk())
                .andExpect(
                        header().string(
                                HttpHeaders.ETAG,
                                "\"1\""
                        )
                )
                .andExpect(
                        jsonPath("$.status")
                                .value("OPEN")
                )
                .andExpect(
                        jsonPath("$.revision")
                                .value(1)
                )
                .andExpect(
                        jsonPath("$.messages.length()")
                                .value(1)
                )
                .andExpect(
                        jsonPath("$.messages[0].role")
                                .value("ASSISTANT")
                )
                .andExpect(
                        jsonPath("$.messages[0].content")
                                .value(
                                        "그 생각이 들었을 때 어떤 일이 있었나요?"
                                )
                )
                .andReturn();

        long sessionId =
                responseJson(firstResult)
                        .get("sessionId")
                        .asLong();

        mockMvc.perform(
                        post("/api/reflections/open")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                                .header(
                                        "Idempotency-Key",
                                        "open-same-record-key"
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content("""
                                        {
                                          "emotionRecordId": %d
                                        }
                                        """.formatted(record.getId()))
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.sessionId")
                                .value(sessionId)
                )
                .andExpect(
                        jsonPath("$.revision")
                                .value(1)
                )
                .andExpect(
                        jsonPath("$.messages.length()")
                                .value(1)
                );

        mockMvc.perform(
                        post("/api/reflections/open")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                                .header(
                                        "Idempotency-Key",
                                        "open-restore-key"
                                )
                                .header(
                                        HttpHeaders.IF_MATCH,
                                        "\"1\""
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content("""
                                        {
                                          "sessionId": %d
                                        }
                                        """.formatted(sessionId))
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.sessionId")
                                .value(sessionId)
                )
                .andExpect(
                        jsonPath("$.revision")
                                .value(1)
                )
                .andExpect(
                        jsonPath("$.messages.length()")
                                .value(1)
                );

        assertThat(reflectionSessionsRepository.count())
                .isEqualTo(1);

        var savedSession =
                reflectionSessionsRepository
                        .findById(sessionId)
                        .orElseThrow();

        assertThat(savedSession.getQuestionAnswers())
                .hasSize(1);

        verify(
                insightAiClient,
                times(3)
        ).call(
                eq("/internal/ai/reflections/start"),
                anyMap()
        );
    }

    @Test
    void invalidOpenBodiesAreRejected()
            throws Exception {
        mockMvc.perform(
                        post("/api/reflections/open")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                                .header(
                                        "Idempotency-Key",
                                        "open-empty-body"
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content("{}")
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        mockMvc.perform(
                        post("/api/reflections/open")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                                .header(
                                        "Idempotency-Key",
                                        "open-two-targets"
                                )
                                .header(
                                        HttpHeaders.IF_MATCH,
                                        "\"0\""
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content("""
                                        {
                                          "emotionRecordId": %d,
                                          "sessionId": 999999
                                        }
                                        """.formatted(record.getId()))
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        mockMvc.perform(
                        post("/api/reflections/open")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                                .header(
                                        "Idempotency-Key",
                                        "open-missing-session"
                                )
                                .header(
                                        HttpHeaders.IF_MATCH,
                                        "\"0\""
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content("""
                                        {
                                          "sessionId": 999999
                                        }
                                        """)
                )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        assertThat(reflectionSessionsRepository.count())
                .isZero();
    }

    @Test
    void concurrentOpenRequestsCreateOnlyOneSessionAndOneGeneration()
            throws Exception {
        CountDownLatch generationStarted =
                new CountDownLatch(1);
        CountDownLatch releaseGeneration =
                new CountDownLatch(1);

        when(
                insightAiClient.call(
                        eq("/internal/ai/reflections/start"),
                        anyMap()
                )
        ).thenAnswer(invocation -> {
            generationStarted.countDown();

            if (!releaseGeneration.await(
                    10,
                    TimeUnit.SECONDS
            )) {
                throw new IllegalStateException(
                        "CBT 응답 대기 시간이 초과됐습니다"
                );
            }

            return validQuestionResponse(
                    invocation.getArgument(1)
            );
        });

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        try {
            Future<MvcResult> firstFuture =
                    executor.submit(
                            () -> mockMvc.perform(
                                            post(
                                                    "/api/reflections/open"
                                            )
                                                    .header(
                                                            HttpHeaders.AUTHORIZATION,
                                                            bearer(accessToken)
                                                    )
                                                    .header(
                                                            "Idempotency-Key",
                                                            "concurrent-open-1"
                                                    )
                                                    .contentType(
                                                            MediaType.APPLICATION_JSON
                                                    )
                                                    .content("""
                                                            {
                                                              "emotionRecordId": %d
                                                            }
                                                            """.formatted(
                                                            record.getId()
                                                    ))
                                    )
                                    .andReturn()
                    );

            assertThat(
                    generationStarted.await(
                            10,
                            TimeUnit.SECONDS
                    )
            ).isTrue();

            Future<MvcResult> secondFuture =
                    executor.submit(
                            () -> mockMvc.perform(
                                            post(
                                                    "/api/reflections/open"
                                            )
                                                    .header(
                                                            HttpHeaders.AUTHORIZATION,
                                                            bearer(accessToken)
                                                    )
                                                    .header(
                                                            "Idempotency-Key",
                                                            "concurrent-open-2"
                                                    )
                                                    .contentType(
                                                            MediaType.APPLICATION_JSON
                                                    )
                                                    .content("""
                                                            {
                                                              "emotionRecordId": %d
                                                            }
                                                            """.formatted(
                                                            record.getId()
                                                    ))
                                    )
                                    .andReturn()
                    );

            MvcResult secondResult =
                    secondFuture.get(
                            10,
                            TimeUnit.SECONDS
                    );

            assertThat(
                    secondResult.getResponse().getStatus()
            ).isEqualTo(202);

            releaseGeneration.countDown();

            MvcResult firstResult =
                    firstFuture.get(
                            10,
                            TimeUnit.SECONDS
                    );

            assertThat(
                    firstResult.getResponse().getStatus()
            ).isEqualTo(200);

            long firstSessionId =
                    responseJson(firstResult)
                            .get("sessionId")
                            .asLong();

            long secondSessionId =
                    responseJson(secondResult)
                            .get("sessionId")
                            .asLong();

            assertThat(secondSessionId)
                    .isEqualTo(firstSessionId);
        } finally {
            releaseGeneration.countDown();
            executor.shutdownNow();
        }

        assertThat(reflectionSessionsRepository.count())
                .isEqualTo(1);

        verify(
                insightAiClient,
                times(1)
        ).call(
                eq("/internal/ai/reflections/start"),
                anyMap()
        );
    }

    private Map<String, Object> validQuestionResponse(
            Map<String, Object> request
    ) {
        @SuppressWarnings("unchecked")
        Map<String, Object> pendingJob =
                (Map<String, Object>) request.get(
                        "pendingJob"
                );

        long inputRevision =
                ((Number) pendingJob.get(
                        "inputRevision"
                )).longValue();

        LinkedHashMap<String, Object> response =
                new LinkedHashMap<>();

        response.put(
                "sessionId",
                request.get("sessionId")
        );
        response.put(
                "requestId",
                pendingJob.get("requestId")
        );
        response.put(
                "attemptNo",
                pendingJob.get("attemptNo")
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
                        "messageNumber", 1,
                        "role", "ASSISTANT",
                        "content",
                        "그 생각이 들었을 때 어떤 일이 있었나요?"
                )
        );
        response.put(
                "currentProposal",
                Map.of()
        );

        return response;
    }

    private EmotionRecords createCompleteRecord(
            Users owner
    ) {
        EmotionRecords emotionRecord =
                EmotionRecords.createQuick(
                        owner,
                        "회의에서 실수한 것 같아 불안했다",
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

        return emotionRecordsRepository.saveAndFlush(
                emotionRecord
        );
    }

    private JsonNode responseJson(
            MvcResult result
    ) throws Exception {
        return objectMapper.readTree(
                result.getResponse()
                        .getContentAsString()
        );
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