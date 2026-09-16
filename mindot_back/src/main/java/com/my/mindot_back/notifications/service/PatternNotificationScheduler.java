// 사용자별 희망 시각에 반복 패턴 알림을 자동 생성하는 Scheduler
package com.my.mindot_back.notifications.service;

import com.my.mindot_back.notifications.entity.NotificationPreferences;
import com.my.mindot_back.notifications.repository.NotificationPreferencesRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class PatternNotificationScheduler {

    private final NotificationPreferencesRepository
            notificationPreferencesRepository;

    private final PatternNotificationService
            patternNotificationService;

    // 매분 활성화된 사용자 중 현재 현지 시각이 희망 시각인 사용자 처리
    @Scheduled(cron = "0 * * * * *")
    public void generateDuePatternNotifications() {
        notificationPreferencesRepository
                .findAllByPatternAlertEnabledTrue()
                .stream()
                .filter(this::isDue)
                .forEach(this::generateSafely);
    }

    // 사용자 시간대 기준 현재 시각과 희망 시각을 분 단위로 비교
    private boolean isDue(
            NotificationPreferences preferences
    ) {
        ZoneId zoneId = ZoneId.of(
                preferences.getUser().getTimezone()
        );

        LocalTime currentTime = LocalTime.now(zoneId)
                .truncatedTo(ChronoUnit.MINUTES);

        LocalTime preferredTime = preferences.getPreferredTime()
                .truncatedTo(ChronoUnit.MINUTES);

        return currentTime.equals(preferredTime);
    }

    // 한 사용자의 생성 실패가 다른 사용자의 알림 생성을 막지 않도록 분리
    private void generateSafely(
            NotificationPreferences preferences
    ) {
        Long userId = preferences.getUser().getId();

        try {
            patternNotificationService.generateForUser(userId);
        } catch (ResponseStatusException exception) {
            // 반복 패턴 없음 또는 설정 변경은 생성 대상에서 제외
            log.debug(
                    "패턴 알림 생성 제외: userId={}, status={}, reason={}",
                    userId,
                    exception.getStatusCode(),
                    exception.getReason()
            );
        } catch (RuntimeException exception) {
            log.error(
                    "패턴 알림 자동 생성 실패: userId={}",
                    userId,
                    exception
            );
        }
    }
}