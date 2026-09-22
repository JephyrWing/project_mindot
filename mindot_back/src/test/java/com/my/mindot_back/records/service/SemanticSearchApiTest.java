// 감정 기록 검색 임베딩과 의미 검색의 경계·소유권·동의 검증

package com.my.mindot_back.records.service;

import com.my.mindot_back.ai.entity.AiJobOperation;
import com.my.mindot_back.ai.entity.AiJobStatus;
import com.my.mindot_back.ai.repository.AiJobsRepository;
import com.my.mindot_back.common.rag.RagUtils;
import com.my.mindot_back.records.entity.EmotionRecords;
import com.my.mindot_back.records.dto.EmotionRecordsListPeriod;
import com.my.mindot_back.records.entity.InputType;
import com.my.mindot_back.records.repository.EmotionRecordsRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest
class SemanticSearchApiTest
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
    private RagUtils ragUtils;

    private Users user;
    private Users otherUser;

    @BeforeEach
    void setUp() {
        cleanDatabase();

        user = usersRepository.saveAndFlush(
                Users.create(
                        "semantic-owner@example.com",
                        "unused-password-hash",
                        "검색 사용자"
                )
        );

        otherUser = usersRepository.saveAndFlush(
                Users.create(
                        "semantic-other@example.com",
                        "unused-password-hash",
                        "다른 사용자"
                )
        );

        grantAiConsent(user);
        grantAiConsent(otherUser);
    }

    @AfterEach
    void cleanUp() {
        reset(ragUtils);
        cleanDatabase();
    }

    @Test
    void embeddingRetryGeneratesVectorOnlyOnce() {
        EmotionRecords record = saveRecord(
                user,
                "회사 회의 때문에 불안했다",
                "2026-09-20T01:00:00Z"
        );

        float[] embedding = vector(1.0F, 0.0F);

        when(ragUtils.embed(record.getRawText()))
                .thenReturn(embedding);

        emotionRecordsService.retrySearchEmbedding(
                user.getId(),
                record.getId()
        );

        emotionRecordsService.retrySearchEmbedding(
                user.getId(),
                record.getId()
        );

        EmotionRecords saved = emotionRecordsRepository
                .findById(record.getId())
                .orElseThrow();

        assertThat(saved.getSearchEmbedding())
                .containsExactly(embedding);

        assertThat(aiJobsRepository.findAll())
                .singleElement()
                .satisfies(job -> {
                    assertThat(job.getOperation())
                            .isEqualTo(AiJobOperation.EMBED);
                    assertThat(job.getStatus())
                            .isEqualTo(AiJobStatus.COMPLETED);
                    assertThat(job.getEntityId())
                            .isEqualTo(record.getId());
                });

        verify(ragUtils, times(1))
                .embed(record.getRawText());
    }

    @Test
    void searchReturnsOnlyOwnedRelevantRecordsInSimilarityOrder() {
        float[] queryVector = vector(1.0F, 0.0F);

        EmotionRecords exactMatch = saveRecordWithEmbedding(
                user,
                "회의에서 발표를 앞두고 불안했다",
                "2026-09-20T04:00:00Z",
                vector(1.0F, 0.0F)
        );

        EmotionRecords similarMatch = saveRecordWithEmbedding(
                user,
                "업무 평가가 걱정됐다",
                "2026-09-20T03:00:00Z",
                vector(0.8F, 0.6F)
        );

        saveRecordWithEmbedding(
                user,
                "친구와 즐겁게 산책했다",
                "2026-09-20T02:00:00Z",
                vector(0.6F, 0.8F)
        );

        saveRecordWithEmbedding(
                otherUser,
                "다른 사용자의 정확한 일치 기록",
                "2026-09-20T05:00:00Z",
                vector(1.0F, 0.0F)
        );

        when(ragUtils.embed("회의와 업무 불안"))
                .thenReturn(queryVector);

        var response =
                emotionRecordsService
                        .searchEmotionRecordsSemantically(
                                user.getId(),
                                "  회의와 업무 불안  ",
                                EmotionRecordsListPeriod.ALL,
                                null,
                                null,
                                0,
                                10
                        );

        assertThat(response.totalElements()).isEqualTo(2);
        assertThat(response.content())
                .extracting(item -> item.emotionRecordId())
                .containsExactly(
                        exactMatch.getId(),
                        similarMatch.getId()
                );

        assertThat(response.content())
                .extracting(item -> item.rawText())
                .doesNotContain(
                        "친구와 즐겁게 산책했다",
                        "다른 사용자의 정확한 일치 기록"
                );

        verify(ragUtils, times(1))
                .embed("회의와 업무 불안");
    }

    @Test
    void invalidSearchBoundariesAreRejectedBeforeEmbedding() {
        assertBadRequest(
                "",
                EmotionRecordsListPeriod.ALL,
                0,
                10
        );

        assertBadRequest(
                "가".repeat(501),
                EmotionRecordsListPeriod.ALL,
                0,
                10
        );

        assertBadRequest(
                "검색어",
                EmotionRecordsListPeriod.ALL,
                -1,
                10
        );

        assertBadRequest(
                "검색어",
                EmotionRecordsListPeriod.ALL,
                0,
                51
        );

        verifyNoInteractions(ragUtils);
    }

    @Test
    void revokedAiConsentBlocksSearchAndEmbeddingRetry() {
        EmotionRecords record = saveRecord(
                user,
                "동의 철회 후 검색할 수 없는 기록",
                "2026-09-20T01:00:00Z"
        );

        consentEventsRepository.saveAndFlush(
                ConsentEvents.revoke(
                        user,
                        ConsentType.AI_ANALYSIS,
                        "ai-analysis-v1"
                )
        );

        assertForbidden(
                () -> emotionRecordsService
                        .searchEmotionRecordsSemantically(
                                user.getId(),
                                "검색할 문장",
                                EmotionRecordsListPeriod.ALL,
                                null,
                                null,
                                0,
                                10
                        )
        );

        assertForbidden(
                () -> emotionRecordsService
                        .retrySearchEmbedding(
                                user.getId(),
                                record.getId()
                        )
        );

        assertThat(
                emotionRecordsRepository
                        .findById(record.getId())
                        .orElseThrow()
                        .getSearchEmbedding()
        ).isNull();

        assertThat(aiJobsRepository.count()).isZero();
        verifyNoInteractions(ragUtils);
    }

    private void assertBadRequest(
            String query,
            EmotionRecordsListPeriod period,
            int page,
            int size
    ) {
        assertThatThrownBy(
                () -> emotionRecordsService
                        .searchEmotionRecordsSemantically(
                                user.getId(),
                                query,
                                period,
                                null,
                                null,
                                page,
                                size
                        )
        )
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(
                        ((ResponseStatusException) error)
                                .getStatusCode()
                ).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    private void assertForbidden(
            ThrowingCall call
    ) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(
                        ((ResponseStatusException) error)
                                .getStatusCode()
                ).isEqualTo(HttpStatus.FORBIDDEN));
    }

    private EmotionRecords saveRecord(
            Users owner,
            String rawText,
            String occurredAt
    ) {
        return emotionRecordsRepository.saveAndFlush(
                EmotionRecords.createQuick(
                        owner,
                        rawText,
                        InputType.TEXT,
                        Instant.parse(occurredAt)
                )
        );
    }

    private EmotionRecords saveRecordWithEmbedding(
            Users owner,
            String rawText,
            String occurredAt,
            float[] embedding
    ) {
        EmotionRecords record = EmotionRecords.createQuick(
                owner,
                rawText,
                InputType.TEXT,
                Instant.parse(occurredAt)
        );

        record.applySearchEmbedding(embedding);

        return emotionRecordsRepository.saveAndFlush(record);
    }

    private void grantAiConsent(Users targetUser) {
        consentEventsRepository.saveAndFlush(
                ConsentEvents.grant(
                        targetUser,
                        ConsentType.AI_ANALYSIS,
                        "ai-analysis-v1"
                )
        );
    }

    private float[] vector(
            float first,
            float second
    ) {
        float[] vector = new float[1536];
        vector[0] = first;
        vector[1] = second;
        return vector;
    }

    private void cleanDatabase() {
        aiJobsRepository.deleteAll();
        emotionRecordsRepository.deleteAll();
        consentEventsRepository.deleteAll();
        usersRepository.deleteAll();
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }
}