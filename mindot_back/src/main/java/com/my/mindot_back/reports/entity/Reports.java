// 사용자별 주간·월간 감정 분석 결과를 저장하는 Entity
package com.my.mindot_back.reports.entity;

import com.my.mindot_back.users.entity.Users;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.*;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(
        name = "reports",

        // 중복 저장 x
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_reports_user_type_period",
                        columnNames = {
                                "user_id",
                                "report_type",
                                "period_start",
                                "period_end"
                        }
                )
        },

        indexes = {
                // 사용자별 리포트를 최신순으로 조회할 때 사용
                @Index(
                        name = "idx_reports_user_period_start",
                        columnList = "user_id, period_start DESC"
                )
        }
)
@Check(
        name = "chk_reports_period_range",
        // 분석 종료일은 시작일보다 빠르면 안됨
        constraints = "period_end >= period_start"
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reports {

    // 리포트 식별자
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 리포트 사용자
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    // 사용자가 삭제되면 해당 사용자의 리포트도 삭제
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "user_id", nullable = false)
    private Users user;

    // 리포트 기간 유형 (주간, 월간)
    @Enumerated(EnumType.STRING)
    @Column(name = "report_type", nullable = false, length = 20)
    private ReportType reportType;

    // 분석 시작일
    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;
    // LocalDate: 2026-08-01 날짜 범위

    // 분석 종료일
    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    // 시간대·상황·감정·자동적 사고·인지왜곡·CBT 전후 변화의 표시용 통계와 요약
    @JdbcTypeCode(SqlTypes.JSON)
    @ColumnDefault("'{}'::jsonb")  // 빈 json 객체를 jsonb 타입으로 저장
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> content = new HashMap<>();

    // 어느 시점까지의 원본 데이터를 바탕으로 리포트 만들었는지 표시
    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(name = "source_snapshot_at", nullable = false, updatable = false)
    private Instant sourceSnapshotAt;
    // Instant: 시간도 필요

    // 리포트 생성 메타데이터(AI모델, 프롬프트 버전, AI 작업 ID 등)
    @JdbcTypeCode(SqlTypes.JSON)
    @ColumnDefault("'{}'::jsonb")
    @Column(name = "ai_meta", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> aiMeta = new HashMap<>();

    // 리포트 행이 생성된 시각
    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // DB 저장전 원본, 생성 시각을 현재 시각으로 설정ㅈ
    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        this.sourceSnapshotAt = now;
        this.createdAt = now;
    }
}
