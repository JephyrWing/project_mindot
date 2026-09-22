package com.my.mindot_back.reports.service;

import com.my.mindot_back.records.entity.EmotionRecords;
import com.my.mindot_back.reports.dto.EmotionCountDto;
import com.my.mindot_back.reports.dto.MonthlyEmotionCompositionDto;
import java.awt.Color;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;

/** Shared monthly/export aggregation. Never infer the meaning of custom emotion names. */
public final class ReportEmotionData {
    private ReportEmotionData() {}
    public static final int VERSION = 2;
    public static final List<String> EMOTION_ORDER = List.of("ANXIETY", "FEAR", "ANGER", "FRUSTRATION", "SADNESS",
            "DISAPPOINTMENT", "SHAME", "GUILT", "LONELINESS", "짜증",
            "JOY", "RELIEF", "ACHIEVEMENT", "CALM", "GRATITUDE", "EXCITEMENT", "OTHER");
    private static int rank(String value) {
        if (value == null) return EMOTION_ORDER.size() + 1;
        int index = EMOTION_ORDER.indexOf(value);
        return index < 0 ? EMOTION_ORDER.size() : index;
    }
    public static final Map<String, String> COLORS = Map.ofEntries(
            Map.entry("ANXIETY", "#ff7a90"), Map.entry("FEAR", "#e96c93"),
            Map.entry("ANGER", "#f95565"), Map.entry("FRUSTRATION", "#ff9a7a"),
            Map.entry("SADNESS", "#ed8fb2"), Map.entry("DISAPPOINTMENT", "#ffb3a3"),
            Map.entry("SHAME", "#d982af"), Map.entry("GUILT", "#f68c80"),
            Map.entry("LONELINESS", "#f7accb"), Map.entry("짜증", "#ff805f"),
            Map.entry("JOY", "#45a9f8"), Map.entry("RELIEF", "#81c9f4"),
            Map.entry("ACHIEVEMENT", "#538bee"), Map.entry("CALM", "#9cbfeb"),
            Map.entry("GRATITUDE", "#59b7cd"), Map.entry("EXCITEMENT", "#8a9bf5"), Map.entry("OTHER", "#a4b1c2"));
    public static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("ANXIETY", "불안"), Map.entry("FEAR", "두려움"), Map.entry("ANGER", "분노"),
            Map.entry("FRUSTRATION", "답답함"), Map.entry("SADNESS", "슬픔"), Map.entry("DISAPPOINTMENT", "실망"),
            Map.entry("SHAME", "수치심"), Map.entry("GUILT", "죄책감"), Map.entry("LONELINESS", "외로움"),
            Map.entry("JOY", "기쁨"), Map.entry("RELIEF", "안도"), Map.entry("ACHIEVEMENT", "성취감"),
            Map.entry("CALM", "평온"), Map.entry("GRATITUDE", "감사"), Map.entry("EXCITEMENT", "설렘"), Map.entry("OTHER", "기타"));
    public static final Map<String, String> CONTEXTS = Map.ofEntries(
            Map.entry("SOCIAL_EVALUATION", "사회적 평가"), Map.entry("PERFORMANCE", "발표·시험"),
            Map.entry("PROMISE", "약속"), Map.entry("MISTAKE", "실수"), Map.entry("CONFLICT", "갈등"),
            Map.entry("REJECTION", "거절·소외"), Map.entry("WORK", "업무"), Map.entry("STUDY", "학업"),
            Map.entry("HEALTH", "건강"), Map.entry("DAILY_LIFE", "일상"), Map.entry("OTHER", "기타"));
    public static String value(String value) { return value == null || value.isBlank() ? null : value; }
    public static String label(String value) { return value(value) == null ? "감정 미입력" : LABELS.getOrDefault(value, value); }
    public static String contextLabel(String value) { return value(value) == null ? "상황 미분류" : CONTEXTS.getOrDefault(value, value); }
    public static Color color(String value) { return Color.decode(value == null ? COLORS.get("OTHER") : COLORS.getOrDefault(value, COLORS.get("OTHER"))); }
    public static double radius(Short intensity) { return Math.sqrt(25 + 12 * (intensity == null ? 0 : intensity)); }
    public static int bucket(int hour) { return com.my.mindot_back.records.entity.TimeBucket.fromHour(hour).ordinal(); }
    public static List<EmotionCountDto> counts(List<EmotionRecords> records) {
        // Negative, positive, neutral groups; custom names retain Unicode order.
        Map<String, Long> counts = new TreeMap<>(Comparator.comparingInt(ReportEmotionData::rank)
                .thenComparing(Comparator.nullsLast(Comparator.naturalOrder())));
        for (var r : records) counts.merge(value(r.getPrimaryEmotionCode()), 1L, Long::sum);
        return counts.entrySet().stream().map(e -> new EmotionCountDto(e.getKey(), e.getValue())).toList();
    }
    public static MonthlyEmotionCompositionDto monthly(List<EmotionRecords> records, YearMonth month, ZoneId zone) {
        List<MonthlyEmotionCompositionDto.Group> days = new ArrayList<>();
        for (int d = 1; d <= month.lengthOfMonth(); d++) {
            var date = month.atDay(d);
            var selected = records.stream().filter(r -> r.getOccurredAt().atZone(zone).toLocalDate().equals(date)).toList();
            days.add(new MonthlyEmotionCompositionDto.Group(date.toString(), selected.size(), counts(selected)));
        }
        Map<String, List<EmotionRecords>> grouped = new TreeMap<>(Comparator.nullsLast(Comparator.naturalOrder()));
        for (var r : records) grouped.computeIfAbsent(value(r.getContextCategory()), k -> new ArrayList<>()).add(r);
        var contexts = grouped.entrySet().stream().map(e -> new MonthlyEmotionCompositionDto.Group(e.getKey(), e.getValue().size(), counts(e.getValue()))).toList();
        int split = month.lengthOfMonth() / 2;
        List<MonthlyEmotionCompositionDto.Period> halves = new ArrayList<>();
        for (int half = 0; half < 2; half++) {
            var start = month.atDay(half == 0 ? 1 : split + 1);
            var end = month.atDay(half == 0 ? split : month.lengthOfMonth());
            var selected = records.stream().filter(r -> {
                var date = r.getOccurredAt().atZone(zone).toLocalDate();
                return !date.isBefore(start) && !date.isAfter(end);
            }).toList();
            halves.add(new MonthlyEmotionCompositionDto.Period(start.toString(), end.toString(), selected.size(), counts(selected)));
        }
        return new MonthlyEmotionCompositionDto(VERSION, zone.getId(), records.size(), counts(records), days, contexts, halves);
    }

    // JSONB is a Map after persistence and a DTO immediately after generation.
    public static MonthlyEmotionCompositionDto restore(Object value) {
        if (value instanceof MonthlyEmotionCompositionDto dto) return dto;
        if (!(value instanceof Map<?, ?> m)) return null;
        try {
            return new MonthlyEmotionCompositionDto(((Number)m.get("version")).intValue(), (String)m.get("timezone"),
                    number(m.get("recordCount")), readCounts(m.get("emotions")), readGroups(m.get("days")), readGroups(m.get("contexts")),
                    ((List<?>)m.get("halves")).stream().map(o -> {
                        var p = (Map<?, ?>)o;
                        return new MonthlyEmotionCompositionDto.Period((String)p.get("periodStart"), (String)p.get("periodEnd"), number(p.get("recordCount")), readCounts(p.get("emotions")));
                    }).toList());
        } catch (RuntimeException invalidCache) { return null; }
    }
    private static long number(Object value) { return ((Number)value).longValue(); }
    private static List<EmotionCountDto> readCounts(Object value) {
        return ((List<?>)value).stream().map(o -> {
            var m = (Map<?, ?>)o;
            return new EmotionCountDto((String)m.get("emotion"), number(m.get("count")));
        }).toList();
    }
    private static List<MonthlyEmotionCompositionDto.Group> readGroups(Object value) {
        return ((List<?>)value).stream().map(o -> {
            var m = (Map<?, ?>)o;
            return new MonthlyEmotionCompositionDto.Group((String)m.get("value"), number(m.get("recordCount")), readCounts(m.get("emotions")));
        }).toList();
    }
}
