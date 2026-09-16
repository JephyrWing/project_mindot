// 읽지 않은 반복 패턴 알림 수 응답 DTO
package com.my.mindot_back.notifications.dto;

public record UnreadNotificationCountResponseDto(
        long unreadCount
) {
}