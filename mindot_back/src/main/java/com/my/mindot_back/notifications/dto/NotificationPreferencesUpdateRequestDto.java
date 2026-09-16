// 반복 패턴 알림 설정 변경 요청 DTO
package com.my.mindot_back.notifications.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalTime;

public record NotificationPreferencesUpdateRequestDto(

        @NotNull(message = "알림 수신 여부는 필수입니다.")
        Boolean patternAlertEnabled,

        @NotNull(message = "알림 희망 시각은 필수입니다.")
        LocalTime preferredTime
) {
}