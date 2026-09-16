// 월 초반과 후반의 평균 감정 강도 변화를 표현
package com.my.mindot_back.reports.dto;

public enum MonthlyIntensityTrend {

    // 월 후반의 평균 강도가 높아짐
    INCREASED,

    // 월 후반의 평균 강도가 낮아짐
    DECREASED,

    // 유의미한 변화가 없음
    STABLE,

    // 비교할 감정 강도 데이터가 부족함
    INSUFFICIENT_DATA
}