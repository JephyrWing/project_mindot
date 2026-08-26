// FastAPI가 유사 CBT 사례를 분석해 반환하는 패턴 설명 DTO
package com.my.mindot_back.records.dto.ai;

import java.util.List;

public record FastApiPatternExplanationResponseDto(
        String patternSummary,
        List<String> repeatedDistortionCodes,
        // 과거 CBT에서 도움이 됐던 대안적 사고
        String helpfulAlternativeThought,
        String recommendation
) {
}