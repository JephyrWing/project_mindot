// 반복 패턴 목록의 API 응답 계약
package com.my.mindot_back.reports.dto;

import com.my.mindot_back.reports.entity.PatternFeedback;
import com.my.mindot_back.reports.entity.PatternLevel;
import com.my.mindot_back.reports.entity.PersonalPattern;

import java.time.LocalDate;

public record PersonalPatternResponseDto(
        Long patternId,
        String emotionCode,
        String weekday,
        String timeBucket,
        PatternLevel patternLevel,
        long occurrenceCount,
        long distinctDateCount,
        long observedWeekCount,
        LocalDate windowStart,
        LocalDate windowEnd,
        PatternFeedback feedback
) {
    public static PersonalPatternResponseDto from(PersonalPattern pattern) {
        return new PersonalPatternResponseDto(
                pattern.getId(), pattern.getEmotionCode(), pattern.getWeekday(),
                pattern.getTimeBucket(), pattern.getPatternLevel(),
                pattern.getOccurrenceCount(), pattern.getDistinctDateCount(),
                pattern.getObservedWeekCount(), pattern.getWindowStart(),
                pattern.getWindowEnd(), pattern.getFeedback()
        );
    }
}
