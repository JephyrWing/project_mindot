// 감정 기록이 어디까지 작성되었는지 구분하는 enum
package com.my.mindot_back.records.entity;

public enum CompletionStatus {

    // 감정 원문만 저장
    QUICK,

    // 일부 정보만 입력 or AI 구조화 완료 전
    PARTIAL,

    // 필요한 정보 확인, 저장 완료 상태
    COMPLETE
}
