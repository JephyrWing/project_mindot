package com.my.mindot_back.records.service;

import com.my.mindot_back.common.rag.RagUtils;
import com.my.mindot_back.records.client.FastApiPatternExplanationClient;
import com.my.mindot_back.records.dto.*;
import com.my.mindot_back.records.dto.ai.*;
import com.my.mindot_back.records.entity.*;
import com.my.mindot_back.records.repository.*;
import com.my.mindot_back.users.entity.Users;
import com.my.mindot_back.users.service.ConsentEventsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AutomaticPatternExplanationTest {
    @Mock EmotionRecordsRepository records;
    @Mock ReflectionSessionsRepository sessions;
    @Mock SessionDistortionsRepository distortions;
    @Mock ConsentEventsService consent;
    @Mock RagUtils rag;
    @Mock FastApiPatternExplanationClient ai;
    @InjectMocks EmotionRecordsService service;
    EmotionRecords record;

    @BeforeEach void setup() {
        var user = mock(Users.class);
        record = EmotionRecords.createQuick(user, "기록", InputType.TEXT, Instant.now());
        ReflectionTestUtils.setField(record, "id", 1L);
        record.confirm(new EmotionRecordsConfirmRequestDto("상황", null, "짜증", (short)4,
                List.of(), "WORK", null, Map.of()));
        when(records.findByIdAndUser_Id(1L, 7L)).thenReturn(Optional.of(record));
    }

    @Test void nineConfirmedSessionsDoNotStartAnyAiRequest() {
        when(sessions.countByUser_IdAndStatusAndUserConfirmedTrueAndEmotionRecord_IdNot(
                7L, ReflectionSessionStatus.COMPLETED, 1L)).thenReturn(9L);
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> service.explainPattern(7L, 1L)).getStatusCode().value());
        verifyNoInteractions(rag, ai);
    }

    @Test void tenConfirmedSessionsAllowSearchWithoutDateOrHelpfulnessRestrictions() {
        prepareSimilarCase();
        when(ai.explain(any())).thenReturn(new FastApiPatternExplanationResponseDto(
                "과거에 확인한 유사한 흐름", List.of(), null, "이번에도 비슷하게 느꼈나요?"));
        var result = service.explainPattern(7L, 1L);
        assertEquals(1, result.similarCaseCount());
        assertEquals("과거에 확인한 유사한 흐름", result.patternSummary());
        verify(sessions).countByUser_IdAndStatusAndUserConfirmedTrueAndEmotionRecord_IdNot(
                7L, ReflectionSessionStatus.COMPLETED, 1L);
        verify(sessions, times(3)).existsByEmotionRecord_IdAndStatus(1L, ReflectionSessionStatus.COMPLETED);
        verifyNoMoreInteractions(sessions);
        var request = ArgumentCaptor.forClass(FastApiPatternExplanationRequestDto.class);
        verify(ai).explain(request.capture());
        assertEquals((short)0, request.getValue().similarCases().get(0).helpfulnessScore());
    }

    private void prepareSimilarCase() {
        when(sessions.countByUser_IdAndStatusAndUserConfirmedTrueAndEmotionRecord_IdNot(
                7L, ReflectionSessionStatus.COMPLETED, 1L)).thenReturn(10L);
        var similar = mock(ReflectionSessions.class);
        when(similar.getId()).thenReturn(2L);
        when(similar.getEmotionRecord()).thenReturn(record);
        when(similar.getHelpfulnessScore()).thenReturn((short)0);
        when(similar.confirmedInsight()).thenReturn(Map.of("userConfirmed", true, "afterText", "확인한 생각"));
        when(similar.confirmedInsightCodes()).thenReturn(List.of());
        when(rag.searchSimilarCases(record)).thenReturn(List.of(similar));
    }

    @Test void completedCbtDoesNotStartSearchOrGeneration() {
        when(sessions.existsByEmotionRecord_IdAndStatus(1L, ReflectionSessionStatus.COMPLETED)).thenReturn(true);
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> service.explainPattern(7L, 1L)).getStatusCode().value());
        verify(sessions).existsByEmotionRecord_IdAndStatus(1L, ReflectionSessionStatus.COMPLETED);
        verifyNoMoreInteractions(sessions);
        verifyNoInteractions(rag, ai);
    }

    @Test void completionDuringSearchSkipsExplanationGeneration() {
        prepareSimilarCase();
        when(sessions.existsByEmotionRecord_IdAndStatus(1L, ReflectionSessionStatus.COMPLETED))
                .thenReturn(false, true);
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> service.explainPattern(7L, 1L)).getStatusCode().value());
        verifyNoInteractions(ai);
    }

    @Test void completionDuringGenerationDiscardsTheLateResult() {
        prepareSimilarCase();
        when(sessions.existsByEmotionRecord_IdAndStatus(1L, ReflectionSessionStatus.COMPLETED))
                .thenReturn(false, false, true);
        when(ai.explain(any())).thenReturn(new FastApiPatternExplanationResponseDto(
                "늦은 패턴 응답", List.of(), null, null));
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> service.explainPattern(7L, 1L)).getStatusCode().value());
        verify(ai).explain(any());
    }

    @Test void noSimilarCaseDoesNotCallTheExplanationModel() {
        when(sessions.countByUser_IdAndStatusAndUserConfirmedTrueAndEmotionRecord_IdNot(
                7L, ReflectionSessionStatus.COMPLETED, 1L)).thenReturn(10L);
        when(rag.searchSimilarCases(record)).thenReturn(List.of());
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> service.explainPattern(7L, 1L)).getStatusCode().value());
        verifyNoInteractions(ai);
    }

    @Test void anUnconfirmedRecordDoesNotStartSearch() {
        ReflectionTestUtils.setField(record, "completionStatus", CompletionStatus.PARTIAL);
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> service.explainPattern(7L, 1L)).getStatusCode().value());
        verifyNoInteractions(sessions, rag, ai);
    }
}
