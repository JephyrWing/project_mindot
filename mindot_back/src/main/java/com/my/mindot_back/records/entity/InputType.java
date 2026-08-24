// 감정 기록을 어떤 방식으로 입력했는지 구분하는 enum
package com.my.mindot_back.records.entity;

public enum InputType {

    // 키보드로 작성한 text
    TEXT,

    // 사용자 음성을 STT로 text 변환하여 저장한 기록
    VOICE_STT
}

