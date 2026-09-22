// 완료한 CBT 성찰 목록 화면에 필요한 최소 정보를 전달하는 응답 DTO
package com.my.mindot_back.records.dto;

import com.my.mindot_back.records.entity.ReflectionSessions;

import java.time.Instant;

public record CompletedReflectionSessionResponseDto(
        Long sessionId,
        Long emotionRecordId,
        String rawText,
        String alternativeThoughtText,
        Short helpfulnessScore,
        Instant completedAt
) {
    public static CompletedReflectionSessionResponseDto from(ReflectionSessions reflectionSession) {
        return new CompletedReflectionSessionResponseDto(
                reflectionSession.getId(),
                reflectionSession.getEmotionRecord().getId(),
                reflectionSession.getEmotionRecord().getRawText(),
                reflectionSession.getAlternativeThoughtText(),
                reflectionSession.getHelpfulnessScore(),
                reflectionSession.getCompletedAt()
        );
    }
}
