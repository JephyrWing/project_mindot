// 사용자별 알림 수신 설정을 저장하는 Entity
package com.my.mindot_back.notifications.entity;

import com.my.mindot_back.users.entity.Users;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;
import java.time.LocalTime;

@Entity
@Table(
        name = "notification_preferences",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_notification_preferences_user",
                        columnNames = "user_id"
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationPreferences {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 알림 설정 소유 사용자
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(
            name = "user_id",
            nullable = false,
            unique = true
    )
    private Users user;

    // 오늘 기준 최근 8주 반복 패턴 알림 수신 여부
    @ColumnDefault("false")
    @Column(
            name = "pattern_alert_enabled",
            nullable = false
    )
    private boolean patternAlertEnabled;

    // 사용자 현지 시간 기준 알림 희망 시각
    @ColumnDefault("'09:00:00'")
    @Column(
            name = "preferred_time",
            nullable = false
    )
    private LocalTime preferredTime;

    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private Instant createdAt;

    @Column(
            name = "updated_at",
            nullable = false
    )
    private Instant updatedAt;

    // 신규 사용자의 기본 알림 설정 생성
    public static NotificationPreferences createDefault(
            Users user
    ) {
        NotificationPreferences preferences =
                new NotificationPreferences();

        preferences.user = user;

        // 명시적 동의 전에는 패턴 알림 비활성화
        preferences.patternAlertEnabled = false;
        preferences.preferredTime = LocalTime.of(9, 0);

        return preferences;
    }

    // 패턴 알림 수신 여부와 희망 시각 변경
    public void updatePatternAlert(
            boolean enabled,
            LocalTime preferredTime
    ) {
        this.patternAlertEnabled = enabled;
        this.preferredTime = preferredTime;
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();

        if (preferredTime == null) {
            preferredTime = LocalTime.of(9, 0);
        }

        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}