// 성찰 세션 시작 API가 프론트에 반환하는 응답 DTO

// FastAPI 응답에 Spring이 생성한 sessionId를 추가해서 반환
// 프론트는 이 sessionId로 이후 답변 전송 API 호출
package com.my.mindot_back.records.dto;

import com.my.mindot_back.records.dto.ai.FastApiCbtResponseDto;
import com.my.mindot_back.records.entity.ReflectionSessions;
import com.my.mindot_back.safety.dto.SafetyNoticeResponseDto;

import java.util.List;

public record ReflectionSessionStartResponseDto (

        Long sessionId,

        String status,

        // FastAPI가 판단한 인지왜곡 존재 여부
        String assessmentType,

        // status가 CONTINUE일 때 보여 줄 첫 질문
        FastApiCbtResponseDto.GeneratedQuestion nextQuestion,

        // AI가 제안한 성찰 전 인지왜곡 목록
        List<FastApiCbtResponseDto.DistortionProposal> beforeDistortions,

        // 성찰 결과 초안 : 시작 단계에서는 null
        FastApiCbtResponseDto.ReflectionOutcomeDraft outcomeDraft,

        // 사용자의 확인, 수정이 필요한 결과 필드
        List<String> confirmationRequiredFields,

        String acknowledgementEvidence,

        String acknowledgementSourceQuestionCode,

        // 프론트에 표시할 결과 제안 안내 문구
        String proposalMessage,

        // 안전 위험도와 사유
        FastApiCbtResponseDto.RiskAssessment risk,

        // 위험 신호가 감지된 경우 프론트가 표시할 안전 안내 정보
        SafetyNoticeResponseDto safetyNotice
){
    // DB 성찰 세션과 FastAPI 응답을 프론트용 응답 DTO로 합침
    public static ReflectionSessionStartResponseDto from(
            ReflectionSessions reflectionSession,
            FastApiCbtResponseDto fastApiResponse,
            SafetyNoticeResponseDto safetyNotice
    ) {
        return new ReflectionSessionStartResponseDto(
                // Spring이 DB에 생성한 성찰 세션 ID
                reflectionSession.getId(),

                fastApiResponse.status(),
                fastApiResponse.assessmentType(),
                fastApiResponse.nextQuestion(),
                fastApiResponse.beforeDistortions(),
                fastApiResponse.outcomeDraft(),
                fastApiResponse.confirmationRequiredFields(),
                fastApiResponse.acknowledgementEvidence(),
                fastApiResponse.acknowledgementSourceQuestionCode(),
                fastApiResponse.proposalMessage(),
                fastApiResponse.risk(),
                safetyNotice
        );
    }
}
