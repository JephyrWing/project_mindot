// 감정 기록 시간을 수정할 때 받는 요청 DTO (감정 발생 시각)
package com.my.mindot_back.records.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record EmotionRecordsUpdateRequestDto(

        // 사용자가 수정한 감정 발생 시각
        @NotNull
        Instant occurredAt
) {
}