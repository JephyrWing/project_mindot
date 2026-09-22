// 오늘의 마음 돌봄 추천을 사용자와 날짜로 조회하는 Repository
package com.my.mindot_back.dailycare.repository;

import com.my.mindot_back.dailycare.entity.DailyCareRecommendation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface DailyCareRecommendationRepository extends JpaRepository<DailyCareRecommendation, Long> {
    Optional<DailyCareRecommendation> findByUser_IdAndRecommendationDate(
            Long userId,
            LocalDate date
    );
    Optional<DailyCareRecommendation> findByIdAndUser_Id(
            Long recommendationId,
            Long userId
    );
}
