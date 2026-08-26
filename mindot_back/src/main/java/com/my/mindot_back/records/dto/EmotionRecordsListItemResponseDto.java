// 감정 기록 목록 1건을 반환하는 DTO
package com.my.mindot_back.records.dto;

import com.my.mindot_back.records.entity.EmotionRecords;

import java.time.Instant;

public record EmotionRecordsListItemResponseDto (
        Long emotionRecordId,
        String rawText,
        String primaryEmotionCode,
        Short primaryIntensity,
        String contextCategory,
        Instant occurredAt
){

    // EmotionRecords Entity를 목록 응답 DTO로 변환
    public static EmotionRecordsListItemResponseDto from (
            EmotionRecords emotionRecord
    ){
        return new EmotionRecordsListItemResponseDto(
                emotionRecord.getId(),
                emotionRecord.getRawText(),
                emotionRecord.getPrimaryEmotionCode(),
                emotionRecord.getPrimaryIntensity(),
                emotionRecord.getContextCategory(),
                emotionRecord.getOccurredAt()
        );
    }
}
