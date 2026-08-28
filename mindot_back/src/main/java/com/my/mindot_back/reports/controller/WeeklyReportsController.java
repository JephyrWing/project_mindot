// 주간 리포트 생성과 조회 HTTP API를 처리하는 Controller
package com.my.mindot_back.reports.controller;

import com.my.mindot_back.reports.dto.WeeklyReportResponseDto;
import com.my.mindot_back.reports.service.WeeklyReportsService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class WeeklyReportsController {

    // 주간 리포트 생성·조회 로직 호출
    private final WeeklyReportsService weeklyReportsService;

    // 선택한 주의 최신 감정 기록으로 리포트 생성 또는 갱신
    @PostMapping("/weekly")
    public WeeklyReportResponseDto generateWeeklyReport(
            @AuthenticationPrincipal Long userId,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate weekStart
    ) {
        return weeklyReportsService.generateWeeklyReport(
                userId,
                weekStart
        );
    }

    // 이미 생성된 선택 주의 리포트 조회
    @GetMapping("/weekly")
    public WeeklyReportResponseDto getWeeklyReport(
            @AuthenticationPrincipal Long userId,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate weekStart
    ) {
        return weeklyReportsService.getWeeklyReport(
                userId,
                weekStart
        );
    }
}