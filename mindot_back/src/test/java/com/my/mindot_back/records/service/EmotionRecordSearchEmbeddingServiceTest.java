// 감정 기록 검색 임베딩의 중복 방지·성공 저장·실패 기록·벡터 검증을 확인
package com.my.mindot_back.records.service;

import com.my.mindot_back.common.rag.RagUtils;
import com.my.mindot_back.users.service.ConsentEventsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EmotionRecordSearchEmbeddingServiceTest {

    private EmotionRecordSearchEmbeddingTransactionService transactionService;
    private RagUtils ragUtils;
    private ConsentEventsService consentEventsService;
    private EmotionRecordSearchEmbeddingService service;

    @BeforeEach
    void setUp() {
        transactionService = mock(
                EmotionRecordSearchEmbeddingTransactionService.class
        );
        ragUtils = mock(RagUtils.class);
        consentEventsService = mock(ConsentEventsService.class);
        service = new EmotionRecordSearchEmbeddingService(
                transactionService,
                ragUtils,
                consentEventsService
        );
    }

    @Test
    void retrySkipsExternalCallWhenEmbeddingAlreadyExists() {
        when(transactionService.start(7L, 78L))
                .thenReturn(new EmotionRecordSearchEmbeddingContext(
                        78L,
                        89L,
                        "이미 임베딩이 저장된 기록",
                        false
                ));

        service.retry(7L, 78L);

        verify(transactionService).start(7L, 78L);
        verifyNoInteractions(ragUtils);
        verify(transactionService, never()).complete(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(float[].class)
        );
    }

    @Test
    void retrySavesGeneratedEmbeddingWhenDispatchIsRequired() {
        float[] embedding = new float[1536];
        when(transactionService.start(7L, 78L))
                .thenReturn(new EmotionRecordSearchEmbeddingContext(
                        78L,
                        89L,
                        "업무 피드백 때문에 불안했다",
                        true
                ));
        when(ragUtils.embed("업무 피드백 때문에 불안했다"))
                .thenReturn(embedding);

        service.retry(7L, 78L);

        verify(transactionService).complete(
                7L,
                78L,
                89L,
                embedding
        );
        verify(transactionService, never()).fail(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString()
        );
    }

    @Test
    void retryRecordsFailureAndReturnsBadGateway() {
        when(transactionService.start(7L, 78L))
                .thenReturn(new EmotionRecordSearchEmbeddingContext(
                        78L,
                        89L,
                        "업무 피드백 때문에 불안했다",
                        true
                ));
        when(ragUtils.embed("업무 피드백 때문에 불안했다"))
                .thenThrow(new IllegalStateException("외부 호출 실패"));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.retry(7L, 78L)
        );

        assertThat(exception.getStatusCode())
                .isEqualTo(HttpStatus.BAD_GATEWAY);
        verify(transactionService).fail(
                7L,
                78L,
                89L,
                "RECORD_SEARCH_EMBEDDING_FAILED"
        );
        verify(transactionService, never()).complete(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(float[].class)
        );
    }

    @Test
    void searchQueryRejectsEmbeddingWithWrongDimensions() {
        when(ragUtils.embed("업무 불안"))
                .thenReturn(new float[10]);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.embedSearchQuery(7L, "업무 불안")
        );

        assertThat(exception.getStatusCode())
                .isEqualTo(HttpStatus.BAD_GATEWAY);
    }
}
