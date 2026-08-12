// reflection_sessions 테이블의 CBT 성찰 세션을 저장, 조회하는 repository
package com.my.mindot_back.records.repository;

import com.my.mindot_back.records.entity.ReflectionSessions;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReflectionSessionsRepository
            extends JpaRepository<ReflectionSessions, Long> {
}
