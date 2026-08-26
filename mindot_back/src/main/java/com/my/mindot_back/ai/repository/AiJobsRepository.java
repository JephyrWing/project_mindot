// AI 작업 이력을 조회·저장하는 Repository
package com.my.mindot_back.ai.repository;

import com.my.mindot_back.ai.entity.AiJobEntityType;
import com.my.mindot_back.ai.entity.AiJobs;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiJobsRepository extends JpaRepository<AiJobs, Long> {

    // 특정 사용자의 대상 Entity와 연결된 AI 작업 이력 삭제
    void deleteAllByUser_IdAndEntityTypeAndEntityId(
            Long userId,
            AiJobEntityType entityType,
            Long entityId
    );
}