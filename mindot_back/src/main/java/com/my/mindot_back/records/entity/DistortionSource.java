// 인지왜곡 라벨을 처음 제안하거나 추가한 주체를 구분하는 enum
package com.my.mindot_back.records.entity;

public enum DistortionSource {

    // AI가 자동으로 분류해 제안한 라벨
    AI,

    // 사용자가 직접 추가하거나 선택한 라벨
    USER
}