// 패턴 설명 AI에 전달할 유사 완료 CBT 사례 DTO
package com.my.mindot_back.records.dto;

import java.util.List;

public record PatternSimilarCaseDto(
        Long reflectionSessionId,
        String situationText,
        String automaticThought,
        String alternativeThoughtText,
        Short helpfulnessScore,
        // 사용자가 맞다고 확정한 인지왜곡 코드
        List<String> confirmedDistortionCodes
) {
}