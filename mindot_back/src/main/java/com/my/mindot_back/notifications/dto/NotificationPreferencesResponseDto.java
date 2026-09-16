// 반복 패턴 알림 설정 응답 DTO
package com.my.mindot_back.notifications.dto;

import com.my.mindot_back.notifications.entity.NotificationPreferences;

import java.time.Instant;
import java.time.LocalTime;

public record NotificationPreferencesResponseDto(
        boolean patternAlertEnabled,
        LocalTime preferredTime,
        String timezone,
        Instant updatedAt
) {

    // 저장된 알림 설정을 응답으로 변환
    public static NotificationPreferencesResponseDto from(
            NotificationPreferences preferences
    ) {
        return new NotificationPreferencesResponseDto(
                preferences.isPatternAlertEnabled(),
                preferences.getPreferredTime(),
                preferences.getUser().getTimezone(),
                preferences.getUpdatedAt()
        );
    }

    // 저장된 설정이 없는 사용자의 기본 응답 생성
    public static NotificationPreferencesResponseDto defaultValue(
            String timezone
    ) {
        return new NotificationPreferencesResponseDto(
                false,
                LocalTime.of(9, 0),
                timezone,
                null
        );
    }
}