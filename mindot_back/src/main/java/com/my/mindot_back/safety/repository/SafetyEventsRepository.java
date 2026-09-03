// 안전 이벤트를 저장하고, 본인 기록의 안전 안내 이력을 조회하는 Repository
package com.my.mindot_back.safety.repository;

import com.my.mindot_back.safety.entity.SafetyEvents;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SafetyEventsRepository
        extends JpaRepository<SafetyEvents, Long> {

    // 감정 기록에 연결된 가장 최근 안전 이벤트 1건 조회
    Optional<SafetyEvents>
    findFirstByEmotionRecords_IdOrderByCreatedAtDesc(
            Long emotionRecordId
    );

    // 로그인 사용자가 소유한 감정 기록의 안전 이벤트만 조회
    Optional<SafetyEvents>
    findByIdAndEmotionRecords_User_Id(
            Long safetyEventId,
            Long userId
    );

    // 회원별 누적 안전 신호 발생 횟수 집계
    @Query("""
            SELECT
                safetyEvent.emotionRecords.user.id AS userId,
                COUNT(safetyEvent) AS safetyEventCount
            FROM SafetyEvents safetyEvent
            GROUP BY safetyEvent.emotionRecords.user.id
            """)
    List<SafetyEventUserCountProjection> countSafetyEventsByUser();

    // 회원별 안전 신호 횟수 조회 결과 Projection
    interface SafetyEventUserCountProjection {

        Long getUserId();

        Long getSafetyEventCount();
    }
}