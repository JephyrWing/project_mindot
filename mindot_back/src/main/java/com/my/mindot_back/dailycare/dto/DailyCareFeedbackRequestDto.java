// 추천에 대한 사용자의 도움됨 또는 나중에 선택을 받는 요청 DTO
package com.my.mindot_back.dailycare.dto;

import jakarta.validation.constraints.NotNull;

public record DailyCareFeedbackRequestDto(
        @NotNull Feedback feedback
) {
    public enum Feedback {
        HELPFUL,
        LATER
    }
}
