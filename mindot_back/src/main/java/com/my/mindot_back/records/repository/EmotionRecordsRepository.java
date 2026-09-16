// emotion_records 테이블의 감정 기록을 저장, 조회하는 repository
package com.my.mindot_back.records.repository;

import com.my.mindot_back.records.entity.EmotionRecords;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface EmotionRecordsRepository
        extends JpaRepository<EmotionRecords, Long>,
        JpaSpecificationExecutor<EmotionRecords> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select e from EmotionRecords e where e.id = :id")
    java.util.Optional<EmotionRecords> findLockedById(@org.springframework.data.repository.query.Param("id") Long id);


    // 로그인한 사용자의 감정 기록을 발생 시각 최신순으로 조회
    List<EmotionRecords> findAllByUser_IdOrderByOccurredAtDesc(
            Long userId
    );

    // 기록 ID와 사용자 ID가 모두 일치하는 감정 기록 1건 조회
    Optional<EmotionRecords> findByIdAndUser_Id(
            Long emotionRecordId,
            Long userId
    );

    // 선택한 기간에 발생한 로그인 사용자의 감정 기록을 오래된 순으로 조회
    List<EmotionRecords>
    findAllByUser_IdAndOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByOccurredAtAsc(
            Long userId,
            Instant periodStart,
            Instant periodEndExclusive
    );

    // 로그인 사용자의 검색 벡터가 생성된 감정 기록을 코사인 유사도순으로 조회
    @Query(
            value = """
                SELECT emotion_record.*
                FROM emotion_records emotion_record
                WHERE emotion_record.user_id = :#{#search.userId}
                  AND emotion_record.search_embedding IS NOT NULL
                  AND (
                        CAST(
                            :#{#search.periodStart}
                            AS timestamptz
                        ) IS NULL
                        OR emotion_record.occurred_at
                            >= CAST(
                                :#{#search.periodStart}
                                AS timestamptz
                            )
                  )
                  AND (
                        CAST(
                            :#{#search.periodEndExclusive}
                            AS timestamptz
                        ) IS NULL
                        OR emotion_record.occurred_at
                            < CAST(
                                :#{#search.periodEndExclusive}
                                AS timestamptz
                            )
                  )
                  AND (
                        CAST(
                            :#{#search.emotionCode}
                            AS text
                        ) IS NULL
                        OR emotion_record.primary_emotion_code
                            = CAST(
                                :#{#search.emotionCode}
                                AS text
                            )
                  )
                  AND (
                        CAST(
                            :#{#search.contextCategory}
                            AS text
                        ) IS NULL
                        OR emotion_record.context_category
                            = CAST(
                                :#{#search.contextCategory}
                                AS text
                            )
                  )
                  AND 1 - (
                        emotion_record.search_embedding
                        <=> CAST(
                            :#{#search.embeddedQueryString}
                            AS vector
                        )
                  ) >= :#{#search.threshold}
                ORDER BY
                    emotion_record.search_embedding
                    <=> CAST(
                        :#{#search.embeddedQueryString}
                        AS vector
                    ),
                    emotion_record.occurred_at DESC,
                    emotion_record.id DESC
                """,
            countQuery = """
                SELECT COUNT(*)
                FROM emotion_records emotion_record
                WHERE emotion_record.user_id = :#{#search.userId}
                  AND emotion_record.search_embedding IS NOT NULL
                  AND (
                        CAST(
                            :#{#search.periodStart}
                            AS timestamptz
                        ) IS NULL
                        OR emotion_record.occurred_at
                            >= CAST(
                                :#{#search.periodStart}
                                AS timestamptz
                            )
                  )
                  AND (
                        CAST(
                            :#{#search.periodEndExclusive}
                            AS timestamptz
                        ) IS NULL
                        OR emotion_record.occurred_at
                            < CAST(
                                :#{#search.periodEndExclusive}
                                AS timestamptz
                            )
                  )
                  AND (
                        CAST(
                            :#{#search.emotionCode}
                            AS text
                        ) IS NULL
                        OR emotion_record.primary_emotion_code
                            = CAST(
                                :#{#search.emotionCode}
                                AS text
                            )
                  )
                  AND (
                        CAST(
                            :#{#search.contextCategory}
                            AS text
                        ) IS NULL
                        OR emotion_record.context_category
                            = CAST(
                                :#{#search.contextCategory}
                                AS text
                            )
                  )
                  AND 1 - (
                        emotion_record.search_embedding
                        <=> CAST(
                            :#{#search.embeddedQueryString}
                            AS vector
                        )
                  ) >= :#{#search.threshold}
                """,
            nativeQuery = true
    )
    Page<EmotionRecords> searchSemantically(
            @Param("search")
            EmotionRecordSemanticSearchQuery search,
            Pageable pageable
    );
}
