// 반복 패턴 알림 저장과 사용자별 조회 Repository
package com.my.mindot_back.notifications.repository;

import com.my.mindot_back.notifications.entity.PatternNotification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PatternNotificationRepository
        extends JpaRepository<PatternNotification, Long> {

    // 사용자 알림 최신순 조회
    Page<PatternNotification> findAllByUser_IdAndDeletedAtIsNullOrderByCreatedAtDesc(
            Long userId,
            Pageable pageable
    );

    // 사용자 소유 알림 단건 조회
    Optional<PatternNotification> findByIdAndUser_IdAndDeletedAtIsNull(
            Long notificationId,
            Long userId
    );

    // 읽지 않은 알림 수 조회
    long countByUser_IdAndReadAtIsNullAndDeletedAtIsNull(
            Long userId
    );

    // 중복 방지 키에 해당하는 기존 알림 조회
    Optional<PatternNotification> findByDeduplicationKey(
            String deduplicationKey
    );
}
