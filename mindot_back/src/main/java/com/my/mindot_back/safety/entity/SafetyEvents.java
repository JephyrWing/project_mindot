// 감정 기록에서 위험 신호가 감지되었을 때 안내, 대응 이력을 남기는 Entity
package com.my.mindot_back.safety.entity;

import com.my.mindot_back.ai.entity.AiJobs;
import com.my.mindot_back.records.entity.EmotionRecords;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

@Entity
@Table(
        name = "safety_events",
        indexes = {
                // 특정 감정 기록에서 감지된 안전 이벤트를 조회할 때 사용
                @Index(
                        name = "idx_safety_events_emotion_record_id",
                        columnList = "emotion_record_id"
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SafetyEvents {

    // 안전 이벤트 식별자
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 위험 신호가 감지된 기록
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    // 원본 감정 기록 삭제되면 관련 안전 이벤트도 삭제
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "emotion_record_id", nullable = false)
    private EmotionRecords emotionRecords;

    // 위험 신호 판단 근거가된 Safety_check 작업
    // AI 작업 이력 삭제되더라도 안전 이벤트는 남김 (ai_job_id만 null로 변경)
    @ManyToOne(fetch = FetchType.LAZY)
    @OnDelete(action = OnDeleteAction.SET_NULL)
    @JoinColumn(name = "ai_job_id")
    private AiJobs aiJobs;

    // 위험 대응 수준
    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", nullable = false, length = 20)
    private RiskLevel riskLevel;

    // 원문 대신 저장하는 정규화된 위험 코드
    @Column(name = "reason_code", length = 50)
    private String reasonCode;

    // 실행한 고정 안전 조치 코드
    @Enumerated(EnumType.STRING)
    @Column(name = "action_code", nullable = false, length = 50)
    private SafetyActionCode actionCode;

    // 사용자에게 표시한 위기 안내 문구 버전
    @Column(name = "notice_version", nullable = false, length = 30)
    private String noticeVersion;

    // 안전 안내가 실제 표시된 시각
    @Column(name = "notice_shown_at")
    private Instant noticeShownAt;

    // 안전 이벤트가 생성된 시각
    // update X
    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // FastAPI가 감지한 위험 신호를 안전 이벤트 이력으로 생성
    public static SafetyEvents create(
            EmotionRecords emotionRecord,
            AiJobs aiJob,
            RiskLevel riskLevel,
            String reasonCode,
            SafetyActionCode actionCode
    ) {
        SafetyEvents safetyEvent = new SafetyEvents();

        safetyEvent.emotionRecords = emotionRecord;
        safetyEvent.aiJobs = aiJob;
        safetyEvent.riskLevel = riskLevel;
        safetyEvent.reasonCode = reasonCode;
        safetyEvent.actionCode = actionCode;

        // 안전 안내 문구가 바뀌어도 당시 버전을 추적할 수 있도록 저장
        safetyEvent.noticeVersion = "safety-notice-v1";

        return safetyEvent;
    }

    // 프론트가 안전 안내를 실제로 화면에 표시한 최초 시각 기록
    public void markNoticeShown(){
        // 중복 API 호출이 와도 최초 표시 시각은 유지
        if (this.noticeShownAt == null){
            this.noticeShownAt = Instant.now();
        }
    }

    // DB 저장 전 생성 시각을 현재 시각으로 설정
    @PrePersist
    void prePersist() {
        this.createdAt = Instant.now();
    }
}
