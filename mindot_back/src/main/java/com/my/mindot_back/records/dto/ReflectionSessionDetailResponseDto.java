// CBT 성찰 세션 상세와 질문·답변 이력을 프론트에 전달하는 응답 DTO
package com.my.mindot_back.records.dto;

import com.my.mindot_back.records.entity.ReflectionSessionStatus;
import com.my.mindot_back.records.entity.ReflectionSessions;

import java.util.List;
import java.util.Map;

// 성찰 화면을 다시 열 때 필요한 현재 상태와 question_answers JSONB 의 질문 답변 이력 전달
public record ReflectionSessionDetailResponseDto (
    Long sessionId,

    ReflectionSessionStatus status,

    String currentStep,

    List<Map<String, Object>> questionAnswers
    ){
    // Entity를 프론트 응답 DTO로 변환
    public static ReflectionSessionDetailResponseDto from(
            ReflectionSessions reflectionSessions
    ){
        return new ReflectionSessionDetailResponseDto(
                reflectionSessions.getId(),
                reflectionSessions.getStatus(),
                reflectionSessions.getCurrentStep(),
                reflectionSessions.getQuestionAnswers()
        );
    }
}
