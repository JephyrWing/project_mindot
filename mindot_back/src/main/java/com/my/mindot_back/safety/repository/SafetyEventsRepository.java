// 안전 이벤트를 조회, 저장하는 Repository
package com.my.mindot_back.safety.repository;

import com.my.mindot_back.safety.entity.SafetyEvents;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SafetyEventsRepository  extends JpaRepository<SafetyEvents, Long> {
}
