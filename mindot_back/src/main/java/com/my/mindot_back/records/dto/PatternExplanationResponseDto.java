// 유사 CBT 사례 기반 패턴 설명 API의 React 응답 DTO
package com.my.mindot_back.records.dto;

import com.my.mindot_back.records.dto.ai.FastApiPatternExplanationResponseDto;

import java.util.List;

public record PatternExplanationResponseDto(
        Long emotionRecordId,
        String patternSummary,
        List<String> repeatedDistortionCodes,
        String helpfulAlternativeThought,
        String recommendation,
        int similarCaseCount
) {
    // FastAPI 응답과 실제로 사용된 유사 사례 수를 React 응답으로 변환
    public static PatternExplanationResponseDto from(
            Long emotionRecordId,
            FastApiPatternExplanationResponseDto response,
            int similarCaseCount
    ) {
        return new PatternExplanationResponseDto(
                emotionRecordId,
                response.patternSummary(),
                response.repeatedDistortionCodes(),
                response.helpfulAlternativeThought(),
                response.recommendation(),
                similarCaseCount
        );
    }
}