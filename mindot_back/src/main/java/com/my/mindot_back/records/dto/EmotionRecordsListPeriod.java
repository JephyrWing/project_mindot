// 감정 기록 목록의 기간 조회 조건을 정의하는 enum
package com.my.mindot_back.records.dto;

public enum EmotionRecordsListPeriod {
    ALL,
    RECENT_7_DAYS,
    WEEK,
    MONTH
}