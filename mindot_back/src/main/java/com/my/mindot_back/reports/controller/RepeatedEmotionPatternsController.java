// 오늘 기준 최근 8주의 반복 감정 패턴 조회 API
package com.my.mindot_back.reports.controller;

import com.my.mindot_back.reports.dto.RepeatedEmotionPatternDto;
import com.my.mindot_back.reports.dto.PersonalPatternResponseDto;
import com.my.mindot_back.reports.dto.PersonalPatternDetailResponseDto;
import com.my.mindot_back.reports.dto.PatternFeedbackRequestDto;
import com.my.mindot_back.reports.dto.PatternFeedbackResponseDto;
import com.my.mindot_back.reports.service.RepeatedEmotionPatternService;
import com.my.mindot_back.reports.service.PersonalPatternService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/patterns")
@RequiredArgsConstructor
public class RepeatedEmotionPatternsController {

    private final RepeatedEmotionPatternService repeatedEmotionPatternService;
    private final PersonalPatternService personalPatternService;

    // 고정 식별자와 피드백을 포함한 현재 유효 반복 패턴 목록 조회
    @GetMapping
    public List<PersonalPatternResponseDto> getPatterns(@AuthenticationPrincipal Long userId) {
        return personalPatternService.list(userId);
    }

    // 패턴의 현재 집계와 본인 소유 근거 기록 조회
    @GetMapping("/{patternId}")
    public PersonalPatternDetailResponseDto getPattern(
            @AuthenticationPrincipal Long userId, @PathVariable Long patternId) {
        return personalPatternService.detail(userId, patternId);
    }

    // 패턴별 도움 여부 저장 또는 기존 선택 변경
    @PostMapping("/{patternId}/feedback")
    public PatternFeedbackResponseDto saveFeedback(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long patternId,
            @Valid @RequestBody PatternFeedbackRequestDto request) {
        return personalPatternService.feedback(userId, patternId, request);
    }

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
