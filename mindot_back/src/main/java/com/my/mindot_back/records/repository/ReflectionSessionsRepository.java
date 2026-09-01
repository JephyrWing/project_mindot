// reflection_sessions 테이블의 CBT 성찰 세션을 저장, 조회하는 repository
package com.my.mindot_back.records.repository;

import com.my.mindot_back.common.rag.CbtSimilaritySearchRequest;
import com.my.mindot_back.records.entity.ReflectionSessionStatus;
import com.my.mindot_back.records.entity.ReflectionSessions;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReflectionSessionsRepository
            extends JpaRepository<ReflectionSessions, Long> {

    // 감정 기록에 성찰 세션이 이미 생성되어 있는지 확인 (중복 확인)
    boolean existsByEmotionRecord_Id(Long emotionRecordId);

    // 감정 기록과 로그인 사용자에게 연결된 CBT 성찰 세션 조회
    Optional <ReflectionSessions> findByEmotionRecord_IdAndUser_Id(
            Long emotionRecordId,
            Long userId
    );

    @Query(value = """
            SELECT rs.*
            FROM reflection_sessions rs
            WHERE rs.user_id = :#{#request.userId}
              AND rs.status = 'COMPLETED'
              AND rs.context_embedding IS NOT NULL
              AND rs.user_confirmed = true
              AND 1 - (
                    rs.context_embedding
                    <=> CAST(
                        :#{#request.embeddedQueryString}
                        AS vector
                    )
              ) >= :#{#request.threshold}
            ORDER BY
                rs.context_embedding
                <=> CAST(
                    :#{#request.embeddedQueryString}
                    AS vector
                )
            LIMIT :#{#request.topK}
            """,
            nativeQuery = true)

    List<ReflectionSessions> findSimilarByContext(
            @Param("request")
            CbtSimilaritySearchRequest request
    );

    @Query(value = """
            SELECT rs.*
            FROM reflection_sessions rs
            WHERE rs.user_id = :#{#request.userId}
              AND rs.status = 'COMPLETED'
              AND rs.thought_aware_embedding IS NOT NULL
              AND rs.user_confirmed = true
              AND 1 - (
                    rs.thought_aware_embedding
                    <=> CAST(
                        :#{#request.embeddedQueryString}
                        AS vector
                    )
              ) >= :#{#request.threshold}
            ORDER BY
                rs.thought_aware_embedding
                <=> CAST(
                    :#{#request.embeddedQueryString}
                    AS vector
                )
            LIMIT :#{#request.topK}
            """,
            nativeQuery = true)
    List<ReflectionSessions> findSimilarByThoughtAware(
            @Param("request")
            CbtSimilaritySearchRequest request
    );

    // 로그인 사용자의 OPEN 상태 성찰 세션을 최신 생성 순으로 조회
    List<ReflectionSessions> findAllByUser_IdAndStatusOrderByCreatedAtDesc(
            Long userId,
            ReflectionSessionStatus status
    );

    // 패턴 분석에 사용할 사용자 확정 완료 CBT 세션 수 조회
    long countByUser_IdAndStatusAndUserConfirmedTrue(
            Long userId,
            ReflectionSessionStatus status
    );

    // 패턴 분석에 사용할 서로 다른 날에 발생한 감정기록이 몇개인지 조회 (완료된 CBT만)
    @Query(value = """
                    SELECT COUNT(DISTINCT DATE(er.occurred_at AT TIME ZONE er.record_timezone))
                    FROM reflection_sessions rs
                    JOIN emotion_records er ON er.id = rs.emotion_record_id
                    WHERE rs.user_id = :userId
                        AND rs.status = 'COMPLETED'
                        AND rs.user_confirmed = true
                    """, nativeQuery = true)
    long countDistinctCompletedReflectionDates(
            @Param("userId") Long userId
    );

    // 사용자에게 도움이 된 확정 완료 CBT 세션 존재 여부 조회
    boolean existsByUser_IdAndStatusAndUserConfirmedTrueAndHelpfulnessScoreGreaterThanEqual(
            Long userId,
            ReflectionSessionStatus status,
            short helpfulnessScore
    );

    // 선택한 기간에 최종 확정된 CBT 성찰 세션을 완료 시각 오래된 순으로 조회
    List<ReflectionSessions>
    findAllByUser_IdAndStatusAndUserConfirmedTrueAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAsc(
            Long userId,
            ReflectionSessionStatus status,
            Instant periodStart,
            Instant periodEndExclusive
    );

    // 선택한 감정 기록에 연결된 완료, 확정, CBT 세션 조회
    List<ReflectionSessions>
    findAllByUser_IdAndEmotionRecord_IdInAndStatusAndUserConfirmedTrueOrderByCompletedAtAsc(
            Long userId,
            List<Long> emotionRecordIds,
            ReflectionSessionStatus status
    );
}

