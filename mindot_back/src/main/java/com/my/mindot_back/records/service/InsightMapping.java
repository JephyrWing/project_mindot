package com.my.mindot_back.records.service;

import com.my.mindot_back.records.entity.ReflectionSessions;
import java.util.*;

/** Read-only legacy mapping: never rewrites old question_answers or invents a USER utterance. */
public final class InsightMapping {
    private InsightMapping() {}
    @SuppressWarnings("unchecked")
    public static Map<String,Object> map(Object value) {
        return value instanceof Map<?,?> ? new LinkedHashMap<>((Map<String,Object>)value) : new LinkedHashMap<>();
    }
    public static long number(Object value) { return value instanceof Number n ? n.longValue() : 0L; }
    public static Map<String,Object> object(Object... pairs) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);
        return result;
    }
    public static List<Map<String,Object>> messages(ReflectionSessions session) {
        List<Map<String,Object>> result=new ArrayList<>();
        // Existing array order resolves equal timestamps and preserves unmatched sides.
        for(Map<String,Object> row:session.getQuestionAnswers()) {
            if(row.containsKey("role")) {
                result.add(object("messageNumber",result.size()+1,"role",row.get("role"),"content",row.get("content"),"createdAt",row.get("createdAt")));
            } else {
                add(result,"ASSISTANT",row.get("question"),row.get("askedAt"),session);
                add(result,"USER",row.get("answer"),row.get("answeredAt"),session);
            }
        }
        return result;
    }
    private static void add(List<Map<String,Object>> rows,String role,Object text,Object at,ReflectionSessions s) {
        if(text instanceof String value && !value.isBlank())rows.add(object("messageNumber",rows.size()+1,
            "role",role,"content",value,"createdAt",at!=null?at:s.getCreatedAt().toString()));
    }
    public static Map<String,Object> record(ReflectionSessions s) {
        Map<String,Object> saved=map(s.insight().get("record"));
        if(!saved.isEmpty())return saved;
        var e=s.getEmotionRecord();
        return object("recordId",e.getId(),"situation",e.getSituationText(),"automaticThought",e.getAutomaticThought(),
            "primaryEmotionCode",e.getPrimaryEmotionCode(),"primaryIntensity",e.getPrimaryIntensity(),
            "beforeBeliefStrength",s.getBeforeBeliefStrength(),"contextCategory",e.getContextCategory());
    }
}
