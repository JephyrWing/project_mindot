package com.my.mindot_back.records.service;

import com.my.mindot_back.ai.entity.*;
import com.my.mindot_back.ai.repository.AiJobsRepository;
import com.my.mindot_back.records.entity.*;
import com.my.mindot_back.records.repository.EmotionRecordsRepository;
import com.my.mindot_back.safety.service.SafetyEventsService;
import com.my.mindot_back.users.entity.Users;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmotionRecordAnalysisStatusTest {
    @Test void detailTracksTheExistingJobWithoutDispatchingAnotherAnalysis() {
        var records = mock(EmotionRecordsRepository.class);
        var jobs = mock(AiJobsRepository.class);
        var safety = mock(SafetyEventsService.class);
        var user = mock(Users.class);
        when(user.getId()).thenReturn(7L);
        var record = EmotionRecords.createQuick(user, "원문", InputType.TEXT, Instant.now());
        ReflectionTestUtils.setField(record, "id", 1L);
        var job = AiJobs.create(user, AiJobEntityType.EMOTION_RECORD, 1L, AiJobOperation.STRUCTURE, "same-key");
        job.prepareInsight(Map.of(), (short)1, Instant.now().plusSeconds(210));
        when(records.findLockedById(1L)).thenReturn(Optional.of(record));
        when(jobs.findFirstByUser_IdAndEntityTypeAndEntityIdAndOperationOrderByIdDesc(
                7L, AiJobEntityType.EMOTION_RECORD, 1L, AiJobOperation.STRUCTURE)).thenReturn(Optional.of(job));
        var sessions = mock(com.my.mindot_back.records.repository.ReflectionSessionsRepository.class);
        var service = new EmotionRecordAiTransactionService(records, null, jobs, safety, sessions, null);
        assertEquals("PENDING", service.detailResponse(7L, 1L).analysisStatus());
        job.startProcessing();
        assertEquals("PROCESSING", service.detailResponse(7L, 1L).analysisStatus());
        job.fail("TEST_FAILED");
        assertEquals("FAILED", service.detailResponse(7L, 1L).analysisStatus());
        assertEquals("원문", service.detailResponse(7L, 1L).rawText());
        ReflectionTestUtils.setField(record, "completionStatus", CompletionStatus.PARTIAL);
        assertEquals("COMPLETED", service.detailResponse(7L, 1L).analysisStatus());
        assertFalse(service.detailResponse(7L, 1L).cbtStarted());
        assertFalse(service.detailResponse(7L, 1L).cbtCompleted());
        when(sessions.existsByEmotionRecord_Id(1L)).thenReturn(true);
        assertTrue(service.detailResponse(7L, 1L).cbtStarted());
        assertFalse(service.detailResponse(7L, 1L).cbtCompleted());
        when(sessions.existsByEmotionRecord_IdAndStatus(1L, ReflectionSessionStatus.COMPLETED)).thenReturn(true);
        assertTrue(service.detailResponse(7L, 1L).cbtStarted());
        assertTrue(service.detailResponse(7L, 1L).cbtCompleted());
        verify(jobs, never()).saveAndFlush(any());
    }
    @Test void abandonedAttemptExpiresInsteadOfPollingForever() {
        var records = mock(EmotionRecordsRepository.class);
        var jobs = mock(AiJobsRepository.class);
        var user = mock(Users.class);
        when(user.getId()).thenReturn(7L);
        var record = EmotionRecords.createQuick(user, "원문", InputType.TEXT, Instant.now());
        ReflectionTestUtils.setField(record, "id", 1L);
        var job = AiJobs.create(user, AiJobEntityType.EMOTION_RECORD, 1L, AiJobOperation.STRUCTURE, "same-key");
        job.prepareInsight(Map.of(), (short)1, Instant.now().minusSeconds(1));
        job.startProcessing();
        when(records.findLockedById(1L)).thenReturn(Optional.of(record));
        when(jobs.findFirstByUser_IdAndEntityTypeAndEntityIdAndOperationOrderByIdDesc(
                7L, AiJobEntityType.EMOTION_RECORD, 1L, AiJobOperation.STRUCTURE)).thenReturn(Optional.of(job));
        var service = new EmotionRecordAiTransactionService(records, null, jobs, mock(SafetyEventsService.class),
                mock(com.my.mindot_back.records.repository.ReflectionSessionsRepository.class), null);
        assertEquals("FAILED", service.detailResponse(7L, 1L).analysisStatus());
        assertEquals("ATTEMPT_EXPIRED", job.getErrorCode());
    }
}
