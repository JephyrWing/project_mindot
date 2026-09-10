package com.my.mindot_back.records.service;

import com.my.mindot_back.ai.entity.*;
import com.my.mindot_back.records.entity.*;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import static com.my.mindot_back.records.service.InsightMapping.*;

/** Read compatibility for one old QUESTION job; never converts historical rows. */
public final class LegacyReflectionRetry {
    private LegacyReflectionRetry() {}

    public static boolean legacyOpen(ReflectionSessions s) {
        return s.getStatus()==ReflectionSessionStatus.OPEN
            && number(s.insight().get("lastJobId"))==0
            && !"cbt-insight-1".equals(s.insight().get("resultFormatVersion"))
            && s.getQuestionAnswers().stream().noneMatch(row->row.containsKey("role"));
    }

    public static boolean matchesSavedInput(ReflectionSessions s,AiJobs job) {
        if(!legacyOpen(s) || job==null || job.getOperation()!=AiJobOperation.QUESTION
            || job.getStatus()==AiJobStatus.COMPLETED || "CONFIRM_REQUIRED".equals(s.getCurrentStep()))return false;
        var rows=s.getQuestionAnswers();
        if(rows.isEmpty())return s.getCurrentStep()==null; // Failed/pending first question, no invented USER.
        var row=rows.get(rows.size()-1);
        if(s.getCurrentStep()==null || !Objects.equals(s.getCurrentStep(),row.get("questionCode"))
            || !(row.get("answer") instanceof String answer) || answer.isBlank()
            || job.getCreatedAt()==null)return false;
        try {
            // An unrelated earlier failure must not claim a later saved answer.
            var answered=Instant.parse(String.valueOf(row.get("answeredAt")));
            return job.getCreatedAt().toEpochMilli()>=answered.toEpochMilli();
        } catch(java.time.format.DateTimeParseException error) {return false;}
    }

    public static Map<String,Object> input(ReflectionSessions s,AiJobs job) {
        long revision=number(s.insight().get("revision"));
        String requestId="legacy-question-"+job.getId();
        var history=messages(s);
        if(history.isEmpty())return object("kind","NEW","requestId",requestId,"inputRevision",revision);
        return object("kind","TURN","requestId",requestId,"baseRevision",revision-1,"inputRevision",revision,
            "userMessage",history.get(history.size()-1));
    }

    public static Instant deadline(AiJobs job) {
        if(job==null)return null;
        if(job.getAttemptDeadline()!=null)return job.getAttemptDeadline();
        return job.getOperation()==AiJobOperation.QUESTION && job.getCreatedAt()!=null
            ?job.getCreatedAt().plusSeconds(210):null;
    }

    /** A callback from the old process may finish only before expiry/adoption/cancel. */
    public static boolean canComplete(ReflectionSessions s,AiJobs job) {
        Instant deadline=deadline(job);
        return s.getStatus()==ReflectionSessionStatus.OPEN && number(s.insight().get("lastJobId"))==0
            && (job.getStatus()==AiJobStatus.PENDING || job.getStatus()==AiJobStatus.PROCESSING)
            && deadline!=null && deadline.isAfter(Instant.now());
    }

    public static void completed(ReflectionSessions s,Map<String,Object> previousState) {
        var state=new java.util.LinkedHashMap<>(previousState);
        // Successful legacy proposals can append no ASSISTANT row. Still advance
        // the view so an earlier PROCESSING poll cannot keep the screen locked.
        state.put("revision",Math.max(number(previousState.get("revision"))+1,messages(s).size()));
        s.replaceInsight(state);
    }
}
