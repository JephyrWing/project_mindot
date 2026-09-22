// 반복 패턴 상세와 사용자 소유 근거 기록의 API 응답 계약
package com.my.mindot_back.reports.dto;

import com.my.mindot_back.reports.entity.PatternFeedback;
import com.my.mindot_back.reports.entity.PatternLevel;
import com.my.mindot_back.reports.entity.PersonalPattern;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record PersonalPatternDetailResponseDto(
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
        PatternFeedback feedback,
        Instant feedbackAt,
        List<PatternEvidenceRecordDto> evidenceRecords
) {
    public static PersonalPatternDetailResponseDto from(
            PersonalPattern pattern, List<PatternEvidenceRecordDto> evidenceRecords) {
        return new PersonalPatternDetailResponseDto(
                pattern.getId(), pattern.getEmotionCode(), pattern.getWeekday(),
                pattern.getTimeBucket(), pattern.getPatternLevel(),
                pattern.getOccurrenceCount(), pattern.getDistinctDateCount(),
                pattern.getObservedWeekCount(), pattern.getWindowStart(),
                pattern.getWindowEnd(), pattern.getFeedback(), pattern.getFeedbackAt(),
                evidenceRecords
        );
    }
}
