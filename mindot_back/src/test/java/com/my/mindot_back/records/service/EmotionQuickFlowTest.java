// 빠른 감정 기록의 분석 성공·실패·멱등 재요청·동시 요청을 실제 DB로 검증

package com.my.mindot_back.records.service;

import com.my.mindot_back.ai.entity.AiJobStatus;
import com.my.mindot_back.ai.repository.AiJobsRepository;
import com.my.mindot_back.records.client.FastApiRecordAnalysisClient;
import com.my.mindot_back.records.dto.EmotionRecordsQuickCreateRequestDto;
import com.my.mindot_back.records.dto.EmotionRecordsQuickCreateResponseDto;
import com.my.mindot_back.records.dto.ai.FastApiRecordAnalysisResponseDto;
import com.my.mindot_back.records.entity.CompletionStatus;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
class EmotionQuickFlowTest
        extends PostgresContainerTestBase {

    @Autowired
    private EmotionRecordsService emotionRecordsService;

    @Autowired
    private EmotionRecordsRepository emotionRecordsRepository;

    @Autowired
    private AiJobsRepository aiJobsRepository;

    @Autowired
    private UsersRepository usersRepository;

    @Autowired
    private ConsentEventsRepository consentEventsRepository;

    @MockitoBean
    private FastApiRecordAnalysisClient fastApiRecordAnalysisClient;

    @MockitoBean
    private EmotionRecordSearchEmbeddingService searchEmbeddingService;

    @MockitoBean
    private ReportCacheInvalidationService reportCacheInvalidationService;

    private Users user;

    @BeforeEach
    void setUp() {
        cleanDatabase();

        user = usersRepository.saveAndFlush(
                Users.create(
                        "quick-flow-test@example.com",
                        "unused-password-hash",
                        "빠른 기록 테스트"
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
    void successfulAnalysisPreservesRawTextAndChangesStatusToPartial() {
        EmotionRecordsQuickCreateRequestDto request =
                request("분석 성공 원문");

        when(
                fastApiRecordAnalysisClient.analyze(
                        request.rawText()
                )
        ).thenReturn(successfulAnalysis());

        EmotionRecordsQuickCreateResponseDto response =
                emotionRecordsService.createQuickRecord(
                        user.getId(),
                        request,
                        "success-key"
                );

        assertThat(response.rawText())
                .isEqualTo("분석 성공 원문");
        assertThat(response.completionStatus())
                .isEqualTo("PARTIAL");
        assertThat(response.analysisStatus())
                .isEqualTo("COMPLETED");

        var saved = emotionRecordsRepository
                .findById(response.recordId())
                .orElseThrow();

        assertThat(saved.getRawText())
                .isEqualTo("분석 성공 원문");
        assertThat(saved.getCompletionStatus())
                .isEqualTo(CompletionStatus.PARTIAL);
        assertThat(saved.getAutomaticThought())
                .isEqualTo("나는 일을 잘하지 못한다");

        assertThat(aiJobsRepository.findAll())
                .singleElement()
                .extracting(job -> job.getStatus())
                .isEqualTo(AiJobStatus.COMPLETED);
    }

    @Test
    void fastApiFailurePreservesRawTextInQuickStatus() {
        EmotionRecordsQuickCreateRequestDto request =
                request("분석 실패 후에도 보존할 원문");

        when(
                fastApiRecordAnalysisClient.analyze(
                        request.rawText()
                )
        ).thenThrow(
                new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "AI 분석 서버 오류"
                )
        );

        EmotionRecordsQuickCreateResponseDto response =
                emotionRecordsService.createQuickRecord(
                        user.getId(),
                        request,
                        "failure-key"
                );

        assertThat(response.rawText())
                .isEqualTo("분석 실패 후에도 보존할 원문");
        assertThat(response.completionStatus())
                .isEqualTo("QUICK");
        assertThat(response.analysisStatus())
                .isEqualTo("FAILED");
        assertThat(response.analysisErrorCode())
                .isEqualTo("FAST_API_ANALYSIS_FAILED");

        var saved = emotionRecordsRepository
                .findById(response.recordId())
                .orElseThrow();

        assertThat(saved.getRawText())
                .isEqualTo("분석 실패 후에도 보존할 원문");
        assertThat(saved.getCompletionStatus())
                .isEqualTo(CompletionStatus.QUICK);

        assertThat(aiJobsRepository.findAll())
                .singleElement()
                .extracting(job -> job.getStatus())
                .isEqualTo(AiJobStatus.FAILED);
    }

    @Test
    void repeatedIdempotencyKeyCreatesOnlyOneRecord() {
        EmotionRecordsQuickCreateRequestDto request =
                request("중복 요청 원문");

        when(
                fastApiRecordAnalysisClient.analyze(
                        request.rawText()
                )
        ).thenReturn(successfulAnalysis());

        EmotionRecordsQuickCreateResponseDto first =
                emotionRecordsService.createQuickRecord(
                        user.getId(),
                        request,
                        "same-key"
                );

        EmotionRecordsQuickCreateResponseDto second =
                emotionRecordsService.createQuickRecord(
                        user.getId(),
                        request,
                        "same-key"
                );

        assertThat(second.recordId())
                .isEqualTo(first.recordId());
        assertThat(emotionRecordsRepository.count())
                .isEqualTo(1);
        assertThat(aiJobsRepository.count())
                .isEqualTo(1);

        verify(
                fastApiRecordAnalysisClient,
                times(1)
        ).analyze(request.rawText());
    }

    @Test
    void concurrentSameRequestCreatesOneRecordAndDispatchesAiOnce()
            throws Exception {
        EmotionRecordsQuickCreateRequestDto request =
                request("동시 요청 원문");

        CountDownLatch analysisStarted =
                new CountDownLatch(1);
        CountDownLatch releaseAnalysis =
                new CountDownLatch(1);

        when(
                fastApiRecordAnalysisClient.analyze(
                        request.rawText()
                )
        ).thenAnswer(invocation -> {
            analysisStarted.countDown();

            if (!releaseAnalysis.await(
                    10,
                    TimeUnit.SECONDS
            )) {
                throw new IllegalStateException(
                        "AI 응답 대기 시간이 초과됐습니다"
                );
            }

            return successfulAnalysis();
        });

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        try {
            Future<EmotionRecordsQuickCreateResponseDto> firstFuture =
                    executor.submit(
                            () -> emotionRecordsService
                                    .createQuickRecord(
                                            user.getId(),
                                            request,
                                            "concurrent-key"
                                    )
                    );

            assertThat(
                    analysisStarted.await(
                            10,
                            TimeUnit.SECONDS
                    )
            ).isTrue();

            Future<EmotionRecordsQuickCreateResponseDto> secondFuture =
                    executor.submit(
                            () -> emotionRecordsService
                                    .createQuickRecord(
                                            user.getId(),
                                            request,
                                            "concurrent-key"
                                    )
                    );

            EmotionRecordsQuickCreateResponseDto second =
                    secondFuture.get(
                            10,
                            TimeUnit.SECONDS
                    );

            releaseAnalysis.countDown();

            EmotionRecordsQuickCreateResponseDto first =
                    firstFuture.get(
                            10,
                            TimeUnit.SECONDS
                    );

            assertThat(second.recordId())
                    .isEqualTo(first.recordId());
        } finally {
            releaseAnalysis.countDown();
            executor.shutdownNow();
        }

        assertThat(emotionRecordsRepository.count())
                .isEqualTo(1);
        assertThat(aiJobsRepository.count())
                .isEqualTo(1);

        verify(
                fastApiRecordAnalysisClient,
                times(1)
        ).analyze(request.rawText());
    }

    private EmotionRecordsQuickCreateRequestDto request(
            String rawText
    ) {
        return new EmotionRecordsQuickCreateRequestDto(
                rawText,
                InputType.TEXT,
                Instant.parse("2026-09-21T01:00:00Z")
        );
    }

    private FastApiRecordAnalysisResponseDto successfulAnalysis() {
        return new FastApiRecordAnalysisResponseDto(
                new FastApiRecordAnalysisResponseDto
                        .StructuredRecord(
                        "회의에서 실수했다",
                        "실수하면 인정받지 못할 것이다",
                        "나는 일을 잘하지 못한다",
                        List.of(
                                new FastApiRecordAnalysisResponseDto
                                        .EmotionItem(
                                        "ANXIETY",
                                        7
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
        emotionRecordsRepository.deleteAll();
        consentEventsRepository.deleteAll();
        usersRepository.deleteAll();
    }
}