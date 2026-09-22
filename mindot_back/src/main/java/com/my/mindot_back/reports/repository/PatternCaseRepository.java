// 패턴에 연결된 근거 감정 기록을 관리하는 Repository
package com.my.mindot_back.reports.repository;

import com.my.mindot_back.reports.entity.PatternCase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PatternCaseRepository extends JpaRepository<PatternCase, Long> {
    @Modifying
    @Query("delete from PatternCase c where c.personalPattern.id = :patternId")
    void deleteAllByPatternId(@Param("patternId") Long patternId);

    @Query("select c from PatternCase c join fetch c.emotionRecord where c.personalPattern.id = :patternId")
    List<PatternCase> findAllWithRecordByPatternId(@Param("patternId") Long patternId);
}
