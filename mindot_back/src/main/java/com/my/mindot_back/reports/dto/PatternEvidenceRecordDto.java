// 반복 패턴에 실제로 포함된 완료 감정 기록의 상세 정보
package com.my.mindot_back.reports.dto;

import com.my.mindot_back.records.entity.EmotionRecords;

import java.time.Instant;

public record PatternEvidenceRecordDto(
        Long emotionRecordId,
        Instant occurredAt,
        String primaryEmotionCode,
        Short primaryIntensity,
        String situationText,
        String rawText
) {
    public static PatternEvidenceRecordDto from(EmotionRecords record) {
        return new PatternEvidenceRecordDto(
                record.getId(), record.getOccurredAt(), record.getPrimaryEmotionCode(),
                record.getPrimaryIntensity(), record.getSituationText(), record.getRawText()
        );
    }
}
