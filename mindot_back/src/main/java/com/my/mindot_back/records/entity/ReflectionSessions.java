// 감정 기록을 바탕으로 진행한 CBT 성찰 질문, 답변 세션 Entity
package com.my.mindot_back.records.entity;

import com.my.mindot_back.records.dto.ai.FastApiCbtResponseDto;
import com.my.mindot_back.users.entity.Users;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
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
    // 사용자가 완전히 삭제되면 해당 사용자의 성찰 세션도 함께 삭제
    @OnDelete(action = OnDeleteAction.CASCADE)
    // users.id 참조
    @JoinColumn(name = "user_id", nullable = false)
    private Users user;

    // 이 세션의 질문 전 원본 감정 기록
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    // 원본 감정 기록이 삭제되면 해당 기록의 성찰 세션도 함께 삭제
    @OnDelete(action = OnDeleteAction.CASCADE)
    // emotion_records.id 참조
    // 감정 기록 1개로 성찰 세션 최대 1개만 만들 수 있음
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

    // 감정 기록을 바탕으로 새 CBT 성찰 세션을 생성하는 정적 생성 메서드
    public static ReflectionSessions create(
            // 성찰 세션을 소유하는 로그인 사용자
            Users user,

            // 성찰 세션의 원본 감정 기록
            EmotionRecords emotionRecord
    ){
        ReflectionSessions reflectionSessions =
                new ReflectionSessions();

        // users 테이블의 사용자와 연결
        reflectionSessions.user = user;

        // emotion_records 테이블의 원본 감정 기록과 연결
        reflectionSessions.emotionRecord = emotionRecord;

        return reflectionSessions;
    }

    // FastAPI가 생성한 CBT 질문을 question_answers JSONB에 저장
    // 사용자 답변 전이므로 answer와 answeredAt은 null로 저장
    public void addQuestion(
            FastApiCbtResponseDto.GeneratedQuestion nextQuestion
    ){
        // CONTINUE 상태인데 질문이 비어 있는 비정상 FastAPI 응답 처리
        if (nextQuestion == null) {
            throw new IllegalArgumentException(
                    "저장할 CBT 질문이 없습니다."
            );
        }

        // FastAPI QuestionAnswer 형식에 맞춰 JSONB 한 항목을 만듦
        // answer, answeredAt이 null인 첫 질문은 HashMap 사용
        Map<String, Object> questionAnswer = new HashMap<>();

        // 질문을 구분하는 안정 코드
        questionAnswer.put("questionCode", nextQuestion.questionCode());

        // 질문 목적
        questionAnswer.put("questionPurpose", nextQuestion.questionPurpose());

        // 프론트에 보여 줄 실제 질문 문장
        questionAnswer.put("question", nextQuestion.question());

        // 첫 질문은 아직 답변 전이므로 null
        questionAnswer.put("answer", null);

        // 질문을 AI에게서 받은 현재 시각
        questionAnswer.put("askedAt", Instant.now().toString());

        // 아직 답변 전이므로 null
        questionAnswer.put("answeredAt", null);

        // question_answers JSON 배열에 질문 1개 추가
        this.questionAnswers.add(questionAnswer);

        // 현재 진행 단계를 방금 생성한 질문 코드로 저장
        this.currentStep = nextQuestion.questionCode();
    }

    // 현재 화면에 표시된 CBT 질문의 사용자 답변을 question_answers JSONB에 저장
    public void answerCurrentQuestion(String answer) {
        // 빈 답변은 전달할 수 없으므로 저장 전 검증
        if (answer == null || answer.isBlank()) {
            throw new IllegalArgumentException("답변 내용은 비어 있을 수 없습니다.");
        }

        // 아직 질문이 없는 세션에는 답변을 저장할 수 없음
        if (currentStep == null) {
            throw new IllegalArgumentException("현재 답변할 CBT 질문이 없습니다.");
        }

        // currentStep과 같은 questionCode를 가진 질문을 찾음
        Map<String, Object> currentQuestion = questionAnswers.stream()
                .filter(question -> currentStep.equals(question.get("questionCode")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "현재 단계에 해당하는 CBT 질문을 찾을 수 없습니다."
                ));

        // 이미 답변된 질문에 다시 답변을 저장하는 요청 방지
        if (currentQuestion.get("answer") != null) {
            throw new IllegalStateException("이미 답변한 CBT 질문입니다.");
        }

        // JSONB의 기존 질문 항목에 사용자 답변, 답변 시각 채움
        currentQuestion.put("answer", answer);
        currentQuestion.put("answeredAt", Instant.now().toString());
    }

    // FastAPI가 반환한 모델명, 프롬프트 버전을 ai_meta JSONB에 저장
    public void applyAiMeta(
            FastApiCbtResponseDto.AnalysisMeta meta
    ){
        // FastAPI가 필수 메타 정보를 누락한 비정상 응답 방어
        if (meta == null) {
            throw new IllegalArgumentException(
                    "AI 처리 메타 정보가 없습니다."
            );
        }

        // 이전 메타 정보가 있다면 이번 FastAPI 응답 값으로 교체
        Map<String, Object> newAiMeta = new HashMap<>();

        // 예: gpt-4o-mini
        newAiMeta.put("model", meta.model());

        // 예: cbt-turn-v1
        newAiMeta.put("promptVersion", meta.promptVersion());

        // AI 응답을 Spring이 받은 시각
        newAiMeta.put("receivedAt", Instant.now().toString());

        // reflection_sessions.ai_meta JSONB 컬럼에 저장될 값
        this.aiMeta = newAiMeta;
    }

    // OpenAI가 생성한 두 임베딩 벡터를 성찰 세션에 반영
    /*
     * contextEmbedding:
     * 자동사고를 제외한 상황·감정 중심의 검색 벡터
     *
     * thoughtAwareEmbedding:
     * 자동사고까지 포함한 더 구체적인 검색 벡터
     */
    public void applyEmbedding(
            float[] contextEmbedding,
            float[] thoughtAwareEmbedding
    ){
        // PostgreSQL의 vector(1536) 컬럼에는 1536개 숫자만 저장 -> 미리 검사
        if (contextEmbedding == null
                || contextEmbedding.length != 1536
                || thoughtAwareEmbedding == null
                || thoughtAwareEmbedding.length != 1536) {
            throw new IllegalArgumentException(
                    "임베딩 벡터는 1536차원이어야 합니다."
            );
        }
        // 자동사고를 제외한 유사 사례 검색용 벡터 저장
        this.contextEmbedding = contextEmbedding;

        // 자동사고를 포함한 유사 사례 검색용 벡터 저장
        this.thoughtAwareEmbedding = thoughtAwareEmbedding;
    }

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
