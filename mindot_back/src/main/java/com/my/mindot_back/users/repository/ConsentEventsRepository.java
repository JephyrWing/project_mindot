// consent_events 테이블에 동의, 철회 이력을 저장, 조회하는 Repository
package com.my.mindot_back.users.repository;

import com.my.mindot_back.users.entity.ConsentEvents;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsentEventsRepository
        extends JpaRepository<ConsentEvents, Long> {

}
