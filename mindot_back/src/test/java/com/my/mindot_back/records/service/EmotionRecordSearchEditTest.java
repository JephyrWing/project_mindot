package com.my.mindot_back.records.service;

import com.my.mindot_back.ai.entity.*;
import com.my.mindot_back.ai.repository.AiJobsRepository;
import com.my.mindot_back.records.entity.*;
import com.my.mindot_back.records.repository.EmotionRecordsRepository;
import com.my.mindot_back.users.entity.Users;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmotionRecordSearchEditTest {
    @Test void lateEmbeddingForOldRawTextCannotOverwriteTheEditedRecord() {
        var records = mock(EmotionRecordsRepository.class);
        var jobs = mock(AiJobsRepository.class);
        var user = mock(Users.class);
        when(user.getId()).thenReturn(7L);
        var record = EmotionRecords.createQuick(user, "원래 원문", InputType.TEXT, Instant.now());
        ReflectionTestUtils.setField(record, "id", 1L);
        var job = AiJobs.create(user, AiJobEntityType.EMOTION_RECORD, 1L, AiJobOperation.EMBED, "test");
        job.prepareInsight(Map.of(), (short)1, Instant.now().plusSeconds(60));
        job.startProcessing();
        when(records.findLockedById(1L)).thenReturn(Optional.of(record));
        when(jobs.findById(2L)).thenReturn(Optional.of(job));
        when(jobs.findFirstByUser_IdAndEntityTypeAndEntityIdAndOperationOrderByIdDesc(
                7L, AiJobEntityType.EMOTION_RECORD, 1L, AiJobOperation.EMBED)).thenReturn(Optional.of(job));
        var service = new EmotionRecordSearchEmbeddingTransactionService(records, jobs);
        service.invalidate(7L, 1L);
        record.updateRawText("수정 원문");
        service.complete(7L, 1L, 2L, new float[1536]);
        assertEquals(AiJobStatus.FAILED, job.getStatus());
        assertNull(record.getSearchEmbedding());
        assertEquals("수정 원문", record.getRawText());
    }
}
