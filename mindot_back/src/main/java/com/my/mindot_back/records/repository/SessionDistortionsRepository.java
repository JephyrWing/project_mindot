// session_distortions 테이블의 인지왜곡 라벨을 저장, 조회하는 repository
package com.my.mindot_back.records.repository;

import com.my.mindot_back.records.entity.SessionDistortions;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionDistortionsRepository
            extends JpaRepository<SessionDistortions, Long> {
}
