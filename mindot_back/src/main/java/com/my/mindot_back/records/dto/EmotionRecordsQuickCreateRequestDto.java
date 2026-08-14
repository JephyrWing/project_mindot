// 간편 감정 기록 생성을 위해 React가 Spring에 보내는 요청 DTO
package com.my.mindot_back.records.dto;

import com.my.mindot_back.records.entity.InputType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record EmotionRecordsQuickCreateRequestDto (

    // 사용자가 직접 입력 or STT로 변환한 원문
    @NotBlank
    String rawText,

    // TEXT or VOICE_STT 입력 방식
    @NotNull
    InputType inputType,

    // 사용자가 감정을 경험한 시각
    @NotNull
    Instant occurredAt
){
}
