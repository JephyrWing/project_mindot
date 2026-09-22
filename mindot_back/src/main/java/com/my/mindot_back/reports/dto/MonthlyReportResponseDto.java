// 선택한 달의 감정 흐름·분포·CBT 요약을 프론트에 반환하는 DTO
package com.my.mindot_back.reports.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record MonthlyReportResponseDto(

        // 저장된 월간 리포트 ID
        Long reportId,

        // 해당 월의 첫날과 마지막 날
        LocalDate periodStart,
        LocalDate periodEnd,

        // 한 달 동안 작성한 전체 감정 기록 수
        int recordCount,

        // 한 달 동안 가장 많이 기록된 대표 감정
        String dominantEmotionCode,

        // 강도가 입력된 전체 기록의 평균 감정 강도
        Double averageIntensity,

        // 한 달 동안 사용자가 최종 확정한 CBT 수
        int completedCbtCount,

        // 완료 CBT의 평균 도움 점수
        Double averageHelpfulnessScore,

        // 월 초반과 후반의 평균 감정 강도
        Double firstHalfAverageIntensity,
        Double secondHalfAverageIntensity,

        // 월 초반과 후반의 감정 강도 변화 방향
        MonthlyIntensityTrend intensityTrend,

        // 가장 많이 기록된 상황 분류
        String mostFrequentContextCategory,

        // 집계 결과를 사용자가 이해하기 쉽게 정리한 문장
        String summaryText,

        // 월간 그래프에 표시할 날짜별 집계
        List<MonthlyReportDailyTrendDto> dailyTrends,

        // 감정 코드별 기록 수
        Map<String, Long> emotionCounts,

        // 상황 분류별 기록 수
        Map<String, Long> contextCategoryCounts,

        // 리포트 원본 데이터 기준 시각
        Instant sourceSnapshotAt,

        MonthlyEmotionCompositionDto emotionComposition
) {
}
