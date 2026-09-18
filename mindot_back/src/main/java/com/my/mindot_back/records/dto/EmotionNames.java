package com.my.mindot_back.records.dto;

import java.util.Map;

/** Exact labels only. Custom names retain their spelling and case. */
public final class EmotionNames {
    private EmotionNames() {}
    private static final Map<String, String> CODES = Map.ofEntries(
        Map.entry("불안", "ANXIETY"), Map.entry("두려움", "FEAR"),
        Map.entry("분노", "ANGER"), Map.entry("답답함", "FRUSTRATION"),
        Map.entry("슬픔", "SADNESS"), Map.entry("실망", "DISAPPOINTMENT"),
        Map.entry("수치심", "SHAME"), Map.entry("죄책감", "GUILT"),
        Map.entry("외로움", "LONELINESS"), Map.entry("기쁨", "JOY"),
        Map.entry("안도", "RELIEF"), Map.entry("성취감", "ACHIEVEMENT"),
        Map.entry("평온", "CALM"), Map.entry("감사", "GRATITUDE"),
        Map.entry("설렘", "EXCITEMENT"), Map.entry("기타", "OTHER")
    );
    public static String normalize(String value) {
        if (value == null || value.isBlank()) return null;
        String text = value.strip();
        return CODES.getOrDefault(text, text);
    }
}
