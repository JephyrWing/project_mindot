// FastAPI에 패턴 설명 생성을 요청할 때 전달하는 DTO
package com.my.mindot_back.records.dto.ai;

import com.my.mindot_back.records.dto.PatternSimilarCaseDto;

import java.util.List;

public record FastApiPatternExplanationRequestDto(
        Long emotionRecordId,
        String situationText,
        String automaticThought,
        String primaryEmotionCode,
        List<PatternSimilarCaseDto> similarCases
) {
}