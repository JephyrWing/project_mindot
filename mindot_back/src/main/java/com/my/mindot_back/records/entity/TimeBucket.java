// 감정 기록이 발생한 시간대를 구분하는 enum
package com.my.mindot_back.records.entity;

public enum TimeBucket {

    // 00:00 ~ 05:59
    DAWN,

    // 06:00 ~ 11:59
    MORNING,

    // 12:00 ~ 17:59
    AFTERNOON,

    // 18:00 ~ 20:59
    EVENING,

    // 21:00 ~ 23:59
    NIGHT
}