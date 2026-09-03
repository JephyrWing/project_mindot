// 주간 리포트 통계의 근거가 된 완료 CBT 성찰 요약으 프론트에 전달하는 DTO
package com.my.mindot_back.reports.dto;

import com.my.mindot_back.records.entity.ReflectionSessions;

public record WeeklyReportCbtEvidenceDto (
        Long sessionId,

        Long emotionRecordId,

        String alternativeThoughtText,

        Short helpfulnessScore
){
    // ReflectionSessions Entity를 주간 리포트 근거용 DTO로 변환
    public static WeeklyReportCbtEvidenceDto from(
            ReflectionSessions reflectionSession
    ) {
        return new WeeklyReportCbtEvidenceDto(
                reflectionSession.getId(),
                reflectionSession.getEmotionRecord().getId(),
                reflectionSession.getAlternativeThoughtText(),
                reflectionSession.getHelpfulnessScore()
        );
    }
}
