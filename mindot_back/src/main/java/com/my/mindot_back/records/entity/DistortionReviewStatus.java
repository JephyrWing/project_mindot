// AI 또는 사용자가 제안한 인지왜곡 라벨의 사용자 검토 상태
package com.my.mindot_back.records.entity;

public enum DistortionReviewStatus {

    // 아직 사용자가 확인하지 않은 AI 제안 상태
    PROPOSED,

    // 사용자가 맞다고 확인한 상태
    // 통계·패턴 분석에는 이 상태의 라벨만 사용
    CONFIRMED,

    // 사용자가 아니라고 거절한 상태
    REJECTED
}