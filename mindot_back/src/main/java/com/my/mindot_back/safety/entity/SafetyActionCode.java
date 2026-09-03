// 위험 수준에 따라 프론트가 표시할 고정 안전 안내 동작을 구분하는 enum
package com.my.mindot_back.safety.entity;

public enum SafetyActionCode {

    // 추가 확인과 전문 도움 안내 표시
    SHOW_REVIEW_NOTICE,

    // 즉시 위기 안내를 표시
    SHOW_CRISIS_NOTICE
}
