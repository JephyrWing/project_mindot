// 감정 기록 삭제 시 연결 데이터 정리와 지연된 AI 결과 차단을 실제 DB로 검증

package com.my.mindot_back.records.service;

import com.my.mindot_back.ai.entity.AiJobEntityType;
import com.my.mindot_back.ai.entity.AiJobOperation;
import com.my.mindot_back.ai.entity.AiJobs;
import com.my.mindot_back.ai.repository.AiJobsRepository;
import com.my.mindot_back.common.jwt.JwtTokenProvider;
import com.my.mindot_back.records.dto.EmotionRecordsConfirmRequestDto;
import com.my.mindot_back.records.dto.EmotionRecordsQuickCreateRequestDto;
import com.my.mindot_back.records.dto.ai.FastApiRecordAnalysisResponseDto;
import com.my.mindot_back.records.entity.EmotionRecords;
import com.my.mindot_back.records.entity.InputType;
import com.my.mindot_back.records.entity.ReflectionSessions;
import com.my.mindot_back.records.repository.EmotionRecordsRepository;
import com.my.mindot_back.records.repository.ReflectionSessionsRepository;
import com.my.mindot_back.reports.entity.Reports;
import com.my.mindot_back.reports.repository.ReportsRepository;
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
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class EmotionRecordDeleteFlowTest
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
    private ReportsRepository reportsRepository;

    @Autowired
    private EmotionRecordAiTransactionService
            emotionRecordAiTransactionService;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private Users user;
    private String accessToken;

    @BeforeEach
    void setUp() {
        cleanDatabase();

        user = usersRepository.saveAndFlush(
                Users.create(
                        "delete-flow@example.com",
                        "unused-password-hash",
                        "삭제 흐름 사용자"
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
    void deletingProcessingRecordRemovesJobReportAndBlocksLateResult()
            throws Exception {
        EmotionRecordsQuickCreateRequestDto request =
                new EmotionRecordsQuickCreateRequestDto(
                        "삭제 후에도 복원되면 안 되는 원문",
                        InputType.TEXT,
                        Instant.parse("2026-09-21T01:00:00Z")
                );

        var context = emotionRecordAiTransactionService
                .createQuickRecordAndStartAiJob(
                        user.getId(),
                        request,
                        "delete-processing-key"
                );

        LocalDate recordDate =
                LocalDate.of(2026, 9, 21);

        reportsRepository.saveAllAndFlush(
                List.of(
                        Reports.createWeekly(
                                user,
                                recordDate,
                                recordDate.plusDays(6),
                                Map.of("recordCount", 1)
                        ),
                        Reports.createMonthly(
                                user,
                                recordDate.withDayOfMonth(1),
                                recordDate.withDayOfMonth(
                                        recordDate.lengthOfMonth()
                                ),
                                Map.of("recordCount", 1)
                        )
                )
        );

        assertThat(emotionRecordsRepository.count())
                .isEqualTo(1);
        assertThat(aiJobsRepository.count())
                .isEqualTo(1);
        assertThat(reportsRepository.count())
                .isEqualTo(2);

        mockMvc.perform(
                        delete(
                                "/api/records/{recordId}",
                                context.emotionRecordId()
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isNoContent());

        assertThat(
                emotionRecordsRepository.existsById(
                        context.emotionRecordId()
                )
        ).isFalse();

        assertThat(
                aiJobsRepository.existsById(
                        context.aiJobId()
                )
        ).isFalse();

        assertThat(reportsRepository.count())
                .isZero();

        mockMvc.perform(
                        get("/api/records")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                                .param("period", "ALL")
                                .param("page", "0")
                                .param("size", "10")
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.totalElements")
                                .value(0)
                );

        mockMvc.perform(
                        get(
                                "/api/records/{recordId}",
                                context.emotionRecordId()
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        assertThatThrownBy(
                () -> emotionRecordAiTransactionService
                        .completeAiAnalysis(
                                context.emotionRecordId(),
                                context.aiJobId(),
                                successfulAnalysis()
                        )
        )
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(
                        ((ResponseStatusException) error)
                                .getStatusCode()
                ).isEqualTo(HttpStatus.NOT_FOUND));

        assertThat(
                emotionRecordsRepository.existsById(
                        context.emotionRecordId()
                )
        ).isFalse();

        assertThat(
                aiJobsRepository.existsById(
                        context.aiJobId()
                )
        ).isFalse();
    }

    @Test
    void deletingRecordRemovesLinkedCbtSessionAndItsAiJob()
            throws Exception {
        EmotionRecords record = createCompleteRecord(
                user,
                "CBT가 연결된 삭제 기록"
        );

        ReflectionSessions session =
                reflectionSessionsRepository.saveAndFlush(
                        ReflectionSessions.create(
                                user,
                                record
                        )
                );

        AiJobs reflectionJob = AiJobs.create(
                user,
                AiJobEntityType.REFLECTION,
                session.getId(),
                AiJobOperation.QUESTION,
                "linked-reflection-job"
        );

        reflectionJob.startProcessing();
        reflectionJob = aiJobsRepository.saveAndFlush(
                reflectionJob
        );

        Long sessionId = session.getId();
        Long reflectionJobId = reflectionJob.getId();

        mockMvc.perform(
                        delete(
                                "/api/records/{recordId}",
                                record.getId()
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isNoContent());

        assertThat(
                emotionRecordsRepository.existsById(
                        record.getId()
                )
        ).isFalse();

        assertThat(
                reflectionSessionsRepository.existsById(
                        sessionId
                )
        ).isFalse();

        assertThat(
                aiJobsRepository.existsById(
                        reflectionJobId
                )
        ).isFalse();
    }

    @Test
    void deletingOtherUsersRecordReturnsNotFoundAndKeepsRecord()
            throws Exception {
        Users otherUser = usersRepository.saveAndFlush(
                Users.create(
                        "other-delete-flow@example.com",
                        "unused-password-hash",
                        "다른 삭제 사용자"
                )
        );

        EmotionRecords otherRecord =
                createCompleteRecord(
                        otherUser,
                        "다른 사용자의 삭제 대상 기록"
                );

        mockMvc.perform(
                        delete(
                                "/api/records/{recordId}",
                                otherRecord.getId()
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        assertThat(
                emotionRecordsRepository.existsById(
                        otherRecord.getId()
                )
        ).isTrue();
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
                        Instant.parse("2026-09-21T02:00:00Z")
                );

        record.confirm(
                new EmotionRecordsConfirmRequestDto(
                        "삭제 테스트 상황",
                        "삭제 테스트 생각",
                        "ANXIETY",
                        (short) 5,
                        List.of(),
                        "WORK",
                        "COLLEAGUE",
                        Map.of()
                )
        );

        return emotionRecordsRepository.saveAndFlush(record);
    }

    private FastApiRecordAnalysisResponseDto successfulAnalysis() {
        return new FastApiRecordAnalysisResponseDto(
                new FastApiRecordAnalysisResponseDto
                        .StructuredRecord(
                        "늦게 도착한 상황",
                        "늦게 도착한 해석",
                        "늦게 도착한 생각",
                        List.of(
                                new FastApiRecordAnalysisResponseDto
                                        .EmotionItem(
                                        "ANXIETY",
                                        5
                                )
                        ),
                        "가슴이 답답하다",
                        "말을 줄였다",
                        "WORK",
                        "COLLEAGUE"
                ),
                null,
                new FastApiRecordAnalysisResponseDto
                        .AnalysisMeta(
                        "test-model",
                        "analyze-record-v1"
                )
        );
    }

    private void cleanDatabase() {
        aiJobsRepository.deleteAll();
        reflectionSessionsRepository.deleteAll();
        reportsRepository.deleteAll();
        emotionRecordsRepository.deleteAll();
        consentEventsRepository.deleteAll();
        usersRepository.deleteAll();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}