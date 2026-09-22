// 로그인 사용자의 확정 감정 기록 분포를 조회하는 API
package com.my.mindot_back.insights.controller;

import com.my.mindot_back.insights.dto.EmotionInsightsResponseDto;
import com.my.mindot_back.insights.service.EmotionInsightsService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/insights")
@RequiredArgsConstructor
public class EmotionInsightsController {
    private final EmotionInsightsService emotionInsightsService;

    @GetMapping("/emotions")
    public EmotionInsightsResponseDto getEmotionInsights(
            @AuthenticationPrincipal Long userId,
            @RequestParam String groupBy
    ) {
        return emotionInsightsService.getEmotionInsights(userId, groupBy);
    }
}
