// 사용자별 오늘의 마음 돌봄 추천과 피드백을 저장하는 Entity
package com.my.mindot_back.dailycare.entity;

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
@Table(name = "daily_care_recommendations", uniqueConstraints =
        @UniqueConstraint(name = "uk_daily_care_user_date", columnNames = {"user_id", "recommendation_date"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DailyCareRecommendation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "user_id", nullable = false)
    private Users user;

    @Column(name = "recommendation_date", nullable = false)
    private LocalDate recommendationDate;

    @Column(name = "basis_key", nullable = false, length = 255)
    private String basisKey;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, length = 20)
    private String activity;

    @Column(nullable = false, length = 30)
    private String source;

    @Column(name = "emotion_record_id")
    private Long emotionRecordId;

    @Column(name = "reflection_session_id")
    private Long reflectionSessionId;

    @Column(length = 20)
    private String feedback;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "feedback_at")
    private Instant feedbackAt;

    public static DailyCareRecommendation create(
            Users user,
            LocalDate date,
            String basisKey,
            String title,
            String description,
            String activity,
            String source,
            Long emotionRecordId,
            Long reflectionSessionId
    ) {
        DailyCareRecommendation recommendation = new DailyCareRecommendation();
        recommendation.user = user;
        recommendation.recommendationDate = date;
        recommendation.replace(
                basisKey, title, description, activity, source, emotionRecordId, reflectionSessionId
        );
        return recommendation;
    }

    // 같은 날 추천 근거가 바뀌면 이전 피드백을 새 추천에 적용하지 않음
    public void replace(
            String basisKey,
            String title,
            String description,
            String activity,
            String source,
            Long emotionRecordId,
            Long reflectionSessionId) {
        this.basisKey = basisKey;
        this.title = title;
        this.description = description;
        this.activity = activity;
        this.source = source;
        this.emotionRecordId = emotionRecordId;
        this.reflectionSessionId = reflectionSessionId;
        this.feedback = null;
        this.feedbackAt = null;
        this.createdAt = Instant.now();
    }

    public void setFeedback(String feedback) {
        this.feedback = feedback;
        this.feedbackAt = Instant.now();
    }
}
