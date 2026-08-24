// FastAPI CBT 시작, 다음 턴 API의 공통 응답 JSON을 받는 DTO
/*
 * /internal/ai/reflections/start
 * /internal/ai/reflections/turn
 */
package com.my.mindot_back.records.dto.ai;

import java.util.List;
import java.util.UUID;

public record FastApiCbtResponseDto(

        // Spring이 보낸 요청 ID가 그대로 돌아옴
        UUID requestId,

        /*
         * CONTINUE: 다음 질문 표시
         * CONFIRM_REQUIRED: 성찰 결과를 사용자에게 확인 요청
         * SAFETY_STOP: 위험 신호로 CBT 진행 중단
         */
        String status,

        // status가 CONTINUE일 때 생성되는 다음 질문
        GeneratedQuestion nextQuestion,

        // AI가 제안한 성찰 전 인지왜곡 목록
        List<DistortionProposal> beforeDistortions,

        // status가 CONFIRM_REQUIRED일 때의 성찰 결과 초안
        ReflectionOutcomeDraft outcomeDraft,

        // 사용자가 확인, 수정해야 하는 결과 필드 목록
        List<String> confirmationRequiredFields,

        // AI가 결과 제안을 만든 근거 요약
        String acknowledgementEvidence,

        // 근거가 나온 질문 코드
        String acknowledgementSourceQuestionCode,

        // 프론트에 보여 줄 결과 제안 안내 문구
        String proposalMessage,

        // 안전 위험도와 사유
        RiskAssessment risk,

        // 사용한 AI 모델·프롬프트 버전
        AnalysisMeta meta
) {
    // FastAPI가 생성한 질문 한 개
    public record GeneratedQuestion(

            // 질문을 구분하는 코드
            String questionCode,

            // 질문의 목적
            String questionPurpose,

            // 실제 사용자에게 보여 줄 질문 문장
            String question
    ) {
    }

    // AI가 제안한 인지왜곡과 신뢰도
    public record DistortionProposal(

            // distortion_types의 code
            String code,

            // AI 분류 신뢰도: 0.0 ~ 1.0
            Double classifierConfidence
    ) {
    }

    // 성찰 완료 전 AI가 제안하는 결과 초안
    public record ReflectionOutcomeDraft(

            // 자동사고를 뒷받침하는 근거
            String evidenceForText,

            // 자동사고와 다른 근거
            String evidenceAgainstText,

            // 대안적 사고
            String alternativeThoughtText,

            // 성찰 후 인지왜곡 제안 목록
            List<DistortionProposal> afterDistortions
    ) {
    }

    // 안전 신호 판단 결과
    public record RiskAssessment(

            // NONE, REVIEW, CRISIS
            String level,

            // 위험 사유 코드
            String reasonCode
    ) {
    }

    // AI 처리 이력
    public record AnalysisMeta(

            // 예: gpt-4o-mini
            String model,

            // 예: cbt-turn-v1
            String promptVersion
    ) {
    }
}
