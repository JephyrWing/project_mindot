package com.my.mindot_back.reports.dto;

import com.my.mindot_back.reports.entity.PatternLevel;

// 기간별 반복 감정 패턴 정보
public record RepeatedEmotionPatternDto(
        String emotionCode,
        String weekday,
        String timeBucket,
        long occurrenceCount,
        long distinctDateCount,
        long observedWeekCount,
        PatternLevel patternLevel
) {
}