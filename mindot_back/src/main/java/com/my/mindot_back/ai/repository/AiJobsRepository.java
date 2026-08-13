// AI 작업 이력을 조회·저장하는 Repository
package com.my.mindot_back.ai.repository;

import com.my.mindot_back.ai.entity.AiJobs;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiJobsRepository extends JpaRepository<AiJobs, Long> {
}