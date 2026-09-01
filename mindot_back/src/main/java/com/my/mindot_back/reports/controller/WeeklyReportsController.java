// 주간 리포트 생성과 조회 HTTP API를 처리하는 Controller
package com.my.mindot_back.reports.controller;

import com.my.mindot_back.reports.dto.PdfExportRequestDto;
import com.my.mindot_back.reports.dto.WeeklyReportResponseDto;
import com.my.mindot_back.reports.service.WeeklyReportsService;
import com.my.mindot_back.reports.service.PdfExportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class WeeklyReportsController {

    // 주간 리포트 생성·조회 로직 호출
    private final WeeklyReportsService weeklyReportsService;

    // 상담용 PDF 내보내기 생성 Service
    private final PdfExportService pdfExportService;

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

    // 선택한 날짜의 감정 기록, CBT 결과를 상담용 PDF로 다운로드
    @PostMapping(
            value = "/export/pdf",
            produces = MediaType.APPLICATION_PDF_VALUE
    )
    public ResponseEntity<byte[]> exportPdf(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody PdfExportRequestDto dto
    ) {
        byte[] pdfBytes = pdfExportService.exportPdf(userId, dto);

        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"mindot-consultation-record.pdf\""
                )
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdfBytes);
    }
}