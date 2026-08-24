// AI 작업 처리 진행 상태를 구분하는 enum
package com.my.mindot_back.ai.entity;

public enum AiJobStatus {

    // 작업 생성후 실행 시작 전
    PENDING,

    // AI 서버에 요청해 현재 처리 중
    PROCESSING,

    // 작업 끝
    COMPLETED,

    // 처리 중 오류 발생
    FAILED
}
