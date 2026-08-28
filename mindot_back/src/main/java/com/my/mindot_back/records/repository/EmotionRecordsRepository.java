// emotion_records 테이블의 감정 기록을 저장, 조회하는 repository
package com.my.mindot_back.records.repository;

import com.my.mindot_back.records.entity.EmotionRecords;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface EmotionRecordsRepository
        extends JpaRepository<EmotionRecords, Long> {

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
}
