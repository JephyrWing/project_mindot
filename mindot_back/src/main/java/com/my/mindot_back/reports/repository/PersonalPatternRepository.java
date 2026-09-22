// 사용자별 반복 패턴 식별자와 피드백을 조회하는 Repository
package com.my.mindot_back.reports.repository;

import com.my.mindot_back.reports.entity.PersonalPattern;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PersonalPatternRepository extends JpaRepository<PersonalPattern, Long> {
    List<PersonalPattern> findAllByUser_Id(Long userId);
    Optional<PersonalPattern> findByIdAndUser_Id(Long patternId, Long userId);
}
