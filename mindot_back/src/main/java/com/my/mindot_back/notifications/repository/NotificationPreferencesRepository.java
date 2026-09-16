// 사용자별 알림 설정 조회와 저장 Repository
package com.my.mindot_back.notifications.repository;

import com.my.mindot_back.notifications.entity.NotificationPreferences;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

import java.util.List;
import java.util.Optional;


public interface NotificationPreferencesRepository
        extends JpaRepository<NotificationPreferences, Long> {

    // 사용자 알림 설정 조회
    Optional<NotificationPreferences> findByUser_Id(
            Long userId
    );

    // 반복 패턴 알림을 활성화한 사용자 설정 조회
    @EntityGraph(attributePaths = "user")
    List<NotificationPreferences> findAllByPatternAlertEnabledTrue();
}