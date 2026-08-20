// FastAPI 감정 원문 구조화 API의 응답 JSON을 받는 DTO
package com.my.mindot_back.records.dto.ai;

import java.util.List;

/*
 * FastAPI POST /internal/ai/records 응답 구조
 *
 * {
 *   "record": { ... },
 *   "risk": { ... },
 *   "meta": { ... }
 * }
 */
public record FastApiRecordAnalysisResponseDto (

    // AI가 원문에서 추출한 구조화 감정 기록
    StructuredRecord record,

    // 안전신호 판단 결과
    RiskAssessment risk,

    // 처리 이력 (AI모델명, 프롬프트 버전)
    AnalysisMeta meta

) {
    // FastAPI 응답의 record 객체
    // AI가 원문에 근거해 추출한 값
    public  record StructuredRecord (
            String situation,

            String interpretation,

            String automaticThought,

            // 주, 보조 감정 목록
            List<EmotionItem> emotions,

            String bodyReaction,

            String behavior,

            // 상황 범주(WORK, FAMILY 등)
            String contextCategory,

            // 관계 유형(FRIEND, COLLEAGUE 등)
            String relatedPersonType

    ){
    }
    // 감정 코드와 강도
    public record EmotionItem(

            // 예: ANXIETY, SADNESS
            String code,

            // 원문에 강도가 없으면 null
            Integer intensity
    ) {
    }

    // 감정 코드, 강도 담는 객체
    public record RiskAssessment (
            String level,

            // 위험 판단 사유 코드
            String reason
    ){
    }

    // AI 처리 추적 정보
    public record AnalysisMeta(

            // 예: gpt-4o-mini
            String model,

            // 예: analyze-record-v1
            String promptVersion
    ) {
    }
}
