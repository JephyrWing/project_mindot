// 안전 이벤트를 저장하고, 본인 기록의 안전 안내 이력을 조회하는 Repository
package com.my.mindot_back.safety.repository;

import com.my.mindot_back.safety.entity.SafetyEvents;
import org.springframework.data.jpa.repository.JpaRepository;

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
}