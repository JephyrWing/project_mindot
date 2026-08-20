// Spring이 FastAPI 감정 원문 구조화 API에 보내는 요청 DTO
package com.my.mindot_back.records.dto.ai;

public record FastApiRecordAnalysisRequestDto(

        // AI가 구조화할 사용자 감정 원문
        String rawText
) {
}
