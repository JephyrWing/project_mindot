// Spring이 FastAPI의 POST /internal/ai/reflections/turn에 보내는 요청 DTO
package com.my.mindot_back.records.dto.ai;

import com.my.mindot_back.records.entity.EmotionRecords;
import com.my.mindot_back.records.entity.ReflectionSessions;
import com.my.mindot_back.records.entity.SessionDistortions;

import java.util.List;
import java.util.Map;
import java.util.UUID;

// 사용자의 답변을 DB에 먼저 저장한 뒤 FastAPI에 전달하는 DTO
// FastAPI는 다음 질문 위해
// 원본 감정 기록 문맥, 지금까지 질문답변 이력, 이전 인지왜곡 제안 검토 상태를 함께 받음
public record FastApiCbtTurnRequestDto (

        UUID requestId,

        Long sessionId,

        // 방금 답변을 저장한 질문의 questionCode
        String currentStep,

        // 원본 감정 기록 기반 CBT 문맥
        FastApiCbtStartRequestDto.RecordContext record,

        // PostgreSQL question_answers JSONB에 저장된 전체 질문, 답변 이력
        List<Map<String, Object>> questionAnswers,

        // 성찰 전 인지왜곡 제안과 사용자의 검토 상태
        List<ReviewedDistortion> beforeDistortions
){
    // DB의 성찰 세션 정보를 FastAPI 다음 턴 요청 형식으로 변환
    public static FastApiCbtTurnRequestDto from(
            ReflectionSessions reflectionSession,
            List<SessionDistortions> sessionDistortions
    ){
        EmotionRecords emotionRecord = reflectionSession.getEmotionRecord();

        return new FastApiCbtTurnRequestDto(
                // 다음 질문 요청마다 새로운 UUID 생성
                UUID.randomUUID(),
                reflectionSession.getId(),
                reflectionSession.getCurrentStep(),

                // 첫 질문 생성때와 동일한 원본 감정 기록 문맥 전달
                new FastApiCbtStartRequestDto.RecordContext(
                        emotionRecord.getId(),
                        emotionRecord.getSituationText(),
                        emotionRecord.getAutomaticThought(),
                        emotionRecord.getPrimaryEmotionCode(),
                        emotionRecord.getPrimaryIntensity(),
                        reflectionSession.getBeforeBeliefStrength(),
                        emotionRecord.getContextCategory()
                ),

                // DB에 저장된 질문, 답변 JSONB 이력 전달
                reflectionSession.getQuestionAnswers(),

                // DB의 인지왜곡 Entity를 FastAPI 요청용 값으로 변환
                sessionDistortions.stream()
                        .map(ReviewedDistortion::from)
                        .toList()
        );
    }

    // FastAPI의 ReviewedDistortion 형식과 동일한 인지왜곡 검토 정보
    public record ReviewedDistortion(

            String code,
            String reviewStatus,
            // AI가 분류한 신뢰도, 사용자 직접 추가면 null 가능
            Double classifierConfidence
    ){
        // SessionDistortions Entity를 FastAPI 요청 형식으로 변환
        public static ReviewedDistortion from(
                SessionDistortions sessionDistortion
        ){
            return new ReviewedDistortion(
                    sessionDistortion.getDistortionType().getCode(),
                    sessionDistortion.getReviewStatus().name(),
                    sessionDistortion.getClassifierConfidence() == null
                                ? null
                                : sessionDistortion.getClassifierConfidence().doubleValue()
            );
        }
    }
}
