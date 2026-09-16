// 월간 리포트 생성과 조회 HTTP API를 처리하는 Controller
package com.my.mindot_back.reports.controller;

import com.my.mindot_back.reports.dto.MonthlyReportResponseDto;
import com.my.mindot_back.reports.service.MonthlyReportsService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.my.mindot_back.reports.service.MonthlyReportPdfService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.YearMonth;

@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class MonthlyReportsController {

    private final MonthlyReportsService monthlyReportsService;

    // 월간 리포트를 시각화된 PDF 파일로 생성
    private final MonthlyReportPdfService monthlyReportPdfService;

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

    // 이미 생성된 월간 리포트를 그래프와 표가 포함된 PDF로 다운로드
    @GetMapping(
            value = "/monthly/pdf",
            produces = MediaType.APPLICATION_PDF_VALUE
    )
    public ResponseEntity<byte[]> exportMonthlyReportPdf(
            @AuthenticationPrincipal Long userId,
            @RequestParam
            @DateTimeFormat(pattern = "yyyy-MM")
            YearMonth month
    ) {
        byte[] pdfBytes =
                monthlyReportPdfService.exportMonthlyPdf(userId, month);

        String fileName =
                "mindot-monthly-report-" + month + ".pdf";

        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + fileName + "\""
                )
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(pdfBytes.length)
                .body(pdfBytes);
    }
}