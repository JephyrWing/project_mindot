// CBT 성찰 세션 상세와 질문·답변 이력을 프론트에 전달하는 응답 DTO
package com.my.mindot_back.records.dto;

import com.my.mindot_back.records.entity.ReflectionSessionStatus;
import com.my.mindot_back.records.entity.ReflectionSessions;

import java.util.List;
import java.util.Map;

// 진행 중 세션의 질문·답변 이력과 완료 세션의 최종 결과를 함께 전달
public record ReflectionSessionDetailResponseDto (
    Long sessionId,

    ReflectionSessionStatus status,

    String currentStep,

    // COMPLETED, 사용자가 확정한 경우에만 제공하는 최종 결과
    ReflectionSessionOutcomeResponseDto outcome,

    List<Map<String, Object>> questionAnswers
    ){
    // Entity를 프론트 응답 DTO로 변환
    public static ReflectionSessionDetailResponseDto from(
            ReflectionSessions reflectionSession
    ){
        boolean hasConfirmedOutcome =
                reflectionSession.getStatus() == ReflectionSessionStatus.COMPLETED
                        && Boolean.TRUE.equals(reflectionSession.getUserConfirmed());

        return new ReflectionSessionDetailResponseDto(
                reflectionSession.getId(),
                reflectionSession.getStatus(),
                reflectionSession.getCurrentStep(),
                hasConfirmedOutcome
                        ? ReflectionSessionOutcomeResponseDto.from(reflectionSession)
                        : null,
                reflectionSession.getQuestionAnswers()
        );
    }
}
