// 진행 중 CBT 목록과 상세 상태·소유권·ETag 응답을 실제 DB로 검증

package com.my.mindot_back.records.controller;

import com.my.mindot_back.ai.entity.AiJobEntityType;
import com.my.mindot_back.ai.entity.AiJobOperation;
import com.my.mindot_back.ai.entity.AiJobs;
import com.my.mindot_back.ai.repository.AiJobsRepository;
import com.my.mindot_back.common.jwt.JwtTokenProvider;
import com.my.mindot_back.records.dto.EmotionRecordsConfirmRequestDto;
import com.my.mindot_back.records.entity.EmotionRecords;
import com.my.mindot_back.records.entity.InputType;
import com.my.mindot_back.records.entity.ReflectionSessions;
import com.my.mindot_back.records.repository.EmotionRecordsRepository;
import com.my.mindot_back.records.repository.ReflectionSessionsRepository;
import com.my.mindot_back.support.PostgresContainerTestBase;
import com.my.mindot_back.users.entity.Users;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ReflectionQueryApiTest
        extends PostgresContainerTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UsersRepository usersRepository;

    @Autowired
    private EmotionRecordsRepository emotionRecordsRepository;

    @Autowired
    private ReflectionSessionsRepository reflectionSessionsRepository;

    @Autowired
    private AiJobsRepository aiJobsRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private Users user;
    private Users otherUser;
    private String accessToken;

    private ReflectionSessions olderOpenSession;
    private ReflectionSessions newerProcessingSession;
    private ReflectionSessions cancelledSession;
    private ReflectionSessions otherUsersSession;

    @BeforeEach
    void setUp() {
        user = usersRepository.saveAndFlush(
                Users.create(
                        "reflection-query@example.com",
                        "unused-password-hash",
                        "CBT 조회 사용자"
                )
        );

        otherUser = usersRepository.saveAndFlush(
                Users.create(
                        "other-reflection-query@example.com",
                        "unused-password-hash",
                        "다른 CBT 사용자"
                )
        );

        olderOpenSession = createOpenSession(
                user,
                "먼저 만든 진행 중 기록",
                2L
        );

        newerProcessingSession = createOpenSession(
                user,
                "나중에 만든 처리 중 기록",
                3L
        );

        attachProcessingJob(
                newerProcessingSession,
                3L
        );

        cancelledSession = createOpenSession(
                user,
                "중단된 기록",
                1L
        );
        cancelledSession.cancel();
        reflectionSessionsRepository.saveAndFlush(
                cancelledSession
        );

        otherUsersSession = createOpenSession(
                otherUser,
                "다른 사용자의 진행 중 기록",
                4L
        );

        accessToken =
                jwtTokenProvider.createAccessToken(user.getId());
    }

    @Test
    void openListReturnsOnlyOwnersOpenSessionsInNewestOrder()
            throws Exception {
        mockMvc.perform(
                        get("/api/reflections/open")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.length()")
                                .value(2)
                )
                .andExpect(
                        jsonPath("$[0].sessionId")
                                .value(
                                        newerProcessingSession.getId()
                                )
                )
                .andExpect(
                        jsonPath("$[0].emotionRecordId")
                                .value(
                                        newerProcessingSession
                                                .getEmotionRecord()
                                                .getId()
                                )
                )
                .andExpect(
                        jsonPath("$[0].rawText")
                                .value(
                                        "나중에 만든 처리 중 기록"
                                )
                )
                .andExpect(
                        jsonPath("$[0].revision")
                                .value(3)
                )
                .andExpect(
                        jsonPath("$[1].sessionId")
                                .value(
                                        olderOpenSession.getId()
                                )
                )
                .andExpect(
                        jsonPath("$[1].rawText")
                                .value(
                                        "먼저 만든 진행 중 기록"
                                )
                )
                .andExpect(
                        jsonPath("$[1].revision")
                                .value(2)
                );
    }

    @Test
    void pagedOpenListReturnsThreeAtATimeWithOwnerOnlyAndNewestFirst()
            throws Exception {
        ReflectionSessions third = createOpenSession(user, "세 번째 진행 중 기록", 5L);
        ReflectionSessions fourth = createOpenSession(user, "네 번째 진행 중 기록", 6L);
        ReflectionSessions fifth = createOpenSession(user, "다섯 번째 진행 중 기록", 7L);
        ReflectionSessions sixth = createOpenSession(user, "여섯 번째 진행 중 기록", 8L);

        mockMvc.perform(get("/api/reflections/open/paged")
                        .param("page", "0")
                        .param("size", "3")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.totalElements").value(6))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content[0].sessionId").value(sixth.getId()))
                .andExpect(jsonPath("$.content[1].sessionId").value(fifth.getId()))
                .andExpect(jsonPath("$.content[2].sessionId").value(fourth.getId()));

        mockMvc.perform(get("/api/reflections/open/paged")
                        .param("page", "1")
                        .param("size", "3")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.totalElements").value(6))
                .andExpect(jsonPath("$.content[0].sessionId").value(third.getId()))
                .andExpect(jsonPath("$.content[1].sessionId").value(newerProcessingSession.getId()))
                .andExpect(jsonPath("$.content[2].sessionId").value(olderOpenSession.getId()));
    }
    @Test
    void completedListReturnsOnlyOwnersConfirmedCompletedSessions()
            throws Exception {
        createCompletedSession(
                user,
                "완료한 내 성찰 기록",
                "다른 관점으로 다시 확인해 볼 수 있다"
        );
        createCompletedSession(
                user,
                "두 번째 완료 성찰",
                "두 번째 대안적 생각"
        );
        createCompletedSession(
                user,
                "세 번째 완료 성찰",
                "세 번째 대안적 생각"
        );
        createCompletedSession(
                otherUser,
                "다른 사용자의 완료 성찰",
                "다른 사용자의 결과"
        );

        mockMvc.perform(
                        get("/api/reflections/completed")
                                .param("page", "1")
                                .param("size", "2")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(
                        jsonPath("$.content[0].sessionId")
                                .isNumber()
                )
                .andExpect(
                        jsonPath("$.content[0].emotionRecordId")
                                .isNumber()
                )
                .andExpect(
                        jsonPath("$.content[0].rawText")
                                .isNotEmpty()
                )
                .andExpect(jsonPath("$.content[0].alternativeThoughtText").isNotEmpty())
                .andExpect(jsonPath("$.content[0].completedAt").exists());
    }

    @Test
    void normalDetailReturnsOkAndProcessingDetailReturnsAccepted()
            throws Exception {
        mockMvc.perform(
                        get(
                                "/api/reflections/{sessionId}",
                                olderOpenSession.getId()
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
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
                        jsonPath("$.sessionId")
                                .value(
                                        olderOpenSession.getId()
                                )
                )
                .andExpect(
                        jsonPath("$.revision")
                                .value(2)
                )
                .andExpect(
                        jsonPath("$.status")
                                .value("OPEN")
                )
                .andExpect(
                        jsonPath("$.job")
                                .doesNotExist()
                );

        mockMvc.perform(
                        get(
                                "/api/reflections/{sessionId}",
                                newerProcessingSession.getId()
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isAccepted())
                .andExpect(
                        header().string(
                                HttpHeaders.ETAG,
                                "\"3\""
                        )
                )
                .andExpect(
                        jsonPath("$.sessionId")
                                .value(
                                        newerProcessingSession.getId()
                                )
                )
                .andExpect(
                        jsonPath("$.revision")
                                .value(3)
                )
                .andExpect(
                        jsonPath("$.job.status")
                                .value("PROCESSING")
                )
                .andExpect(
                        jsonPath("$.job.retryable")
                                .value(false)
                );
    }

    @Test
    void otherUsersSessionAndMissingSessionReturnNotFound()
            throws Exception {
        mockMvc.perform(
                        get(
                                "/api/reflections/{sessionId}",
                                otherUsersSession.getId()
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        mockMvc.perform(
                        get(
                                "/api/reflections/{sessionId}",
                                999999L
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    private ReflectionSessions createOpenSession(
            Users owner,
            String rawText,
            long revision
    ) {
        EmotionRecords emotionRecord =
                EmotionRecords.createQuick(
                        owner,
                        rawText,
                        InputType.TEXT,
                        Instant.now()
                );

        emotionRecord.confirm(
                new EmotionRecordsConfirmRequestDto(
                        rawText + " 상황",
                        rawText + " 생각",
                        "ANXIETY",
                        (short) 6,
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

        ReflectionSessions session =
                ReflectionSessions.create(
                        owner,
                        emotionRecord
                );

        LinkedHashMap<String, Object> state =
                new LinkedHashMap<>();
        state.put("revision", revision);
        state.put(
                "resultFormatVersion",
                "cbt-insight-1"
        );

        session.replaceInsight(state);

        return reflectionSessionsRepository.saveAndFlush(
                session
        );
    }

    private ReflectionSessions createCompletedSession(
            Users owner,
            String rawText,
            String alternativeThoughtText
    ) {
        ReflectionSessions session = createOpenSession(owner, rawText, 1L);
        session.confirmInsight(
                Map.of(
                        "evidenceForText", "처음 생각을 뒷받침하는 근거",
                        "evidenceAgainstText", "다른 가능성을 보여 주는 근거",
                        "afterText", alternativeThoughtText
                ),
                (short) 80,
                (short) 40,
                (short) 4,
                (short) 5
        );
        return reflectionSessionsRepository.saveAndFlush(session);
    }

    private void attachProcessingJob(
            ReflectionSessions session,
            long revision
    ) {
        AiJobs job = AiJobs.create(
                session.getUser(),
                AiJobEntityType.REFLECTION,
                session.getId(),
                AiJobOperation.CBT_COMMAND,
                "processing-query-job"
        );

        job.prepareInsight(
                Map.of(
                        "command", "TURN",
                        "input", Map.of(
                                "requestId",
                                "processing-request",
                                "inputRevision",
                                revision
                        )
                ),
                (short) 1,
                Instant.now().plusSeconds(210)
        );
        job.startProcessing();

        job = aiJobsRepository.saveAndFlush(job);

        LinkedHashMap<String, Object> state =
                new LinkedHashMap<>(
                        session.insight()
                );
        state.put("revision", revision);
        state.put("lastJobId", job.getId());
        state.put(
                "resultFormatVersion",
                "cbt-insight-1"
        );

        session.replaceInsight(state);

        reflectionSessionsRepository.saveAndFlush(session);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
