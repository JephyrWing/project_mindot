// 반복 집계 결과와 사용자가 남긴 피드백을 같은 논리 패턴에 유지하는 Entity
package com.my.mindot_back.reports.entity;

import com.my.mindot_back.users.entity.Users;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "personal_patterns", uniqueConstraints =
        @UniqueConstraint(name = "uk_personal_patterns_user_key", columnNames = {"user_id", "pattern_key"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PersonalPattern {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "user_id", nullable = false)
    private Users user;

    @Column(name = "pattern_key", nullable = false, length = 160)
    private String patternKey;

    @Column(name = "emotion_code", nullable = false, length = 50)
    private String emotionCode;

    @Column(length = 12)
    private String weekday;

    @Column(name = "time_bucket", nullable = false, length = 20)
    private String timeBucket;

    @Enumerated(EnumType.STRING)
    @Column(name = "pattern_level", nullable = false, length = 20)
    private PatternLevel patternLevel;

    @Column(name = "occurrence_count", nullable = false)
    private long occurrenceCount;

    @Column(name = "distinct_date_count", nullable = false)
    private long distinctDateCount;

    @Column(name = "observed_week_count", nullable = false)
    private long observedWeekCount;

    @Column(name = "window_start", nullable = false)
    private LocalDate windowStart;

    @Column(name = "window_end", nullable = false)
    private LocalDate windowEnd;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private PatternFeedback feedback;

    @Column(name = "feedback_at")
    private Instant feedbackAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static PersonalPattern create(Users user, String patternKey) {
        PersonalPattern pattern = new PersonalPattern();
        pattern.user = user;
        pattern.patternKey = patternKey;
        return pattern;
    }

    // 새 집계값만 교체하고 기존 식별자와 피드백은 보존
    public void update(String emotionCode, String weekday, String timeBucket,
                       PatternLevel level, long occurrenceCount, long distinctDateCount,
                       long observedWeekCount, LocalDate windowStart, LocalDate windowEnd) {
        this.emotionCode = emotionCode;
        this.weekday = weekday;
        this.timeBucket = timeBucket;
        this.patternLevel = level;
        this.occurrenceCount = occurrenceCount;
        this.distinctDateCount = distinctDateCount;
        this.observedWeekCount = observedWeekCount;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.updatedAt = Instant.now();
    }

    // 같은 패턴에 대한 재평가 시 기존 선택을 덮어씀
    public void setFeedback(PatternFeedback feedback) {
        this.feedback = feedback;
        this.feedbackAt = Instant.now();
    }

    @PrePersist
    void prePersist() {
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }
}
