// 감정 기록을 바탕으로 진행한 CBT 성찰 질문, 답변 세션 Entity
package com.my.mindot_back.records.entity;

import com.my.mindot_back.users.entity.Users;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Entity
@Table(
        name = "reflection_sessions",
        // 사용자별 성찰 세션을 최신 시작 순으로 조회할 때 사용할 인덱스
        indexes = {
                @Index(
                        name = "idx_reflection_sessions_user_created_at",
                        columnList = "user_id, created_at DESC"
                )
        }
)
/*
 *null 값 허용하되 값이 있다면 각 범위 안에서만 저장하도록 제한
 * null 값을 false로 판단하지 않으므로 아직 작성하지 않은 항목은 null로 저장 가능
*/
@Check(
        name = "chk_reflection_sessions_score_range",
        constraints = """
                before_belief_strength BETWEEN 0 AND 100
                AND after_belief_strength BETWEEN 0 AND 100
                AND final_emotion_intensity BETWEEN 0 AND 10
                AND helpfulness_score BETWEEN 0 AND 5
                AND (status <> 'COMPLETED' OR completed_at IS NOT NULL)
                """
)

@Getter
// JPA만 빈 객체를 만들 수 있음
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReflectionSessions {

    // CBT 성찰 세션 식별자
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 세션 소유 사용자
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    // users.id 참조
    @JoinColumn(name = "user_id", nullable = false)
    private Users user;

    // 이 세션의 질문 전 원본 감정 기록
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    // emotion_records.id 참조
    // 감정 기록 1개로 성찰 세션 최대 1개만 만들 수
    @JoinColumn(
            name = "emotion_record_id",
            nullable = false,
            unique = true
    )
    private EmotionRecords emotionRecord;

    // CBT 성찰 세션 진행 상태
    @Enumerated(EnumType.STRING)
    // enum 이름 (OPEN, COMPLETED 등)을 문자열로 DB에 저장
    @Column(nullable = false, length = 20)
    private ReflectionSessionStatus status = ReflectionSessionStatus.OPEN;

    // 중단 후 재개 시 사용할 현재 단계
    @Column(name = "current_step", length = 50)
    private String currentStep;

    // FastAPI가 생성한 CBT 질문과 React가 전달한 사용자 답변을 순서대로 저장
    // 순서 배열: questionCode/question/answer/askedAt/answeredAt
    @JdbcTypeCode(SqlTypes.JSON)
    // postgreSQL의 jsonb 타입과 연결
    @Column(name = "question_answers", nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> questionAnswers = new ArrayList<>();

    // 최초 생각이 맞다고 본 근거
    @Column(name = "evidence_for_text", columnDefinition = "TEXT")
    private String evidenceForText;

    // 최초 생각과 다른 근거 or 다른 가능성
    @Column(name = "evidence_against_text", columnDefinition = "TEXT")
    private String evidenceAgainstText;

    // 성찰 후 사용자가 받아들인 대안적 해석
    @Column(name = "alternative_thought_text", columnDefinition = "TEXT")
    private String alternativeThoughtText;

    // 성찰 전 최초 생각을 사실로 믿은 정도 : 0~100
    @Column(name = "before_belief_strength")
    private Short beforeBeliefStrength;

    // 성찰 후 최초 생각에 남아 있는 확신 정도 : 0~100
    @Column(name = "after_belief_strength")
    private Short afterBeliefStrength;

    // 성찰 후 주 감정 강도 : 0~10
    // 성찰 전은 emotion_records.primary_intensity 에 이미 저장됨
    @Column(name = "final_emotion_intensity")
    private Short finalEmotionIntensity;

    // 성찰 과정 도움 정도
    @Column(name = "helpfulness_score")
    private Short helpfulnessScore;

    // 사용자가 성찰 결과를 확인했는지 여부
    @Column(name = "user_confirmed", nullable = false)
    private Boolean userConfirmed = false;

    // 최초 생각이 없는 현재 기록에서 유사한 CBT 사례를 찾는 1536차원 검색 벡터
    // 시간맥락, 상황 범주, 상황, 감정을 조합해 AI가 생성
    @JdbcTypeCode(SqlTypes.VECTOR)
    @Column(name = "context_embedding", columnDefinition = "vector(1536)")
    private float[] contextEmbedding;

    // 최초 생각까지 있는 기록에서 더 구체적인 유사 CBT 사례를 찾는 1536차원 검색 벡터
    // contextEmbedding에 사용한 정보와 automaticThought를 조합해 AI가 생성
    @JdbcTypeCode(SqlTypes.VECTOR)
    @Column(name = "thought_aware_embedding", columnDefinition = "vector(1536)")
    private float[] thoughtAwareEmbedding;

    // 두 검색 벡터의 모델명, 차원, 템플릿 버전, 생성 시각 등의 메타데이터
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "embedding_meta", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> embeddingMeta = new HashMap<>();

    // AI 처리 요약 (모델, 프롬프트 버전 정보)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ai_meta", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> aiMeta = new HashMap<>();

    // 성찰 세션 시작 시각
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // 성찰 세션 완료 시각 status가 COMPLETED가 된 시각
    @Column(name = "completed_at")
    private Instant completedAt;

    // 세션 내용이 마지막으로 수정된 시각
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;


    // DB에 처음 저장되기전 한번 실행
    // 생성, 수정 시각을 같은 현재 시각으로 설정
    @PrePersist
    void prePersist(){
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    // DB update 전 실행
    // 수정 시각만 현재 시각으로 갱신
    @PreUpdate
    void preUpdate() {
        this.updatedAt = Instant.now();
    }
}
