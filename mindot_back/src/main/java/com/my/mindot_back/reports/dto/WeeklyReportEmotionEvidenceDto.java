// 주간 리포트 통계의 근거가 된 감정 기록 요약을 프론트에 전달하는 DTO
package com.my.mindot_back.reports.dto;

import com.my.mindot_back.records.entity.EmotionRecords;

import java.time.Instant;

public record WeeklyReportEmotionEvidenceDto (
        Long emotionRecordId,

        Instant occurredAt,

        String primaryEmotionCode,

        Short primaryIntensity,

        String situationText,

        String automaticThought
){
    // EmotionRecords Entity를 주간 리포트 근거용 DTO로 변환
    public static WeeklyReportEmotionEvidenceDto from(
            EmotionRecords emotionRecord
    ) {
        return new WeeklyReportEmotionEvidenceDto(
                emotionRecord.getId(),
                emotionRecord.getOccurredAt(),
                emotionRecord.getPrimaryEmotionCode(),
                emotionRecord.getPrimaryIntensity(),
                emotionRecord.getSituationText(),
                emotionRecord.getAutomaticThought()
        );
    }
}
