// 마음 돌봄 추천과 저장된 피드백을 화면에 전달하는 응답 DTO
package com.my.mindot_back.dailycare.dto;

import com.my.mindot_back.dailycare.entity.DailyCareRecommendation;

import java.time.Instant;

public record DailyCareRecommendationResponseDto(
        Long recommendationId,
        String title,
        String description,
        String activity,
        String source,
        Long emotionRecordId,
        Long reflectionSessionId,
        Instant createdAt,
        String feedback
) {
    public static DailyCareRecommendationResponseDto from(
            DailyCareRecommendation recommendation
    ) {
        return new DailyCareRecommendationResponseDto(
                recommendation.getId(),
                recommendation.getTitle(),
                recommendation.getDescription(),
                recommendation.getActivity(),
                recommendation.getSource(),
                recommendation.getEmotionRecordId(),
                recommendation.getReflectionSessionId(),
                recommendation.getCreatedAt(),
                recommendation.getFeedback()
        );
    }
}
