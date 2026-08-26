// session_distortions 테이블의 인지왜곡 라벨을 저장, 조회하는 repository
package com.my.mindot_back.records.repository;

import com.my.mindot_back.records.entity.DistortionPhase;
import com.my.mindot_back.records.entity.DistortionReviewStatus;
import com.my.mindot_back.records.entity.SessionDistortions;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SessionDistortionsRepository
            extends JpaRepository<SessionDistortions, Long> {

    // 특정 성찰 세션의 BEFORE 단계 인지왜곡 목록 조회
    List<SessionDistortions> findAllBySession_IdAndPhase(
            Long sessionId,
            DistortionPhase phase
    );

    // 패턴 분석용: 사용자가 확정한 성찰 전 인지왜곡 라벨 조회
    List<SessionDistortions> findAllBySession_IdAndPhaseAndReviewStatus(
            Long sessionId,
            DistortionPhase phase,
            DistortionReviewStatus reviewStatus
    );
}
