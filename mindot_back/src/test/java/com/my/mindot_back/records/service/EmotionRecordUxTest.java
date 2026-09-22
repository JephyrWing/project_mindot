package com.my.mindot_back.records.service;

import com.my.mindot_back.records.dto.*;
import com.my.mindot_back.records.entity.*;
import com.my.mindot_back.records.repository.*;
import com.my.mindot_back.reports.service.ReportCacheInvalidationService;
import com.my.mindot_back.users.entity.Users;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmotionRecordUxTest {
    @Mock EmotionRecordsRepository records;
    @Mock ReflectionSessionsRepository sessions;
    @Mock ReportCacheInvalidationService cache;
    @Mock EmotionRecordAiTransactionService analysis;
    @Mock EmotionRecordSearchEmbeddingTransactionService searchEmbeddingTransactions;
    @Mock EmotionRecordSearchEmbeddingService searchEmbeddingService;
    @InjectMocks EmotionRecordsService service;
    EmotionRecords record;

    @BeforeEach void setup() {
        Users user = mock(Users.class);
        lenient().when(user.getId()).thenReturn(7L);
        record = EmotionRecords.createQuick(user, "테스트 원문", InputType.TEXT, Instant.parse("2026-09-15T00:00:00Z"));
        ReflectionTestUtils.setField(record, "id", 1L);
        ReflectionTestUtils.setField(record, "completionStatus", CompletionStatus.PARTIAL);
        lenient().when(records.findLockedById(1L)).thenReturn(Optional.of(record));
        lenient().when(analysis.detailResponse(7L, 1L)).thenAnswer(call -> EmotionRecordsDetailResponseDto.from(record));
    }
    EmotionRecordsConfirmRequestDto confirm(String thought, String emotion) {
        return new EmotionRecordsConfirmRequestDto("상황", thought, emotion, (short)5, List.of(), "WORK", null, Map.of());
    }
    EmotionRecordsUpdateRequestDto patch(Instant occurredAt, String thought) {
        var dto = new EmotionRecordsUpdateRequestDto();
        if (occurredAt != null) dto.setOccurredAt(occurredAt);
        if (thought != null) dto.setAutomaticThought(thought);
        return dto;
    }
    void completeEmpty() { service.confirmEmotionRecord(7L, 1L, confirm("  ", "  먹먹함  ")); }

    @Test void optionalThoughtAndCustomEmotionSurviveConfirmation() {
        completeEmpty();
        assertNull(record.getAutomaticThought());
        assertEquals("먹먹함", record.getPrimaryEmotionCode());
        assertEquals(CompletionStatus.COMPLETE, record.getCompletionStatus());
    }
    @Test void firstThoughtIsDurableAndIdenticalRetriesAreIdempotent() {
        completeEmpty();
        var dto = patch(null, "  혼자 남겨질 것 같다  ");
        assertEquals("혼자 남겨질 것 같다", service.updateEmotionRecord(7L, 1L, dto).automaticThought());
        // A retry after the session has opened is still safe because no value changes.
        lenient().when(sessions.existsByEmotionRecord_Id(1L)).thenReturn(true);
        assertEquals("혼자 남겨질 것 같다", service.updateEmotionRecord(7L, 1L, dto).automaticThought());
        assertEquals("먹먹함", record.getPrimaryEmotionCode());
        assertEquals("상황", record.getSituationText());
    }
    @Test void aCompetingDifferentThoughtCannotOverwriteTheFirstWriter() {
        completeEmpty();
        service.updateEmotionRecord(7L, 1L, patch(null, "먼저 저장된 생각"));
        var error = assertThrows(ResponseStatusException.class,
                () -> service.updateEmotionRecord(7L, 1L, patch(null, "다른 요청의 생각")));
        assertEquals(409, error.getStatusCode().value());
        assertEquals("먼저 저장된 생각", record.getAutomaticThought());
    }
    @Test void otherOwnerCannotSaveThought() {
        completeEmpty();
        var error = assertThrows(ResponseStatusException.class,
                () -> service.updateEmotionRecord(99L, 1L, patch(null, "생각")));
        assertEquals(404, error.getStatusCode().value());
        assertNull(record.getAutomaticThought());
    }
    @Test void existingSessionBlocksFillingAnEmptyThought() {
        completeEmpty();
        when(sessions.existsByEmotionRecord_Id(1L)).thenReturn(true);
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> service.updateEmotionRecord(7L, 1L, patch(null, "생각"))).getStatusCode().value());
        assertNull(record.getAutomaticThought());
    }
    @Test void confirmRemainsPartialOnlyAndPartialCbtPathStillWorks() {
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> service.updateEmotionRecord(7L, 1L, patch(null, "생각"))).getStatusCode().value());
        assertEquals("기존 PARTIAL 생각", service.confirmEmotionRecord(7L, 1L, confirm("기존 PARTIAL 생각", "불안")).automaticThought());
        assertEquals("ANXIETY", record.getPrimaryEmotionCode());
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> service.confirmEmotionRecord(7L, 1L, confirm("새 생각", "JOY"))).getStatusCode().value());
        assertEquals("기존 PARTIAL 생각", record.getAutomaticThought());
    }
    @Test void partialSessionCannotHaveItsBaselineOverwritten() {
        when(sessions.existsByEmotionRecord_Id(1L)).thenReturn(true);
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> service.confirmEmotionRecord(7L, 1L, confirm("새 생각", "JOY"))).getStatusCode().value());
    }
    @Test void timeEditKeepsTheActualPendingAnalysisStatus() {
        ReflectionTestUtils.setField(record, "completionStatus", CompletionStatus.QUICK);
        var response = EmotionRecordsDetailResponseDto.from(record, null, "PROCESSING");
        when(analysis.detailResponse(7L, 1L)).thenReturn(response);
        var result = service.updateEmotionRecord(7L, 1L,
                patch(Instant.parse("2026-09-15T00:10:00Z"), null));
        assertEquals("PROCESSING", result.analysisStatus());
        assertEquals(Instant.parse("2026-09-15T00:10:00Z"), record.getOccurredAt());
    }

    @Test void thoughtOnlyPatchPreservesTimeAndOtherFields() {
        completeEmpty();
        var before = record.getOccurredAt();
        service.updateEmotionRecord(7L, 1L, patch(null, "생각"));
        assertEquals(before, record.getOccurredAt());
        assertEquals("먹먹함", record.getPrimaryEmotionCode());
        assertEquals("상황", record.getSituationText());
    }

    @Test void timeOnlyPatchIsAlsoBlockedOnceASessionExists() {
        service.confirmEmotionRecord(7L, 1L, confirm("기존 생각", "JOY"));
        when(sessions.existsByEmotionRecord_Id(1L)).thenReturn(true);
        var before = record.getOccurredAt();
        var time = Instant.parse("2026-09-15T00:10:00Z");
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> service.updateEmotionRecord(7L, 1L, patch(time, null))).getStatusCode().value());
        assertEquals(before, record.getOccurredAt());
        assertEquals("기존 생각", record.getAutomaticThought());
    }

    @Test void fullEditReplacesStructuredFieldsAndRefreshesRawSearchAfterCommit() {
        completeEmpty();
        record.applySearchEmbedding(new float[1536]);
        var dto = patch(record.getOccurredAt(), null);
        dto.setRawText("수정한 원문");
        dto.setAnalysis(new EmotionRecordsConfirmRequestDto("새 상황", "  ", "짜증", (short)3,
                List.of(Map.of("code", "SADNESS", "intensity", 2)), "HEALTH", "FRIEND",
                Map.of("bodyReaction", "손 떨림", "behavior", "산책")));
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            var result = service.updateEmotionRecord(7L, 1L, dto);
            assertEquals("수정한 원문", result.rawText());
            assertEquals("새 상황", result.situationText());
            assertEquals("짜증", result.primaryEmotionCode());
            assertNull(result.automaticThought());
            assertEquals((short)3, result.primaryIntensity());
            assertEquals("HEALTH", result.contextCategory());
            assertEquals("FRIEND", result.relatedPersonType());
            assertEquals("산책", result.details().get("behavior"));
            assertEquals(1, result.secondaryEmotions().size());
            assertNull(record.getSearchEmbedding());
            verify(searchEmbeddingTransactions).invalidate(7L, 1L);
            verifyNoInteractions(searchEmbeddingService);
            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                    .forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);
            verify(searchEmbeddingService).submit(7L, 1L);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test void fullEditCannotOverwriteAnExistingSessionOrAnotherOwnersRecord() {
        completeEmpty();
        var dto = patch(record.getOccurredAt().plusSeconds(60), null);
        dto.setRawText("수정 원문");
        dto.setAnalysis(confirm("새 생각", "JOY"));
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> service.updateEmotionRecord(99L, 1L, dto)).getStatusCode().value());
        when(sessions.existsByEmotionRecord_Id(1L)).thenReturn(true);
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> service.updateEmotionRecord(7L, 1L, dto)).getStatusCode().value());
        assertEquals("테스트 원문", record.getRawText());
        assertEquals("먹먹함", record.getPrimaryEmotionCode());
        verifyNoInteractions(searchEmbeddingTransactions);
    }

    @Test void fullEditRequiresConfirmationFirst() {
        var dto = patch(null, null);
        dto.setAnalysis(confirm(null, "JOY"));
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> service.updateEmotionRecord(7L, 1L, dto)).getStatusCode().value());
        assertEquals(CompletionStatus.PARTIAL, record.getCompletionStatus());
    }

    @Test void combinedPatchUpdatesBothAndSameThoughtRetryStillAllowsTimeEdit() {
        completeEmpty();
        var time = Instant.parse("2026-09-15T00:10:00Z");
        service.updateEmotionRecord(7L, 1L, patch(time, "생각"));
        assertEquals(time, record.getOccurredAt());
        assertEquals("생각", record.getAutomaticThought());
        service.updateEmotionRecord(7L, 1L, patch(time.plusSeconds(60), "생각"));
        assertEquals(time.plusSeconds(60), record.getOccurredAt());
    }

    @Test void combinedPatchWithAConflictingThoughtDoesNotChangeTime() {
        service.confirmEmotionRecord(7L, 1L, confirm("기존 생각", "JOY"));
        var before = record.getOccurredAt();
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> service.updateEmotionRecord(7L, 1L,
                        patch(before.plusSeconds(60), "다른 생각"))).getStatusCode().value());
        assertEquals(before, record.getOccurredAt());
        assertEquals("기존 생각", record.getAutomaticThought());
    }

    @Test void emptyPatchAndInvalidThoughtCannotMutateRecord() {
        completeEmpty();
        for (var dto : List.of(patch(null, null),
                patch(null, "   "),
                patch(null, "가".repeat(4001)))) {
            assertEquals(400, assertThrows(ResponseStatusException.class,
                    () -> service.updateEmotionRecord(7L, 1L, dto)).getStatusCode().value());
        }
        assertNull(record.getAutomaticThought());
    }

    @Test void requestValidationUsesNormalizedValuesAndExactEmotionLabels() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertTrue(validator.validate(confirm(null, "가".repeat(50))).isEmpty());
            assertFalse(validator.validate(confirm(null, "가".repeat(51))).isEmpty());
            assertFalse(validator.validate(confirm(null, "   ")).isEmpty());
            assertFalse(validator.validate(confirm(null, "__CUSTOM_EMOTION__")).isEmpty());
            assertTrue(validator.validate(confirm("   ", "불안")).isEmpty());
            assertFalse(validator.validate(confirm("가".repeat(4001), "불안")).isEmpty());
            assertFalse(validator.validate(patch(null, "   ")).isEmpty());
            assertFalse(validator.validate(patch(null, "가".repeat(4001))).isEmpty());
            assertTrue(validator.validate(patch(null, "가".repeat(4000))).isEmpty());
            assertEquals("ANXIETY", confirm(null, "  불안  ").primaryEmotionCode());
            assertEquals("불안한 마음", confirm(null, "불안한 마음").primaryEmotionCode());
            assertEquals("MixedCase", EmotionNames.normalize(" MixedCase "));
            assertEquals("OTHER", confirm(null, "OTHER").primaryEmotionCode());
        }
    }
}
