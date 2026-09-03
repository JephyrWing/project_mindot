// 리포트를 조회, 저장하는 Repository
package com.my.mindot_back.reports.repository;

import com.my.mindot_back.reports.entity.ReportType;
import com.my.mindot_back.reports.entity.Reports;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface ReportsRepository extends JpaRepository<Reports, Long> {

    // 사용자, 리포트 유형, 기간이 모두 일치하는 기존 리포트 조회
    Optional<Reports> findByUser_IdAndReportTypeAndPeriodStartAndPeriodEnd(
            Long userId,
            ReportType reportType,
            LocalDate periodStart,
            LocalDate periodEnd
    );

    // 감정 기록 변경 시 최신 원본으로 다시 만들 수 있도록 사용자 리포트 캐시 전체 삭제
    long deleteByUser_Id(Long userId);
}
