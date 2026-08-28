// 선택한 주의 감정 기록 통계를 React에 반환하는 DTO
package com.my.mindot_back.reports.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

public record WeeklyReportResponseDto (
        Long reportId,
        LocalDate periodStart,
        LocalDate periodEnd,
        int recordCount,
        String dominantEmotionCode,
        Double averageIntensity,
        Map<String, Long> emotionCounts,
        Map<String, Long> weekdayCounts,
        Map<String, Long> timeBucketCounts,
        Instant sourceSnapshotAt
){
}
