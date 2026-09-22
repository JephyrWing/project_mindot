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

    // 사용자가 최종 선택한 BEFORE/AFTER 목록으로 다시 저장하기 전 기존 단계를 비운다.
    void deleteAllBySession_IdAndPhase(
            Long sessionId,
            DistortionPhase phase
    );

    // 패턴 분석용: 사용자가 확정한 성찰 전 인지왜곡 라벨 조회
    List<SessionDistortions> findAllBySession_IdAndPhaseAndReviewStatus(
            Long sessionId,
            DistortionPhase phase,
            DistortionReviewStatus reviewStatus
    );

    // 여러 완료 CBT 세션의 사용자 확정 인지왜곡 라벨을 한 번에 조회
    List<SessionDistortions> findAllBySession_IdInAndReviewStatus(
            List<Long> sessionIds,
            DistortionReviewStatus reviewStatus
    );
}
