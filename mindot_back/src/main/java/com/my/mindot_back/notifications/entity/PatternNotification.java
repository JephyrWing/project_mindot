// 탐지된 반복 감정 패턴 알림을 저장하는 Entity
package com.my.mindot_back.notifications.entity;

import com.my.mindot_back.reports.dto.RepeatedEmotionPatternDto;
import com.my.mindot_back.reports.entity.PatternLevel;
import com.my.mindot_back.users.entity.Users;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(
        name = "pattern_notifications",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_pattern_notifications_deduplication_key",
                        columnNames = "deduplication_key"
                )
        },
        indexes = {
                @Index(
                        name = "idx_pattern_notifications_user_created_at",
                        columnList = "user_id, created_at DESC"
                ),
                @Index(
                        name = "idx_pattern_notifications_user_read_at",
                        columnList = "user_id, read_at"
                )
        }
)
@Check(
        name = "chk_pattern_notifications_counts",
        constraints = "occurrence_count >= 1 "
                + "AND distinct_date_count >= 1 "
                + "AND observed_week_count >= 1 "
                + "AND window_end >= window_start"
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PatternNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 알림 소유 사용자
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "user_id", nullable = false)
    private Users user;

    // 감정·요일·시간대 조합 식별값
    @Column(
            name = "pattern_key",
            nullable = false,
            length = 150
    )
    private String patternKey;

    @Column(
            name = "emotion_code",
            nullable = false,
            length = 50
    )
    private String emotionCode;

    // 여러 요일에 걸친 패턴은 null
    @Column(length = 15)
    private String weekday;

    @Column(
            name = "time_bucket",
            nullable = false,
            length = 20
    )
    private String timeBucket;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "pattern_level",
            nullable = false,
            length = 20
    )
    private PatternLevel patternLevel;

    @Column(
            name = "occurrence_count",
            nullable = false
    )
    private long occurrenceCount;

    @Column(
            name = "distinct_date_count",
            nullable = false
    )
    private long distinctDateCount;

    @Column(
            name = "observed_week_count",
            nullable = false
    )
    private long observedWeekCount;

    // 패턴 계산에 사용한 최근 8주 시작일
    @Column(
            name = "window_start",
            nullable = false
    )
    private LocalDate windowStart;

    // 패턴 계산 기준일
    @Column(
            name = "window_end",
            nullable = false
    )
    private LocalDate windowEnd;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(nullable = false, length = 500)
    private String message;

    // 사용자가 실행할 수 있는 간단한 추천 행동
    @Column(
            name = "recommended_action",
            nullable = false,
            length = 500
    )
    private String recommendedAction;

    // 같은 사용자·패턴·등급·주차의 중복 생성 방지값
    @Column(
            name = "deduplication_key",
            nullable = false,
            unique = true,
            length = 255
    )
    private String deduplicationKey;

    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private Instant createdAt;

    @Column(name = "read_at")
    private Instant readAt;

    // 탐지된 반복 패턴으로 알림 생성
    public static PatternNotification create(
            Users user,
            RepeatedEmotionPatternDto pattern,
            String patternKey,
            LocalDate windowStart,
            LocalDate windowEnd,
            String title,
            String message,
            String recommendedAction,
            String deduplicationKey
    ) {
        PatternNotification notification =
                new PatternNotification();

        notification.user = user;
        notification.patternKey = patternKey;
        notification.emotionCode = pattern.emotionCode();
        notification.weekday = pattern.weekday();
        notification.timeBucket = pattern.timeBucket();
        notification.patternLevel = pattern.patternLevel();
        notification.occurrenceCount = pattern.occurrenceCount();
        notification.distinctDateCount = pattern.distinctDateCount();
        notification.observedWeekCount = pattern.observedWeekCount();
        notification.windowStart = windowStart;
        notification.windowEnd = windowEnd;
        notification.title = title;
        notification.message = message;
        notification.recommendedAction = recommendedAction;
        notification.deduplicationKey = deduplicationKey;

        return notification;
    }

    // 알림 읽음 처리
    public void markRead() {
        if (readAt == null) {
            readAt = Instant.now();
        }
    }

    // 읽음 여부 확인
    public boolean isRead() {
        return readAt != null;
    }

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }
}