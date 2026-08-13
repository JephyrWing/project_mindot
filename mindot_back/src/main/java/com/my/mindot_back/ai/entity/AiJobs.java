// FastAPI 등 AI 처리 요청의 실행 이력과 상태 저장하는 Entity
package com.my.mindot_back.ai.entity;

import com.my.mindot_back.users.entity.Users;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

@Entity
@Table(
        name = "ai_jobs",

        // 중복 저장 방지
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_ai_jobs_entity_operation_idempotency_attempt",
                        columnNames = {
                                "entity_type",
                                "entity_id",
                                "operation",
                                "idempotency_key",
                                "attempt_no"
                        }
                )
        },

        indexes = {
                // 사용자별 AI 작업 이력을 최신순으로 조회할 때 사용
                @Index(
                        name = "idx_ai_jobs_user_created_at",
                        columnList = "user_id, created_at DESC"
                ),

                // 특정 대상에 대한 작업 종류별 실행 이력을 최신순으로 조회할 때 사용
                @Index(
                        name = "idx_ai_jobs_entity_operation_created_at",
                        columnList = "entity_type, entity_id, operation, created_at DESC"
                )
        }
)
@Check(
        name = "chk_ai_jobs_status_and_attempt",
        constraints = """
                attempt_no >= 1
                AND (status <> 'FAILED' OR error_code IS NOT NULL)
                AND (status NOT IN ('COMPLETED', 'FAILED') OR completed_at IS NOT NULL)
                """
        // FAILED면 error_code 반드시 있어야함
        // COMPLETED, FAILED면 completed_at 반드시 있어야함
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiJobs {

    // AI 작업 실행 식별자
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // AI 작업을 요청한 사용자
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "user_id", nullable = false)
    private Users user;

    // AI 작업 대상 종류
    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 30)
    private AiJobEntityType entityType;

    // AI 작업 대상 ID
    // entityType에 따라 emotion_records.id, reflection_sessions.id, reports.id를 가리킴
    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    // AI 작업 종류
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private AiJobOperation operation;

    // 논리 요청 중복 방지 키
    @Column(name = "idempotency_key", nullable = false, length = 120)
    private String idempotencyKey;

    // AI 작업 현재 처리 상태
    @Enumerated(EnumType.STRING)
    @ColumnDefault("'PENDING'")
    @Column(nullable = false, length = 20)
    private AiJobStatus status = AiJobStatus.PENDING;

    // 동일 요청 재시도 횟수, 1부터 시작
    @ColumnDefault("1")
    @Column(name = "attempt_no", nullable = false)
    private Short attemptNo = 1;

    // 작업에 사용한 AI 모델
    @Column(name = "model_name", length = 80)
    private String modelName;

    // 프롬프트, Structured Output 버전
    @Column(name = "prompt_version", length = 30)
    private String promptVersion;

    // 실패했을 때 저장할 정규화된 코드
    // 원문 저장 x
    @Column(name = "error_code", length = 50)
    private String errorCode;

    // AI 작업 시작 시각
    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    // 완료 or 실패 시각
    @Column(name = "completed_at")
    private Instant completedAt;

    // AI 작업 이력 행 생성 시각
    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // DB 저장 전 시작, 생성 시각을 현재 시각으로 설정
    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        this.startedAt = now;
        this.createdAt = now;
    }
}
