// 사용자의 감정 원문과 AI 구조화 결과를 저장하는 Entity
package com.my.mindot_back.records.entity;

import com.my.mindot_back.records.dto.ai.FastApiRecordAnalysisResponseDto;
import com.my.mindot_back.users.entity.Users;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.*;
import org.hibernate.type.SqlTypes;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Entity
// DB의 emotion_records 테이블과 연결
@Table(
        name = "emotion_records",
        indexes = {
                // 사용자별 최신 감정 기록 목록 조회용
                @Index(
                        name = "idx_emotion_records_user_occurred_at",
                        columnList = "user_id, occurred_at DESC"
                ),
                // 사용자별 감정 분포 통계 조회용
                @Index(
                        name = "idx_emotion_records_user_emotion_occurred_at",
                        columnList = "user_id, primary_emotion_code, occurred_at DESC"
                ),
                // 사용자별 상황 범주 통계 조회용
                @Index(
                        name = "idx_emotion_records_user_context_occurred_at",
                        columnList = "user_id, context_category, occurred_at DESC"
                ),
                // 사용자별 시간대별 감정 기록 통계 조회용
                @Index(
                        name = "idx_emotion_records_user_time_bucket_occurred_at",
                        columnList = "user_id, time_bucket, occurred_at DESC"
                ),
                // 사용자별 평일·주말 감정 기록 통계 조회용
                @Index(
                        name = "idx_emotion_records_user_weekday_type_occurred_at",
                        columnList = "user_id, weekday_type, occurred_at DESC"
                )
        }
)
@Check(
        name = "chk_emotion_records_primary_intensity",
        constraints = "primary_intensity BETWEEN 0 AND 10"
)
@Getter
// JPA만 빈 객체 만들 수 있고, 다른 코드에서 불완전한 기록 생성 막음
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EmotionRecords {

    // 감정 기록 식별자
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 기록 작성한 사용자
    // 사용자 1명은 감정 기록 여러개 작성 가능
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    // 사용자가 완전히 삭제되면 해당 사용자의 감정 기록도 함께 삭제
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "user_id", nullable = false)
    private Users user;

    // 감정이 발생한 시각
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    // 기록 시각을 해석할 기준 시간대
    @ColumnDefault("'Asia/Seoul'")
    @Column(
            name = "record_timezone",
            nullable = false,
            length = 50
    )
    private String recordTimezone = "Asia/Seoul";

    // occurredAt과 recordTimezone을 기준으로 Spring이 계산한 시간대
    // ex. MORNING
    @Enumerated(EnumType.STRING)
    @Column(name = "time_bucket", nullable = false, length = 20)
    private TimeBucket timeBucket;

    // occurredAt과 recordTimezone을 기준으로 Spring이 계산한 평일·주말 구분
    @Enumerated(EnumType.STRING)
    @Column(name = "weekday_type", nullable = false, length = 20)
    private WeekdayType weekdayType;

    // 입력 방식 (text, voice STT)
    @Enumerated(EnumType.STRING)
    @Column(name = "input_type", nullable = false, length = 20)
    private InputType inputType;

    // 사용자 원문
    @Column(name = "raw_text", nullable = false, columnDefinition = "TEXT")
    private String rawText;

    // 객관적 사건 중심 상황
    @Column(name = "situation_text", columnDefinition = "TEXT")
    private String situationText;

    // CBT 질문 전 자동적 사고
    @Column(name = "automatic_thought", columnDefinition = "TEXT")
    private String automaticThought;

    // 주 감정 코드
    @Column(name = "primary_emotion_code", length = 50)
    private String primaryEmotionCode;

    // 질문 전 주 감정 강도 (0~10)
    @Column(name = "primary_intensity")
    private Short primaryIntensity;

    // 복수 보조 감정  예시 : [{"code":"SADNESS","intensity":5}]
    // java 객체를  db의 json 타입으로 저장, 조회하는 방법을 hibernate에 알려줌
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "secondary_emotions", nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> secondaryEmotions = new ArrayList<>();

    // 상황 범주
    @Column(name = "context_category", length = 50)
    private String contextCategory;

    // 상황과 관련된 상대방 관계 유형
    @Column(name = "related_person_type", length = 50)
    private String relatedPersonType;

    // 추가 정보 (해석, 신체반응, 행동, 원한 반응)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> details = new HashMap<>();

    // 기록 또는 보완 완료 상태
    @Enumerated(EnumType.STRING)
    @Column(name = "completion_status", nullable = false, length = 20)
    private CompletionStatus completionStatus = CompletionStatus.QUICK;

    // AI 처리 관련 정보 : AI 모델명, 프롬프트 버전, 처리 결과 등
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ai_meta", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> aiMeta = new HashMap<>();

    // 기록 생성 시각
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // 마지막 수정 시각
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // AI 구조화 전, 사용자가 보낸 간편 감정기록 원문 생성
    // AI 분석값X -> completionStatus는 기본값 QUICK 유지
    public static EmotionRecords createQuick(
            Users user,
            String rawText,
            InputType inputType,
            Instant occurredAt
    ) {
        EmotionRecords emotionRecord = new EmotionRecords();

        // 로그인한 기록 작성자
        emotionRecord.user = user;

        // DTO로 받은 원문, 입력 방식, 감정 발생 시각
        emotionRecord.rawText = rawText;
        emotionRecord.inputType = inputType;
        emotionRecord.occurredAt = occurredAt;

        // Instant는 UTC 기준 -> 변환 후 평일, 주말 계산
        ZonedDateTime occurredAtInSeoul = occurredAt.atZone(
                ZoneId.of(emotionRecord.recordTimezone)
        );

        // 변환된 한국 시간에서 '시'만 가져옴
        int hour = occurredAtInSeoul.getHour();

        // 발생 시각의 ‘시’를 기준으로 시간대 구분
        if (hour < 6) {
            emotionRecord.timeBucket = TimeBucket.DAWN;
        } else if (hour < 12) {
            emotionRecord.timeBucket = TimeBucket.MORNING;
        } else if (hour < 18) {
            emotionRecord.timeBucket = TimeBucket.AFTERNOON;
        } else if (hour < 21) {
            emotionRecord.timeBucket = TimeBucket.EVENING;
        } else {
            emotionRecord.timeBucket = TimeBucket.NIGHT;
        }

        DayOfWeek dayOfWeek = occurredAtInSeoul.getDayOfWeek();

        // 토요일·일요일이면 WEEKEND, 나머지는 WEEKDAY
        if (dayOfWeek == DayOfWeek.SATURDAY
                || dayOfWeek == DayOfWeek.SUNDAY) {
            emotionRecord.weekdayType = WeekdayType.WEEKEND;
        } else {
            emotionRecord.weekdayType = WeekdayType.WEEKDAY;
        }

        // service로 return
        return emotionRecord;
    }

    // FastAPI가 반환한 구조화 결과를 현재 감정 기록에 반영

    // AI 분석 후 사용자 추가 확인 필요 > PARTIAL 상태로 변경
    public void applyAiAnalysis(
            FastApiRecordAnalysisResponseDto analysis
    ) {
        FastApiRecordAnalysisResponseDto.StructuredRecord record =
                analysis.record();

        if (record == null) {
            throw new IllegalArgumentException("AI 구조화 결과가 없습니다.");
        }

        // Entity의 일반 컬럼에 저장할 구조화 값
        this.situationText = record.situation();
        this.automaticThought = record.automaticThought();
        this.contextCategory = record.contextCategory();
        this.relatedPersonType = record.relatedPersonType();

        // FastAPI emotions 배열의 첫번째 감정은 주 감정,
        // 두번째부터는 보조감정, JSON 배열로 저장

        // 초기화
        this.primaryEmotionCode = null;
        this.primaryIntensity = null;
        this.secondaryEmotions = new ArrayList<>();

        List<FastApiRecordAnalysisResponseDto.EmotionItem> emotions =
                record.emotions();

        // 배열의 첫번째 감정 가져옴 > 주감정
        if(emotions != null && !emotions.isEmpty()) {
            FastApiRecordAnalysisResponseDto.EmotionItem primaryEmotion =
                    emotions.get(0);  // List는 0번부터 시작

            // 첫번째 감정 코드를 primary_emotion_code 컬럼에 저장할 값으로 넣음
            // 예시: "ANXIETY"
            this.primaryEmotionCode = primaryEmotion.code();

            if(primaryEmotion.intensity() != null) {
                this.primaryIntensity =
                        primaryEmotion.intensity().shortValue();
            }

            // 보조감정: 두번재 감정부터 끝까지 반복
            for (int index = 1; index < emotions.size(); index++) {
                FastApiRecordAnalysisResponseDto.EmotionItem emotion =
                        emotions.get(index);

                // 보조 감정 하나를 JSON 객체 모양으로 만들 빈 Map, 키 : 값
                Map<String, Object> secondaryEmotion = new HashMap<>();
                secondaryEmotion.put("code", emotion.code()); // 키
                secondaryEmotion.put("intensity", emotion.intensity()); // 값

                // 보조 감정 JSON 객체를 보조 감정 목록에 추가
                this.secondaryEmotions.add(secondaryEmotion);
            }
        }

        // 일반 컬럼이 없는 추가 구조화 값은 details JSONB에 저장
        this.details = new HashMap<>();
        putIfNotNull(this.details, "interpretation", record.interpretation());
        putIfNotNull(this.details, "bodyReaction", record.bodyReaction());
        putIfNotNull(this.details, "behavior", record.behavior());

        // AI 모델, 프롬프트 버전, 위험 판단은 ai_meta JSONB에 저장
        this.aiMeta = new HashMap<>();

        if (analysis.meta() != null) {
            putIfNotNull(this.aiMeta, "model", analysis.meta().model());
            putIfNotNull(
                    this.aiMeta,
                    "promptVersion",
                    analysis.meta().promptVersion()
            );
        }

        if (analysis.risk() != null) {
            putIfNotNull(
                    this.aiMeta,
                    "riskLevel",
                    analysis.risk().level()
            );
            putIfNotNull(
                    this.aiMeta,
                    "riskReason",
                    analysis.risk().reason()
            );
        }

        // AI 구조화 끝 but 사용자 확인 전 -> PARTIAL
        this.completionStatus = CompletionStatus.PARTIAL;
    }

    // null 값은 JSONB Map에 넣지 않음 (공통 메서드)
    // AI가 분석해 반환한 정보만 저장
    private static void putIfNotNull(
            Map<String, Object> target,
            String key,
            String value
    ) {
        if (value != null) {
            target.put(key, value);
        }
    }

    // DB insert 전 JPA가 자동 호출하는 메서드
    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    // DB update  전 JPA가 자동 호출하는 메서드
    @PreUpdate
    void preUpdate() {
        this.updatedAt = Instant.now();
    }
}
