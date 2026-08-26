// 진행 중인 CBT 성찰 세션 목록에서 1 건을 표시할 때 사용하는 응답 DTO
package com.my.mindot_back.records.dto;

import com.my.mindot_back.records.entity.ReflectionSessions;

import java.time.Instant;

/*
 * 목록 화면에는 전체 question_answers를 보내지 않음
 * 선택한 뒤 상세 조회 API에서 질문·답변 이력을 가져옴
 */
public record OpenReflectionSessionResponseDto (
        Long sessionId,

        Long emotionRecordId,

        // 원문
        String rawText,

        // 마지막으로 진행하던 질문 단계
        String currentStep,

        // 성찰 세션을 시작한 시각
        Instant createdAt
) {
    // ReflectionSessions Entity를 목록용 DTO로 변환
    public static OpenReflectionSessionResponseDto from(
            ReflectionSessions reflectionSession
    ) {
        return new OpenReflectionSessionResponseDto(
                reflectionSession.getId(),
                reflectionSession.getEmotionRecord().getId(),
                reflectionSession.getEmotionRecord().getRawText(),
                reflectionSession.getCurrentStep(),
                reflectionSession.getCreatedAt()
        );
    }
}
