// 반복 패턴 알림 단건 응답 DTO
package com.my.mindot_back.notifications.dto;

import com.my.mindot_back.notifications.entity.PatternNotification;
import com.my.mindot_back.reports.entity.PatternLevel;

import java.time.Instant;
import java.time.LocalDate;

public record PatternNotificationResponseDto(
        Long notificationId,
        String emotionCode,
        String weekday,
        String timeBucket,
        PatternLevel patternLevel,
        long occurrenceCount,
        long distinctDateCount,
        long observedWeekCount,
        LocalDate windowStart,
        LocalDate windowEnd,
        String title,
        String message,
        String recommendedAction,
        boolean read,
        Instant createdAt,
        Instant readAt
) {

    // 알림 Entity를 응답으로 변환
    public static PatternNotificationResponseDto from(
            PatternNotification notification
    ) {
        return new PatternNotificationResponseDto(
                notification.getId(),
                notification.getEmotionCode(),
                notification.getWeekday(),
                notification.getTimeBucket(),
                notification.getPatternLevel(),
                notification.getOccurrenceCount(),
                notification.getDistinctDateCount(),
                notification.getObservedWeekCount(),
                notification.getWindowStart(),
                notification.getWindowEnd(),
                notification.getTitle(),
                notification.getMessage(),
                notification.getRecommendedAction(),
                notification.isRead(),
                notification.getCreatedAt(),
                notification.getReadAt()
        );
    }
}