// 인지왜곡 라벨이 성찰 질문 전, 후 중 어느 생각에 해당하는지 구분
package com.my.mindot_back.records.entity;

public enum DistortionPhase {

    // CBT 질문 전 최초 자동적 사고의 인지왜곡
    BEFORE,

    // CBT 질문 후 대안적 생각 또는 변화한 생각의 인지왜곡
    AFTER
}
