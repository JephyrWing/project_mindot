// 오늘 기준 최근 8주의 반복 감정 패턴 조회 API
package com.my.mindot_back.reports.controller;

import com.my.mindot_back.reports.dto.RepeatedEmotionPatternDto;
import com.my.mindot_back.reports.service.RepeatedEmotionPatternService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/patterns")
@RequiredArgsConstructor
public class RepeatedEmotionPatternsController {

    private final RepeatedEmotionPatternService repeatedEmotionPatternService;

     // 리포트 선택 날짜와 관계없이 사용자 현지 날짜의 오늘을 기준으로 최근 8주 반복 패턴을 조회
     // 추후 알림, 할 일 추천, 콘텐츠 추천에서 같은 결과를 사용
    @GetMapping("/recent")
    public List<RepeatedEmotionPatternDto> getRecentPatterns(
            @AuthenticationPrincipal Long userId
    ) {
        return repeatedEmotionPatternService
                .analyzeRecentEightWeeksUpToToday(userId);
    }
}