// 리포트를 조회, 저장하는 Repository
package com.my.mindot_back.reports.repository;

import com.my.mindot_back.reports.entity.Reports;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportsRepository extends JpaRepository<Reports, Long> {
}
