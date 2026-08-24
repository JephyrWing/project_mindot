// CBT 성찰 세션에 붙은 인지왜곡 라벨 Entity
package com.my.mindot_back.records.entity;

import com.my.mindot_back.distortions.entity.DistortionTypes;
import org.hibernate.annotations.ColumnDefault;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
        name = "session_distortions",
        // 중복 저장되지 않도록 unique 설정
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_session_distortions_session_phase_type",
                        columnNames = {
                                "session_id",
                                "phase",
                                "distortion_type_id"
                        }
                )
        },
        indexes = {
                // 특정 인지왜곡 유형이 붙은 성찰 결과를 조회할 때 사용
                @Index(
                        name = "idx_session_distortions_distortion_type_id",
                        columnList = "distortion_type_id"
                )
        }
)
// AI 분류 확률: null 허용, 값이 있다면 0~1로 저장
@Check(
        name = "chk_session_distortions_classifier_confidence",
        constraints = "classifier_confidence BETWEEN 0 AND 1"
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SessionDistortions {

    // 세션 인지왜곡 라벨 식별자
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 이 인지왜곡 라벨이 붙은 CBT 성찰 세션
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    // 성찰 세션이 삭제되면 해당 세션의 인지왜곡 라벨도 함께 삭제
    @OnDelete(action = OnDeleteAction.CASCADE)
    // reflection_sessions.id 참조
    @JoinColumn(name = "session_id", nullable = false)
    private ReflectionSessions session;

    // 질문 전, 질문 후 인지 왜곡 구분
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DistortionPhase phase;

    // 인지왜곡 유형
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    // distortion_types.id 참조
    @JoinColumn(name = "distortion_type_id", nullable = false)
    private DistortionTypes distortionType;

    // 최초 라벨 제안 주체 (AI or USER)
    @Enumerated(EnumType.STRING)
    @ColumnDefault("'AI'")
    @Column(nullable = false, length = 20)
    private DistortionSource source = DistortionSource.AI;

    // 사용자가 라벨을 검토한 상태
    @Enumerated(EnumType.STRING)
    @ColumnDefault("'PROPOSED'")
    @Column(name = "review_status", nullable = false, length = 20)
    private DistortionReviewStatus reviewStatus =
            DistortionReviewStatus.PROPOSED;

    // AI 분류 확률 0.0000~1.0000
    // 사용자가 직접 추가한 라벨은 null 가능
    @Column(
            name = "classifier_confidence",
            precision = 5,  // 숫자 전체 자릿수는 최대 5자리
            scale = 4        // 소수점 아래 자릿수는 최대 4자리
    )
    private BigDecimal classifierConfidence;

    // 사용자가 라벨을 확인 or 거절한 시각
    // 아직 제안 상태(PROPOSED)면 null
    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    // 인지왜곡 라벨 행이 생성된 시각
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // FastAPI가 제안한 성찰 전 인지왜곡 라벨 생성
    // source는 AI, reviewStatus는 PROPOSED가 기본값
    public static SessionDistortions createAiProposal(
            // 어느 CBT 성찰 세션의 라벨인지 연결
            ReflectionSessions session,

            // distortion_types 테이블의 기준 인지왜곡 유형
            DistortionTypes distortionType,

            // FastAPI가 반환한 분류 신뢰도: 0.0 ~ 1.0
            Double classifierConfidence
    ){
        // FastAPI 응답이 비정상일 때 DB 제약 조건 오류 전 Java에서 검사
        if(classifierConfidence == null
                || classifierConfidence < 0
                || classifierConfidence > 1){
            throw new IllegalArgumentException(
                    "인지왜곡 분류 신뢰도는 0과 1 사이여야 합니다."
            );
        }
        SessionDistortions sessionDistortion =
                new SessionDistortions();

        // reflection_sessions.id 연결
        sessionDistortion.session = session;

        // FastAPI start 응답은 CBT 질문 전 자동사고를 기준으로 한 제안이므로
        // BEFORE 단계 인지왜곡으로 저장
        sessionDistortion.phase = DistortionPhase.BEFORE;

        // distortion_types.id 연결
        sessionDistortion.distortionType = distortionType;

        // numeric(5,4) 타입에 맞도록 BigDecimal로 변환
        sessionDistortion.classifierConfidence =
                BigDecimal.valueOf(classifierConfidence);

        return sessionDistortion;
    }


    // DB 저장 전 한번 실행
    // 라벨 생성 시각을 현재 시각으로 저장
    @PrePersist
    void prePersist() {
        this.createdAt = Instant.now();
    }
}
