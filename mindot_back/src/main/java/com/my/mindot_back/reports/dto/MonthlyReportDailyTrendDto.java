// 월간 리포트 그래프에 표시할 날짜별 감정 기록 집계 결과
package com.my.mindot_back.reports.dto;

import java.time.LocalDate;

public record MonthlyReportDailyTrendDto(

        // 집계 날짜
        LocalDate date,

        // 해당 날짜에 작성한 감정 기록 수
        long recordCount,

        // 강도가 입력된 기록들의 평균 감정 강도
        // 기록이 없거나 강도가 모두 null이면 null
        Double averageIntensity,

        // 해당 날짜에 가장 많이 기록된 대표 감정 코드
        String dominantEmotionCode
) {
}