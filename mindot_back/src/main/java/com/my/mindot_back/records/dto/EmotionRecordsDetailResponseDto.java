// 감정 기록 상세 화면에 필요한 데이터를 반환하는 DTO
package com.my.mindot_back.records.dto;

import com.my.mindot_back.records.entity.EmotionRecords;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record EmotionRecordsDetailResponseDto (
        Long emotionRecordId,
        String rawText,
        Instant occurredAt,
        String timeBucket,
        String weekdayType,
        String completionStatus,

        String situationText,
        String automaticThought,

        String primaryEmotionCode,
        Short primaryIntensity,
        List<Map<String, Object>> secondaryEmotions,

        String contextCategory,
        String relatedPersonType,
        Map<String, Object> details
){
    // EmotionRecords Entity를 상세 응답 DTO로 변환
    public static EmotionRecordsDetailResponseDto from(
            EmotionRecords emotionRecord
    ) {
        return new EmotionRecordsDetailResponseDto(
                emotionRecord.getId(),
                emotionRecord.getRawText(),
                emotionRecord.getOccurredAt(),
                emotionRecord.getTimeBucket().name(),
                emotionRecord.getWeekdayType().name(),
                emotionRecord.getCompletionStatus().name(),

                emotionRecord.getSituationText(),
                emotionRecord.getAutomaticThought(),

                emotionRecord.getPrimaryEmotionCode(),
                emotionRecord.getPrimaryIntensity(),
                emotionRecord.getSecondaryEmotions(),

                emotionRecord.getContextCategory(),
                emotionRecord.getRelatedPersonType(),
                emotionRecord.getDetails()
        );
    }
}
