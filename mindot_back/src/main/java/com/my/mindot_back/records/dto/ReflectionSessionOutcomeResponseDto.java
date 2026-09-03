// 완료된 CBT 성찰 세션의 최종 결과를 프론트에 전달하는 DTO
package com.my.mindot_back.records.dto;

import com.my.mindot_back.records.entity.ReflectionSessions;

public record ReflectionSessionOutcomeResponseDto (

        String evidenceForText,

        String evidenceAgainstText,

        String alternativeThoughtText,

        Short beforeBeliefStrength,

        Short afterBeliefStrength,

        Short finalEmotionIntensity,

        Short helpfulnessScore
){
    // 완료된 ReflectionSessions Entity를 최종 결과 DTO로 변환
    public static ReflectionSessionOutcomeResponseDto from(
            ReflectionSessions reflectionSession
    ) {
        return new ReflectionSessionOutcomeResponseDto(
                reflectionSession.getEvidenceForText(),
                reflectionSession.getEvidenceAgainstText(),
                reflectionSession.getAlternativeThoughtText(),
                reflectionSession.getBeforeBeliefStrength(),
                reflectionSession.getAfterBeliefStrength(),
                reflectionSession.getFinalEmotionIntensity(),
                reflectionSession.getHelpfulnessScore()
        );
    }
}
