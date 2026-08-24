// 사용자의 CBT 답변 저장 후 다음 질문 생성 결과를 프론트에 전달하는 응답 DTO
package com.my.mindot_back.records.dto;

import com.my.mindot_back.records.dto.ai.FastApiCbtResponseDto;
import com.my.mindot_back.records.entity.ReflectionSessions;

import java.util.List;

// FastAPI 응답에 Spring의 성찰 세션 ID를 함께 담아 프론트에 전달
// 프론트는 sessionId 유지한채 다음 답변도 같은 성찰 세션으로 요청함
public record ReflectionSessionTurnResponseDto (

        Long sessionId,

        String status,

        FastApiCbtResponseDto.GeneratedQuestion nextQuestion,

        List<FastApiCbtResponseDto.DistortionProposal> beforeDistortions,

        FastApiCbtResponseDto.ReflectionOutcomeDraft outcomeDraft ,

        List<String> confirmationRequiredFields,

        String acknowledgementEvidence,

        String acknowledgementSourceQuestionCode,

        String proposalMessage,

        FastApiCbtResponseDto.RiskAssessment risk
){
    // DB 성찰 세션과 FastAPI 응답을 프론트 응답 형식으로 변환
    public static ReflectionSessionTurnResponseDto from(
            ReflectionSessions reflectionSessions,
            FastApiCbtResponseDto fastApiCbtResponse
    ) {
        return new ReflectionSessionTurnResponseDto(
                reflectionSessions.getId(),
                fastApiCbtResponse.status(),
                fastApiCbtResponse.nextQuestion(),
                fastApiCbtResponse.beforeDistortions(),
                fastApiCbtResponse.outcomeDraft(),
                fastApiCbtResponse.confirmationRequiredFields(),
                fastApiCbtResponse.acknowledgementEvidence(),
                fastApiCbtResponse.acknowledgementSourceQuestionCode(),
                fastApiCbtResponse.proposalMessage(),
                fastApiCbtResponse.risk()
        );
    }
}
