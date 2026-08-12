// CBT 성찰 세션이 어느 상태인지 구분하는 enum
package com.my.mindot_back.records.entity;

public enum ReflectionSessionStatus {

    // 질문 진행 중 or 중단 후 다시 이어갈 수 있는 상태
    OPEN,

    // 모두 완료한 상태
    COMPLETED,

    // 사용자가 취소한 상태
    CANCELLED
}
