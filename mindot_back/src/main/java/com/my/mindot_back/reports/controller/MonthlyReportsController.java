// 월간 리포트 생성과 조회 HTTP API를 처리하는 Controller
package com.my.mindot_back.reports.controller;

import com.my.mindot_back.reports.dto.MonthlyReportResponseDto;
import com.my.mindot_back.reports.service.MonthlyReportsService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;

@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class MonthlyReportsController {

    private final MonthlyReportsService monthlyReportsService;

    // 선택한 달의 최신 기록으로 월간 리포트 생성 또는 갱신
    @PostMapping("/monthly")
    public MonthlyReportResponseDto generateMonthlyReport(
            @AuthenticationPrincipal Long userId,
            @RequestParam
            @DateTimeFormat(pattern = "yyyy-MM")
            YearMonth month
    ) {
        return monthlyReportsService.generateMonthlyReport(
                userId,
                month
        );
    }

    // 이미 생성된 선택 달의 월간 리포트 조회
    @GetMapping("/monthly")
    public MonthlyReportResponseDto getMonthlyReport(
            @AuthenticationPrincipal Long userId,
            @RequestParam
            @DateTimeFormat(pattern = "yyyy-MM")
            YearMonth month
    ) {
        return monthlyReportsService.getMonthlyReport(
                userId,
                month
        );
    }
}