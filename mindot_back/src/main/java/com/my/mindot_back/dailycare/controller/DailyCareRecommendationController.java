// 오늘의 마음 돌봄 추천 조회와 추천 피드백 저장 API
package com.my.mindot_back.dailycare.controller;

import com.my.mindot_back.dailycare.dto.DailyCareFeedbackRequestDto;
import com.my.mindot_back.dailycare.dto.DailyCareRecommendationResponseDto;
import com.my.mindot_back.dailycare.service.DailyCareRecommendationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/daily-care")
@RequiredArgsConstructor
public class DailyCareRecommendationController {
    private final DailyCareRecommendationService recommendationService;

    @GetMapping("/recommendation")
    public DailyCareRecommendationResponseDto getRecommendation(
            @AuthenticationPrincipal Long userId) {
        return recommendationService.getToday(
                userId
        );
    }

    @PostMapping("/recommendations/{recommendationId}/feedback")
    public DailyCareRecommendationResponseDto saveFeedback(@AuthenticationPrincipal Long userId,
            @PathVariable Long recommendationId,
            @Valid @RequestBody DailyCareFeedbackRequestDto request
    ) {
        return recommendationService.saveFeedback(
                userId,
                recommendationId,
                request.feedback());
    }
}
