// 선택한 주의 감정·CBT 통계와 통계 근거 목록을 프론트에 반환하는 DTO
package com.my.mindot_back.reports.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record WeeklyReportResponseDto (
        Long reportId,
        LocalDate periodStart,
        LocalDate periodEnd,
        int recordCount,
        String dominantEmotionCode,
        Double averageIntensity,
        int completedCbtCount,
        Double averageHelpfulnessScore,
        List<WeeklyReportEmotionEvidenceDto> emotionRecordEvidences,
        List<WeeklyReportCbtEvidenceDto> completedCbtEvidences,
        Map<String, Map<String, Long>> distortionChangeCounts,
        List<RepeatedEmotionPatternDto> repeatedPatterns,
        Map<String, Long> emotionCounts,
        Map<String, Long> contextCategoryCounts,
        Map<String, Map<String, Long>> positiveEmotionContextCounts,
        Map<String, Long> weekdayCounts,
        Map<String, Long> timeBucketCounts,
        Instant sourceSnapshotAt
) {
}
