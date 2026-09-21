// 반복 패턴 알림의 설정 조건·주간 멱등성·동시 생성·사용자별 실패 격리를 실제 DB로 검증

package com.my.mindot_back.notifications;

import com.my.mindot_back.notifications.dto.PatternNotificationResponseDto;
import com.my.mindot_back.notifications.entity.NotificationPreferences;
import com.my.mindot_back.notifications.entity.PatternNotification;
import com.my.mindot_back.notifications.repository.NotificationPreferencesRepository;
import com.my.mindot_back.notifications.repository.PatternNotificationRepository;
import com.my.mindot_back.notifications.service.PatternNotificationScheduler;
import com.my.mindot_back.notifications.service.PatternNotificationService;
import com.my.mindot_back.records.dto.EmotionRecordsConfirmRequestDto;
import com.my.mindot_back.records.entity.EmotionRecords;
import com.my.mindot_back.records.entity.InputType;
import com.my.mindot_back.records.repository.EmotionRecordsRepository;
import com.my.mindot_back.reports.entity.PatternLevel;
import com.my.mindot_back.support.PostgresContainerTestBase;
import com.my.mindot_back.users.entity.Users;
import com.my.mindot_back.users.repository.UsersRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class NotificationGenerationIntegrationTest
        extends PostgresContainerTestBase {

    @Autowired
    private UsersRepository usersRepository;

    @Autowired
    private EmotionRecordsRepository emotionRecordsRepository;

    @Autowired
    private NotificationPreferencesRepository
            notificationPreferencesRepository;

    @Autowired
    private PatternNotificationRepository
            patternNotificationRepository;

    @Autowired
    private PatternNotificationService
            patternNotificationService;

    @Autowired
    private PatternNotificationScheduler
            patternNotificationScheduler;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> createdUserIds =
            new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long userId : createdUserIds) {
            jdbcTemplate.update(
                    "delete from users where id = ?",
                    userId
            );
        }
    }

    @Test
    void schedulerCreatesOnlyEligibleNotificationAndIsolatesFailures() {
        ZoneId eligibleZone =
                ZoneId.of("Pacific/Honolulu");

        Users eligibleUser =
                createUser(
                        "eligible",
                        eligibleZone
                );

        createRecentPattern(
                eligibleUser,
                eligibleZone,
                "ANXIETY"
        );

        Users noPatternUser =
                createUser(
                        "no-pattern",
                        ZoneId.of("Asia/Seoul")
                );

        Users disabledUser =
                createUser(
                        "disabled",
                        ZoneId.of("Asia/Seoul")
                );

        createRecentPattern(
                disabledUser,
                ZoneId.of("Asia/Seoul"),
                "SADNESS"
        );

        createPreferences(
                eligibleUser,
                true,
                eligibleZone
        );

        createPreferences(
                noPatternUser,
                true,
                ZoneId.of("Asia/Seoul")
        );

        createPreferences(
                disabledUser,
                false,
                ZoneId.of("Asia/Seoul")
        );

        patternNotificationScheduler
                .generateDuePatternNotifications();

        List<PatternNotification> notifications =
                patternNotificationRepository.findAll();

        assertThat(notifications)
                .hasSize(1);

        PatternNotification notification =
                notifications.get(0);

        assertThat(notification.getUser().getId())
                .isEqualTo(eligibleUser.getId());
        assertThat(notification.getEmotionCode())
                .isEqualTo("ANXIETY");
        assertThat(notification.getPatternLevel())
                .isEqualTo(PatternLevel.RECENT);
        assertThat(notification.getWindowEnd())
                .isEqualTo(
                        LocalDate.now(eligibleZone)
                );
        assertThat(notification.getWindowStart())
                .isEqualTo(
                        LocalDate.now(eligibleZone)
                                .minusDays(55)
                );

        patternNotificationScheduler
                .generateDuePatternNotifications();

        assertThat(
                notificationCount(
                        eligibleUser.getId()
                )
        ).isEqualTo(1);

        PatternNotificationResponseDto repeated =
                patternNotificationService.generateForUser(
                        eligibleUser.getId()
                );

        assertThat(repeated.notificationId())
                .isEqualTo(notification.getId());
        assertThat(
                notificationCount(
                        eligibleUser.getId()
                )
        ).isEqualTo(1);

        assertThat(
                notificationCount(
                        noPatternUser.getId()
                )
        ).isZero();

        assertThat(
                notificationCount(
                        disabledUser.getId()
                )
        ).isZero();
    }

    @Test
    void concurrentGenerationCreatesOnlyOneNotification()
            throws Exception {
        ZoneId zoneId =
                ZoneId.of("Asia/Seoul");

        Users concurrentUser =
                createUser(
                        "concurrent",
                        zoneId
                );

        createRecentPattern(
                concurrentUser,
                zoneId,
                "ANGER"
        );

        createPreferences(
                concurrentUser,
                true,
                zoneId
        );

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        CountDownLatch ready =
                new CountDownLatch(2);
        CountDownLatch start =
                new CountDownLatch(1);

        try {
            Future<Long> first =
                    executor.submit(() -> {
                        ready.countDown();
                        start.await();

                        return patternNotificationService
                                .generateForUser(
                                        concurrentUser.getId()
                                )
                                .notificationId();
                    });

            Future<Long> second =
                    executor.submit(() -> {
                        ready.countDown();
                        start.await();

                        return patternNotificationService
                                .generateForUser(
                                        concurrentUser.getId()
                                )
                                .notificationId();
                    });

            assertThat(
                    ready.await(
                            10,
                            TimeUnit.SECONDS
                    )
            ).isTrue();

            start.countDown();

            Long firstNotificationId =
                    first.get(
                            20,
                            TimeUnit.SECONDS
                    );

            Long secondNotificationId =
                    second.get(
                            20,
                            TimeUnit.SECONDS
                    );

            assertThat(secondNotificationId)
                    .isEqualTo(firstNotificationId);

            assertThat(
                    notificationCount(
                            concurrentUser.getId()
                    )
            ).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private Users createUser(
            String prefix,
            ZoneId zoneId
    ) {
        Users saved =
                usersRepository.saveAndFlush(
                        Users.create(
                                prefix
                                        + "-"
                                        + UUID.randomUUID()
                                        + "@example.com",
                                "unused-password-hash",
                                prefix + " 사용자"
                        )
                );

        createdUserIds.add(saved.getId());

        jdbcTemplate.update(
                """
                update users
                   set timezone = ?
                 where id = ?
                """,
                zoneId.getId(),
                saved.getId()
        );

        return usersRepository.findById(
                        saved.getId()
                )
                .orElseThrow();
    }

    private void createPreferences(
            Users owner,
            boolean enabled,
            ZoneId zoneId
    ) {
        NotificationPreferences preferences =
                NotificationPreferences
                        .createDefault(owner);

        preferences.updatePatternAlert(
                enabled,
                LocalTime.now(zoneId)
                        .truncatedTo(
                                ChronoUnit.MINUTES
                        )
        );

        notificationPreferencesRepository
                .saveAndFlush(preferences);
    }

    private void createRecentPattern(
            Users owner,
            ZoneId zoneId,
            String emotionCode
    ) {
        LocalDate localToday =
                LocalDate.now(zoneId);

        createRecord(
                owner,
                emotionCode,
                localToday
                        .atTime(9, 0)
                        .atZone(zoneId)
                        .toInstant()
        );

        createRecord(
                owner,
                emotionCode,
                localToday
                        .atTime(9, 15)
                        .atZone(zoneId)
                        .toInstant()
        );

        createRecord(
                owner,
                emotionCode,
                localToday
                        .atTime(9, 30)
                        .atZone(zoneId)
                        .toInstant()
        );
    }

    private void createRecord(
            Users owner,
            String emotionCode,
            Instant occurredAt
    ) {
        EmotionRecords record =
                EmotionRecords.createQuick(
                        owner,
                        emotionCode + " 알림 패턴 기록",
                        InputType.TEXT,
                        occurredAt
                );

        record.confirm(
                new EmotionRecordsConfirmRequestDto(
                        emotionCode + " 상황",
                        emotionCode + " 자동 사고",
                        emotionCode,
                        (short) 6,
                        List.of(),
                        "OTHER",
                        "OTHER",
                        Map.of()
                )
        );

        emotionRecordsRepository.saveAndFlush(
                record
        );
    }

    private long notificationCount(
            Long userId
    ) {
        Long count = jdbcTemplate.queryForObject(
                """
                select count(*)
                  from pattern_notifications
                 where user_id = ?
                """,
                Long.class,
                userId
        );

        return count == null ? 0L : count;
    }
}