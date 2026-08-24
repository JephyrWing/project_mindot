// 감정 기록에서 감지된 위험 신호의 대응 수준을 구분하는 enum
package com.my.mindot_back.safety.entity;

public enum RiskLevel {

    // 검토, 추가 안내 필요
    REVIEW,

    // 즉시 안내 표시
    CRISIS
}
