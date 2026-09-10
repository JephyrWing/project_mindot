package com.my.mindot_back.records.service;

import com.my.mindot_back.records.entity.ReflectionSessions;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InsightMappingTest {
    @Test void legacyAndNewMessagesKeepOriginalRowsAndStableNumbers() {
        var session=mock(ReflectionSessions.class);
        String time="2026-09-09T00:00:00Z";
        List<Map<String,Object>> original=List.of(
            Map.of("question","옛 질문","answer","옛 답변","askedAt",time,"answeredAt",time),
            Map.of("question","미답변 질문","askedAt",time),
            Map.of("role","USER","content","새 답변","messageNumber",4,"createdAt",time));
        when(session.getQuestionAnswers()).thenReturn(original);
        when(session.getCreatedAt()).thenReturn(Instant.parse(time));
        var messages=InsightMapping.messages(session);
        assertEquals(List.of("ASSISTANT","USER","ASSISTANT","USER"),messages.stream().map(m->m.get("role")).toList());
        assertEquals(List.of(1,2,3,4),messages.stream().map(m->m.get("messageNumber")).toList());
        assertEquals(original,session.getQuestionAnswers());
        verify(session,never()).appendInsightMessage(any());
    }
}
