// 실패한 QUICK 감정 기록의 재분석과 상태별 재분석 제한을 검증

package com.my.mindot_back.records.service;

import com.my.mindot_back.ai.entity.AiJobStatus;
import org.springframework.boot.test.context.SpringBootTest;
import com.my.mindot_back.ai.repository.AiJobsRepository;
import com.my.mindot_back.records.client.FastApiRecordAnalysisClient;
import com.my.mindot_back.records.dto.EmotionRecordsConfirmRequestDto;
import com.my.mindot_back.records.dto.EmotionRecordsQuickCreateRequestDto;
import com.my.mindot_back.records.dto.ai.FastApiRecordAnalysisResponseDto;
import com.my.mindot_back.records.entity.CompletionStatus;
import com.my.mindot_back.records.entity.EmotionRecords;
import com.my.mindot_back.records.entity.InputType;
import com.my.mindot_back.records.repository.EmotionRecordsRepository;
import com.my.mindot_back.reports.service.ReportCacheInvalidationService;
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
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
class EmotionRecordReanalysisFlowTest
        extends PostgresContainerTestBase {

    @Autowired
    private EmotionRecordsService emotionRecordsService;

    @Autowired
    private EmotionRecordAiTransactionService
            emotionRecordAiTransactionService;

    @Autowired
    private EmotionRecordsRepository emotionRecordsRepository;

    @Autowired
    private AiJobsRepository aiJobsRepository;

    @Autowired
    private UsersRepository usersRepository;

    @Autowired
    private ConsentEventsRepository consentEventsRepository;

    @MockitoBean
    private FastApiRecordAnalysisClient
            fastApiRecordAnalysisClient;

    @MockitoBean
    private EmotionRecordSearchEmbeddingService
            searchEmbeddingService;

    @MockitoBean
    private ReportCacheInvalidationService
            reportCacheInvalidationService;

    private Users user;

    @BeforeEach
    void setUp() {
        cleanDatabase();

        user = usersRepository.saveAndFlush(
                Users.create(
                        "reanalysis-flow@example.com",
                        "unused-password-hash",
                        "재분석 테스트"
                )
        );

        consentEventsRepository.saveAndFlush(
                ConsentEvents.grant(
                        user,
                        ConsentType.AI_ANALYSIS,
                        "ai-analysis-v1"
                )
        );
    }

    @AfterEach
    void cleanUp() {
        reset(
                fastApiRecordAnalysisClient,
                searchEmbeddingService,
                reportCacheInvalidationService
        );

        cleanDatabase();
    }

    @Test
    void failedQuickRecordCanBeReanalyzedAndChangesToPartial() {
        EmotionRecordsQuickCreateRequestDto request =
                new EmotionRecordsQuickCreateRequestDto(
                        "처음 분석에는 실패한 감정 원문",
                        InputType.TEXT,
                        Instant.parse("2026-09-21T01:00:00Z")
                );

        when(
                fastApiRecordAnalysisClient.analyze(
                        request.rawText()
                )
        ).thenThrow(
                new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "FastAPI 분석 실패"
                )
        );

        var failedResponse =
                emotionRecordsService.createQuickRecord(
                        user.getId(),
                        request,
                        "failed-quick-key"
                );

        assertThat(failedResponse.completionStatus())
                .isEqualTo("QUICK");
        assertThat(failedResponse.analysisStatus())
                .isEqualTo("FAILED");

        reset(fastApiRecordAnalysisClient);

        when(
                fastApiRecordAnalysisClient.analyze(
                        request.rawText()
                )
        ).thenReturn(successfulAnalysis());

        var reanalyzed =
                emotionRecordsService.reanalyzeEmotionRecord(
                        user.getId(),
                        failedResponse.recordId()
                );

        assertThat(reanalyzed.emotionRecordId())
                .isEqualTo(failedResponse.recordId());
        assertThat(reanalyzed.rawText())
                .isEqualTo(request.rawText());
        assertThat(reanalyzed.completionStatus())
                .isEqualTo("PARTIAL");
        assertThat(reanalyzed.analysisStatus())
                .isEqualTo("COMPLETED");
        assertThat(reanalyzed.automaticThought())
                .isEqualTo("나는 일을 제대로 하지 못한다");

        EmotionRecords saved = emotionRecordsRepository
                .findById(failedResponse.recordId())
                .orElseThrow();

        assertThat(saved.getCompletionStatus())
                .isEqualTo(CompletionStatus.PARTIAL);
        assertThat(saved.getRawText())
                .isEqualTo(request.rawText());

        assertThat(aiJobsRepository.findAll())
                .hasSize(2)
                .extracting(job -> job.getStatus())
                .containsExactlyInAnyOrder(
                        AiJobStatus.FAILED,
                        AiJobStatus.COMPLETED
                );

        verify(
                fastApiRecordAnalysisClient,
                times(1)
        ).analyze(request.rawText());
    }

    @Test
    void processingPartialAndCompleteRecordsAreRejected() {
        EmotionRecords processingRecord =
                emotionRecordsRepository.saveAndFlush(
                        EmotionRecords.createQuick(
                                user,
                                "현재 분석 중인 원문",
                                InputType.TEXT,
                                Instant.parse(
                                        "2026-09-21T02:00:00Z"
                                )
                        )
                );

        emotionRecordAiTransactionService.startReanalysis(
                user.getId(),
                processingRecord.getId()
        );

        EmotionRecords partialRecord =
                EmotionRecords.createQuick(
                        user,
                        "이미 분석된 원문",
                        InputType.TEXT,
                        Instant.parse(
                                "2026-09-21T03:00:00Z"
                        )
                );

        partialRecord.applyAiAnalysis(successfulAnalysis());
        partialRecord =
                emotionRecordsRepository.saveAndFlush(partialRecord);

        EmotionRecords completeRecord =
                EmotionRecords.createQuick(
                        user,
                        "사용자 확인까지 끝난 원문",
                        InputType.TEXT,
                        Instant.parse(
                                "2026-09-21T04:00:00Z"
                        )
                );

        completeRecord.applyAiAnalysis(successfulAnalysis());

        completeRecord.confirm(
                new EmotionRecordsConfirmRequestDto(
                        "회의에서 실수했다",
                        "나는 일을 제대로 하지 못한다",
                        "ANXIETY",
                        (short) 7,
                        List.of(),
                        "WORK",
                        "COLLEAGUE",
                        Map.of()
                )
        );

        completeRecord =
                emotionRecordsRepository.saveAndFlush(completeRecord);

        assertConflict(processingRecord.getId());
        assertConflict(partialRecord.getId());
        assertConflict(completeRecord.getId());

        assertThat(
                emotionRecordsRepository
                        .findById(processingRecord.getId())
                        .orElseThrow()
                        .getCompletionStatus()
        ).isEqualTo(CompletionStatus.QUICK);

        assertThat(
                emotionRecordsRepository
                        .findById(partialRecord.getId())
                        .orElseThrow()
                        .getCompletionStatus()
        ).isEqualTo(CompletionStatus.PARTIAL);

        assertThat(
                emotionRecordsRepository
                        .findById(completeRecord.getId())
                        .orElseThrow()
                        .getCompletionStatus()
        ).isEqualTo(CompletionStatus.COMPLETE);
    }

    private void assertConflict(Long emotionRecordId) {
        assertThatThrownBy(
                () -> emotionRecordsService
                        .reanalyzeEmotionRecord(
                                user.getId(),
                                emotionRecordId
                        )
        )
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(
                        ((ResponseStatusException) error)
                                .getStatusCode()
                ).isEqualTo(HttpStatus.CONFLICT));
    }

    private FastApiRecordAnalysisResponseDto successfulAnalysis() {
        return new FastApiRecordAnalysisResponseDto(
                new FastApiRecordAnalysisResponseDto.StructuredRecord(
                        "회의에서 실수했다",
                        "실수하면 인정받지 못할 것이다",
                        "나는 일을 제대로 하지 못한다",
                        List.of(
                                new FastApiRecordAnalysisResponseDto
                                        .EmotionItem(
                                        "ANXIETY",
                                        7
                                )
                        ),
                        "가슴이 답답하다",
                        "말수가 줄었다",
                        "WORK",
                        "COLLEAGUE"
                ),
                null,
                new FastApiRecordAnalysisResponseDto.AnalysisMeta(
                        "test-model",
                        "analyze-record-v1"
                )
        );
    }

    private void cleanDatabase() {
        aiJobsRepository.deleteAll();
        emotionRecordsRepository.deleteAll();
        consentEventsRepository.deleteAll();
        usersRepository.deleteAll();
    }
}