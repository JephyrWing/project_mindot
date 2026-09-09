package com.my.mindot_back.records.service;

import com.my.mindot_back.ai.entity.*;
import com.my.mindot_back.ai.repository.AiJobsRepository;
import com.my.mindot_back.records.dto.InsightDtos;
import com.my.mindot_back.records.entity.*;
import com.my.mindot_back.records.repository.*;
import com.my.mindot_back.distortions.repository.DistortionTypesRepository;
import com.my.mindot_back.safety.service.SafetyEventsService;
import com.my.mindot_back.users.entity.Users;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** In-memory entity/repository fixtures only; no server/DB. Prepared, not run. */
class LegacyReflectionRetryTest {
    ReflectionSessions session;
    AiJobs old;
    InsightTransactions tx;
    Instant answered;

    @BeforeEach void setup() {
        var user=mock(Users.class);when(user.getId()).thenReturn(1L);
        var record=mock(EmotionRecords.class);when(record.getId()).thenReturn(7L);
        when(record.getAutomaticThought()).thenReturn("처음 생각");
        session=ReflectionSessions.create(user,record);
        ReflectionTestUtils.setField(session,"id",8L);
        ReflectionTestUtils.setField(session,"createdAt",Instant.now().minusSeconds(120));
        answered=Instant.now().minusSeconds(30);
        ReflectionTestUtils.setField(session,"currentStep","Q1");
        ReflectionTestUtils.setField(session,"questionAnswers",new ArrayList<>(List.of(InsightMapping.object(
            "questionCode","Q1","question","실제 질문","answer","저장된 답변",
            "askedAt",answered.minusSeconds(10).toString(),"answeredAt",answered.toString()))));
        old=AiJobs.create(user,AiJobEntityType.REFLECTION,8L,AiJobOperation.QUESTION,"old-key");
        ReflectionTestUtils.setField(old,"id",10L);
        ReflectionTestUtils.setField(old,"createdAt",answered.plusMillis(2));
        old.fail("FAST_API_CBT_TURN_FAILED");
        var sessions=mock(ReflectionSessionsRepository.class);
        when(sessions.findLockedById(8L)).thenReturn(Optional.of(session));
        var jobs=mock(AiJobsRepository.class);
        Map<Long,AiJobs> saved=new HashMap<>();saved.put(10L,old);
        when(jobs.findFirstByUser_IdAndEntityTypeAndEntityIdAndOperationOrderByIdDesc(1L,AiJobEntityType.REFLECTION,8L,AiJobOperation.QUESTION))
            .thenReturn(Optional.of(old));
        when(jobs.findById(anyLong())).thenAnswer(a->Optional.ofNullable(saved.get(a.getArgument(0))));
        when(jobs.saveAndFlush(any(AiJobs.class))).thenAnswer(a->{
            AiJobs job=a.getArgument(0);long id=20L+saved.size();
            ReflectionTestUtils.setField(job,"id",id);saved.put(id,job);return job;
        });
        tx=new InsightTransactions(sessions,mock(EmotionRecordsRepository.class),jobs,mock(SessionDistortionsRepository.class),
            mock(DistortionTypesRepository.class),mock(SafetyEventsService.class));
    }

    @Test void failedLegacyAnswerRestoresThenExplicitlyRetriesWithoutAppendingUser() {
        var view=tx.get(1L,8L);assertEquals(2,view.revision());assertEquals(true,view.job().get("retryable"));
        var opened=tx.open(1L,new InsightDtos.Open(null,8L),"open",2L);
        assertFalse(opened.dispatch());assertEquals("RESTORE",opened.restore().get("mode"));
        var retry=tx.retry(1L,8L,"retry",2L);
        assertTrue(retry.dispatch());assertFalse(retry.start());
        assertEquals(2L,retry.request().get("inputRevision"));assertEquals(1L,retry.request().get("baseRevision"));
        assertEquals(InsightMapping.messages(session).get(1),retry.request().get("userMessage"));
        assertEquals(2,((List<?>)retry.restore().get("messages")).size());
        assertEquals(1,session.getQuestionAnswers().size()); // Original row remains byte-equivalent in shape.
        assertEquals("저장된 답변",session.getQuestionAnswers().get(0).get("answer"));
        assertFalse(LegacyReflectionRetry.canComplete(session,old));
    }

    @Test void processingIsNotRetryableUntilDeadline() {
        old.startProcessing();var view=tx.get(1L,8L);
        assertEquals("PROCESSING",view.job().get("status"));assertEquals(false,view.job().get("retryable"));
        assertThrows(ResponseStatusException.class,()->tx.retry(1L,8L,"retry",2L));
        ReflectionTestUtils.setField(old,"createdAt",Instant.now().minusSeconds(220));
        session.getQuestionAnswers().get(0).put("answeredAt",Instant.now().minusSeconds(221).toString());
        assertEquals(true,tx.get(1L,8L).job().get("retryable"));
        assertFalse(LegacyReflectionRetry.canComplete(session,old));
    }

    @Test void successfulProposalAndUnansweredQuestionAreNotFailedInputs() {
        old.complete("legacy","legacy");
        ReflectionTestUtils.setField(session,"currentStep","CONFIRM_REQUIRED");
        assertNull(tx.get(1L,8L).job());
        old.fail("old failure");ReflectionTestUtils.setField(session,"currentStep","Q1");
        session.getQuestionAnswers().get(0).put("answer",null);
        assertNull(tx.get(1L,8L).job());
    }

    @Test void earlierJobCannotClaimLaterAnswerAndClosedSessionCannotRetry() {
        ReflectionTestUtils.setField(old,"createdAt",answered.minusSeconds(10));
        assertNull(tx.get(1L,8L).job());
        session.cancel();assertNull(tx.get(1L,8L).job());
        assertFalse(LegacyReflectionRetry.canComplete(session,old));
    }

    @Test void firstQuestionFailureUsesNewWithEmptyHistory() {
        session.getQuestionAnswers().clear();ReflectionTestUtils.setField(session,"currentStep",null);
        var retry=tx.retry(1L,8L,"first-retry",0L);
        assertTrue(retry.start());assertEquals("NEW",retry.request().get("mode"));
        assertTrue(((List<?>)retry.request().get("messages")).isEmpty());
        assertEquals(0L,retry.request().get("revision"));
    }

    @Test void legacySuccessWithoutNewMessageStillAdvancesRevision() {
        tx.get(1L,8L);var state=session.insight();old.startProcessing();
        assertTrue(LegacyReflectionRetry.canComplete(session,old));
        old.complete("legacy","legacy");ReflectionTestUtils.setField(session,"currentStep","CONFIRM_REQUIRED");
        LegacyReflectionRetry.completed(session,state);
        var view=tx.get(1L,8L);assertEquals(3,view.revision());assertNull(view.job());
    }
}
