// 상담용 PDF 내보내기 조건을 받는 요청 DTO
package com.my.mindot_back.reports.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

public record PdfExportRequestDto (
        LocalDate startDate,

        LocalDate endDate,

        List<LocalDate> selectedDates,

        @NotNull
        ExportContentType contentType,

        boolean includeFullCbtConversation
){
}
