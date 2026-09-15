// consent_events 테이블에 동의, 철회 이력을 저장, 조회하는 Repository
package com.my.mindot_back.users.repository;

import com.my.mindot_back.users.entity.ConsentEvents;
import com.my.mindot_back.users.entity.ConsentType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ConsentEventsRepository
        extends JpaRepository<ConsentEvents, Long> {

    // 사용자와 동의 종류가 일치하는 가장 최근 상태 조회
    Optional<ConsentEvents>
    findFirstByUser_IdAndConsentTypeOrderByOccurredAtDescIdDesc(
            Long userId,
            ConsentType consentType
    );

    // 사용자의 전체 동의 변경 이력을 최신순으로 페이징 조회
    Page<ConsentEvents>
    findAllByUser_IdOrderByOccurredAtDescIdDesc(
            Long userId,
            Pageable pageable
    );
}
